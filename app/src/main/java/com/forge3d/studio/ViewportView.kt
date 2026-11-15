package com.forge3d.studio

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.forge3d.studio.engine.GLRenderer
import com.forge3d.studio.engine.Math3D
import com.forge3d.studio.engine.Mesh
import com.forge3d.studio.engine.Primitives
import com.forge3d.studio.engine.SceneObject
import com.forge3d.studio.mcp.AppModel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Viewport táctil: 1 dedo = órbita o transformar objeto seleccionado,
 * 2 dedos = pinch zoom + desplazar, toque corto = seleccionar.
 */
class ViewportView(context: Context, attrs: AttributeSet?) : GLSurfaceView(context, attrs) {

    companion object {
        const val MODE_ORBIT = 0
        const val MODE_MOVE = 1
        const val MODE_ROTATE = 2
        const val MODE_SCALE = 3
    }

    var mode = MODE_ORBIT
    var onSelectChanged: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pointers = 0
    private var pinchStart = 0f
    private var pinchDist = 0f
    private var panLastX = 0f
    private var panLastY = 0f

    private val renderer = GLRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun renderer(): GLRenderer = renderer

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointers = 1
                lastX = ev.x; lastY = ev.y
                downX = ev.x; downY = ev.y
                downTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointers++
                if (pointers == 2) {
                    pinchStart = fingerDist(ev)
                    pinchDist = AppModel.camera.distance
                    panLastX = (ev.getX(0) + ev.getX(1)) / 2f
                    panLastY = (ev.getY(0) + ev.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointers >= 2) {
                    val d = fingerDist(ev)
                    if (pinchStart > 0f && d > 0f) {
                        synchronized(AppModel.lock) {
                            AppModel.camera.distance = (pinchDist * pinchStart / d).coerceIn(0.05f, 500f)
                        }
                    }
                    val mx = (ev.getX(0) + ev.getX(1)) / 2f
                    val my = (ev.getY(0) + ev.getY(1)) / 2f
                    val wpp = AppModel.camera.worldPerPixel(height)
                    synchronized(AppModel.lock) {
                        AppModel.camera.pan((panLastX - mx) * wpp, (my - panLastY) * wpp)
                    }
                    panLastX = mx; panLastY = my
                } else {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    handleDrag(dx, dy)
                    lastX = ev.x; lastY = ev.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pointers = (pointers - 1).coerceAtLeast(0)
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > 12.0
                val dt = System.currentTimeMillis() - downTime
                pointers = 0
                if (!moved && dt < 350) pickAt(ev.x, ev.y)
            }
            MotionEvent.ACTION_CANCEL -> { pointers = 0 }
        }
        return true
    }

    private fun fingerDist(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun handleDrag(dx: Float, dy: Float) {
        val selId = AppModel.scene.selected
        synchronized(AppModel.lock) {
            when (mode) {
                MODE_ORBIT -> AppModel.camera.orbit(dx * 0.35f, dy * 0.35f)
                MODE_MOVE -> {
                    val obj = AppModel.scene.byId(selId) ?: return@synchronized
                    val wpp = AppModel.camera.worldPerPixel(height)
                    val ax = FloatArray(6)
                    AppModel.camera.screenAxes(ax)
                    obj.position[0] += (ax[0] * dx - ax[3] * dy) * wpp
                    obj.position[1] += (ax[1] * dx - ax[4] * dy) * wpp
                    obj.position[2] += (ax[2] * dx - ax[5] * dy) * wpp
                }
                MODE_ROTATE -> {
                    val obj = AppModel.scene.byId(selId) ?: return@synchronized
                    obj.rotation[1] = (obj.rotation[1] + dx * 0.5f) % 360f
                    obj.rotation[0] = (obj.rotation[0] + dy * 0.5f) % 360f
                }
                MODE_SCALE -> {
                    val obj = AppModel.scene.byId(selId) ?: return@synchronized
                    val f = (1f - dy * 0.004f).coerceIn(0.5f, 2f)
                    for (i in 0..2) obj.scale[i] = (obj.scale[i] * f).coerceIn(0.02f, 50f)
                }
            }
        }
    }

    private fun pickAt(px: Float, py: Float) {
        val origin = FloatArray(3)
        val dir = FloatArray(3)
        if (!renderer.pickRay(px, py, origin, dir)) return
        var bestId: String? = null
        var bestT = Float.MAX_VALUE
        val b = FloatArray(6)
        val model = FloatArray(16)
        val a = FloatArray(3); val bb = FloatArray(3); val c = FloatArray(3)
        synchronized(AppModel.lock) {
            for (i in AppModel.scene.objects.indices.reversed()) {
                val obj: SceneObject = AppModel.scene.objects[i]
                if (!obj.visible) continue
                obj.worldBounds(b)
                if (!Math3D.rayAabb(origin, dir, b, b.copyOfRange(3, 6))) continue
                // test de triángulos en mundo
                obj.modelMatrix(model)
                val mesh: Mesh = obj.mesh
                var t = 0
                while (t + 9 <= mesh.used) {
                    // transforma los 3 vértices a mundo
                    for (v in 0..2) {
                        val vx = mesh.pos[t + v * 3]
                        val vy = mesh.pos[t + v * 3 + 1]
                        val vz = mesh.pos[t + v * 3 + 2]
                        val wx = model[0] * vx + model[4] * vy + model[8] * vz + model[12]
                        val wy = model[1] * vx + model[5] * vy + model[9] * vz + model[13]
                        val wz = model[2] * vx + model[6] * vy + model[10] * vz + model[14]
                        if (v == 0) { a[0] = wx; a[1] = wy; a[2] = wz }
                        else if (v == 1) { bb[0] = wx; bb[1] = wy; bb[2] = wz }
                        else { c[0] = wx; c[1] = wy; c[2] = wz }
                    }
                    val hit = Math3D.rayTriangle(origin, dir, a, bb, c)
                    if (hit != null && hit < bestT) {
                        bestT = hit
                        bestId = obj.id
                    }
                    t += 9
                }
            }
            AppModel.scene.selected = bestId
        }
        onSelectChanged?.invoke()
    }
}
