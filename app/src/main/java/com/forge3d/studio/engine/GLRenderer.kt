package com.forge3d.studio.engine

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.forge3d.studio.mcp.AppModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Renderizador GLES20 original: sombreado tipo PBR, rejilla y ejes. */
class GLRenderer : GLSurfaceView.Renderer {

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val invVp = FloatArray(16)

    private var progObj = 0
    private var progLine = 0

    private var uMvpObj = 0
    private var uModelObj = 0
    private var uEyeObj = 0
    private var uColorObj = 0
    private var uMetalObj = 0
    private var uRoughObj = 0
    private var uEmisObj = 0
    private var uAlphaModeObj = 0
    private var uCutObj = 0
    private var aPosObj = 0
    private var aNormObj = 0

    private var uMvpLine = 0
    private var uColorLine = 0
    private var aPosLine = 0

    private var gridBuf = 0
    private var gridCount = 0
    private var axisBuf = 0

    var viewportW = 1
        private set
    var viewportH = 1
        private set

    private val vsObj = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec4 aPos;
        attribute vec3 aNorm;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);
            vN = nm * aNorm;
            vec4 wp = uModel * aPos;
            vW = wp.xyz;
            gl_Position = uMVP * aPos;
        }
    """.trimIndent()

    private val fsObj = """
        precision mediump float;
        uniform vec3 uEye;
        uniform vec4 uColor;
        uniform float uMetal;
        uniform float uRough;
        uniform vec3 uEmissive;
        uniform float uAlphaMode;
        uniform float uCut;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            if (uAlphaMode > 1.5 && uColor.a < uCut) discard;
            vec3 N = normalize(vN);
            vec3 L1 = normalize(vec3(0.5, 0.9, 0.4));
            vec3 L2 = normalize(vec3(-0.6, 0.3, -0.5));
            float diff = max(dot(N, L1), 0.0) * 0.72 + max(dot(N, L2), 0.0) * 0.30 + 0.24;
            vec3 V = normalize(uEye - vW);
            vec3 H = normalize(L1 + V);
            float shin = mix(64.0, 6.0, clamp(uRough, 0.0, 1.0));
            float spec = pow(max(dot(N, H), 0.0), shin) * mix(0.25, 0.9, uMetal) * (1.0 - uRough * 0.55);
            vec3 col = uColor.rgb * diff + vec3(spec) + uEmissive;
            float a = uColor.a;
            if (uAlphaMode > 0.5 && uAlphaMode < 1.5) a = a;
            gl_FragColor = vec4(col, a);
        }
    """.trimIndent()

    private val vsLine = """
        uniform mat4 uMVP;
        attribute vec4 aPos;
        void main() { gl_Position = uMVP * aPos; }
    """.trimIndent()

    private val fsLine = """
        precision mediump float;
        uniform vec4 uColor;
        void main() { gl_FragColor = uColor; }
    """.trimIndent()

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val st = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw IllegalStateException("Error compilando shader: $log")
        }
        return sh
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            throw IllegalStateException("Error enlazando programa: $log")
        }
        return p
    }

    private fun buildGrid() {
        val v = ArrayList<Float>(4096)
        val ext = 10
        for (i in -ext..ext) {
            v.add(i.toFloat()); v.add(0f); v.add(-ext.toFloat())
            v.add(i.toFloat()); v.add(0f); v.add(ext.toFloat())
            v.add(-ext.toFloat()); v.add(0f); v.add(i.toFloat())
            v.add(ext.toFloat()); v.add(0f); v.add(i.toFloat())
        }
        val fa = FloatArray(v.size)
        for (i in v.indices) fa[i] = v[i]
        gridCount = v.size / 3
        val bb = ByteBuffer.allocateDirect(fa.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(fa)
        fb.position(0)
        val buf = IntArray(1)
        GLES20.glGenBuffers(1, buf, 0)
        gridBuf = buf[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuf)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, fa.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val ax = floatArrayOf(
            -ext.toFloat(), 0.002f, 0f, ext.toFloat(), 0.002f, 0f,
            0f, 0.002f, -ext.toFloat(), 0f, 0.002f, ext.toFloat()
        )
        val ab = ByteBuffer.allocateDirect(ax.size * 4).order(ByteOrder.nativeOrder())
        val afb = ab.asFloatBuffer()
        afb.put(ax)
        afb.position(0)
        val b2 = IntArray(1)
        GLES20.glGenBuffers(1, b2, 0)
        axisBuf = b2[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, axisBuf)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, ax.size * 4, afb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.063f, 0.078f, 0.094f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        progObj = link(vsObj, fsObj)
        progLine = link(vsLine, fsLine)
        uMvpObj = GLES20.glGetUniformLocation(progObj, "uMVP")
        uModelObj = GLES20.glGetUniformLocation(progObj, "uModel")
        uEyeObj = GLES20.glGetUniformLocation(progObj, "uEye")
        uColorObj = GLES20.glGetUniformLocation(progObj, "uColor")
        uMetalObj = GLES20.glGetUniformLocation(progObj, "uMetal")
        uRoughObj = GLES20.glGetUniformLocation(progObj, "uRough")
        uEmisObj = GLES20.glGetUniformLocation(progObj, "uEmissive")
        uAlphaModeObj = GLES20.glGetUniformLocation(progObj, "uAlphaMode")
        uCutObj = GLES20.glGetUniformLocation(progObj, "uCut")
        aPosObj = GLES20.glGetAttribLocation(progObj, "aPos")
        aNormObj = GLES20.glGetAttribLocation(progObj, "aNorm")
        uMvpLine = GLES20.glGetUniformLocation(progLine, "uMVP")
        uColorLine = GLES20.glGetUniformLocation(progLine, "uColor")
        aPosLine = GLES20.glGetAttribLocation(progLine, "aPos")

        buildGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width
        viewportH = if (height < 1) 1 else height
        GLES20.glViewport(0, 0, width, viewportH)
        synchronized(AppModel.lock) {
            AppModel.camera.proj(proj, width, viewportH)
        }
    }

    private fun uploadMesh(mesh: Mesh) {
        val vertN = mesh.vertCount
        if (vertN == 0) return
        val bb = ByteBuffer.allocateDirect(vertN * 24).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        var i = 0
        while (i < mesh.used) {
            fb.put(mesh.pos[i]); fb.put(mesh.pos[i + 1]); fb.put(mesh.pos[i + 2])
            fb.put(mesh.nrm[i]); fb.put(mesh.nrm[i + 1]); fb.put(mesh.nrm[i + 2])
            i += 3
        }
        fb.position(0)
        if (mesh.glBuffer == 0) {
            val buf = IntArray(1)
            GLES20.glGenBuffers(1, buf, 0)
            mesh.glBuffer = buf[0]
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertN * 24, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        mesh.needsUpload = false
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // liberar mallas eliminadas
        while (true) {
            val m = AppModel.meshDeleteQueue.poll() ?: break
            if (m.glBuffer != 0) {
                val b = IntArray(1)
                b[0] = m.glBuffer
                GLES20.glDeleteBuffers(1, b, 0)
                m.glBuffer = 0
            }
        }

        synchronized(AppModel.lock) {
            val cam = AppModel.camera
            cam.update()
            cam.view(view)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.invertM(invVp, 0, vp, 0)

            // rejilla
            GLES20.glUseProgram(progLine)
            GLES20.glUniformMatrix4fv(uMvpLine, 1, false, vp, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuf)
            GLES20.glEnableVertexAttribArray(aPosLine)
            GLES20.glVertexAttribPointer(aPosLine, 3, GLES20.GL_FLOAT, false, 12, 0)
            GLES20.glUniform4f(uColorLine, 0.32f, 0.36f, 0.42f, 1f)
            GLES20.glLineWidth(1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridCount)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, axisBuf)
            GLES20.glVertexAttribPointer(aPosLine, 3, GLES20.GL_FLOAT, false, 12, 0)
            GLES20.glUniform4f(uColorLine, 0.85f, 0.32f, 0.25f, 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
            GLES20.glUniform4f(uColorLine, 0.25f, 0.5f, 0.9f, 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 2, 2)
            GLES20.glDisableVertexAttribArray(aPosLine)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            // objetos
            GLES20.glUseProgram(progObj)
            GLES20.glUniformMatrix4fv(uMvpObj, 1, false, vp, 0)
            GLES20.glUniform3f(uEyeObj, cam.eye[0], cam.eye[1], cam.eye[2])
            val sel = AppModel.scene.selected
            for (obj in AppModel.scene.objects) {
                if (!obj.visible) continue
                val mesh = obj.mesh
                if (mesh.needsUpload || mesh.glBuffer == 0) uploadMesh(mesh)
                if (mesh.glBuffer == 0 || mesh.vertCount == 0) continue

                obj.modelMatrix(model)
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                GLES20.glUniformMatrix4fv(uMvpObj, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(uModelObj, 1, false, model, 0)

                val c = obj.material.baseColor
                val em = obj.material.emissive
                val selected = sel != null && obj.id == sel
                GLES20.glUniform4f(uColorObj, c[0], c[1], c[2], c[3])
                GLES20.glUniform1f(uMetalObj, obj.material.metallic)
                GLES20.glUniform1f(uRoughObj, obj.material.roughness)
                if (selected) {
                    GLES20.glUniform3f(uEmisObj, em[0] + 0.20f, em[1] + 0.10f, em[2] + 0.02f)
                } else {
                    GLES20.glUniform3f(uEmisObj, em[0], em[1], em[2])
                }
                val mode = when (obj.material.alphaMode) {
                    "BLEND" -> 1f
                    "MASK" -> 2f
                    else -> 0f
                }
                GLES20.glUniform1f(uAlphaModeObj, mode)
                GLES20.glUniform1f(uCutObj, obj.material.alphaCutoff)

                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer)
                GLES20.glEnableVertexAttribArray(aPosObj)
                GLES20.glVertexAttribPointer(aPosObj, 3, GLES20.GL_FLOAT, false, 24, 0)
                GLES20.glEnableVertexAttribArray(aNormObj)
                GLES20.glVertexAttribPointer(aNormObj, 3, GLES20.GL_FLOAT, false, 24, 12)

                if (mode == 1f || c[3] < 0.999f) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glDepthMask(false)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertCount)
                    GLES20.glDepthMask(true)
                    GLES20.glDisable(GLES20.GL_BLEND)
                } else {
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertCount)
                }
                GLES20.glDisableVertexAttribArray(aPosObj)
                GLES20.glDisableVertexAttribArray(aNormObj)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            }
        }
    }

    /** Rayo de picking desde un punto de pantalla. Llamar desde cualquier hilo. */
    fun pickRay(px: Float, py: Float, outOrigin: FloatArray, outDir: FloatArray): Boolean {
        synchronized(AppModel.lock) {
            if (viewportW < 1 || viewportH < 1) return false
            AppModel.camera.update()
            AppModel.camera.view(view)
            AppModel.camera.proj(proj, viewportW, viewportH)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.invertM(invVp, 0, vp, 0)

            val nx = 2f * px / viewportW - 1f
            val ny = 1f - 2f * py / viewportH
            val near4 = FloatArray(4)
            val far4 = FloatArray(4)
            transform(invVp, nx, ny, -1f, near4)
            transform(invVp, nx, ny, 1f, far4)
            if (near4[3] == 0f || far4[3] == 0f) return false
            val ax = near4[0] / near4[3]; val ay = near4[1] / near4[3]; val az = near4[2] / near4[3]
            val bx = far4[0] / far4[3]; val by = far4[1] / far4[3]; val bz = far4[2] / far4[3]
            val cam = AppModel.camera
            outOrigin[0] = cam.eye[0]; outOrigin[1] = cam.eye[1]; outOrigin[2] = cam.eye[2]
            outDir[0] = bx - ax; outDir[1] = by - ay; outDir[2] = bz - az
            Math3D.normalize(outDir)
            return true
        }
    }

    private fun transform(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15]
    }
}
