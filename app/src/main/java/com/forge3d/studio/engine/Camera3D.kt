package com.forge3d.studio.engine


import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Cámara orbital (objetivo, yaw, pitch, distancia) en metros. */
class Camera3D {

    val target = floatArrayOf(0f, 0f, 0f)
    var yaw = 45f     // grados alrededor de Y
    var pitch = 28f   // grados, -89..89
    var distance = 6f

    val eye = floatArrayOf(0f, 0f, 0f)

    fun update() {
        val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
        val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
        val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
        val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
        eye[0] = target[0] + distance * cp * sy
        eye[1] = target[1] + distance * sp
        eye[2] = target[2] + distance * cp * cy
    }

    fun view(m: FloatArray) {
        Mat4.lookAt(m, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 1f, 0f)
    }

    fun proj(m: FloatArray, width: Int, height: Int) {
        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
        Mat4.perspective(m, 0, 50f, aspect, 0.05f, 1000f)
    }

    fun orbit(dyaw: Float, dpitch: Float) {
        yaw += dyaw
        pitch = (pitch + dpitch).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        if (factor > 0f) distance = (distance * factor).coerceIn(0.05f, 500f)
    }

    /** Ejes de pantalla en mundo: right (0), up (1). [out] debe ser 6 floats. */
    fun screenAxes(out: FloatArray) {
        val f = floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
        Math3D.normalize(f)
        val up0 = floatArrayOf(0f, 1f, 0f)
        val r = FloatArray(3)
        Math3D.cross(f[0], f[1], f[2], up0[0], up0[1], up0[2], r)
        val rl = Math3D.length(r[0], r[1], r[2])
        if (rl < 1e-6f) { r[0] = 1f; r[1] = 0f; r[2] = 0f } else { r[0] /= rl; r[1] /= rl; r[2] /= rl }
        val u = FloatArray(3)
        Math3D.cross(r[0], r[1], r[2], f[0], f[1], f[2], u)
        out[0] = r[0]; out[1] = r[1]; out[2] = r[2]
        out[3] = u[0]; out[4] = u[1]; out[5] = u[2]
    }

    /** Desplazar objetivo en el plano de pantalla (metros). */
    fun pan(dx: Float, dy: Float) {
        val ax = FloatArray(6)
        screenAxes(ax)
        target[0] += ax[0] * dx + ax[3] * dy
        target[1] += ax[1] * dx + ax[4] * dy
        target[2] += ax[2] * dx + ax[5] * dy
    }

    /** Encuadrar una AABB mundo (min/max de 6 floats). */
    fun fit(b: FloatArray) {
        val cx = (b[0] + b[3]) / 2f
        val cy = (b[1] + b[4]) / 2f
        val cz = (b[2] + b[5]) / 2f
        val radius = Math3D.length(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 2f
        target[0] = cx; target[1] = cy; target[2] = cz
        distance = (radius * 2.4f + 0.4f).coerceIn(0.4f, 500f)
    }

    /** Metros por píxel a la distancia actual (para mover objetos con el dedo). */
    fun worldPerPixel(heightPx: Int): Float {
        val h = if (heightPx < 1) 1 else heightPx
        return 2f * distance * tan(Math.toRadians(25.0)).toFloat() / h
    }
}
