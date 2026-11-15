package com.forge3d.studio.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.forge3d.studio.core.scene.Mesh
import com.forge3d.studio.core.scene.SceneObject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderizador GLES2.0 con shaders PBR (BRDF GGX + Smith + Fresnel Schlick)
 * y soporte de mapas de textura: albedo, normal, roughness, metalness, AO.
 */
class GLRenderer : GLSurfaceView.Renderer {

    private val proj = FloatArray(16); val view = FloatArray(16); val vp = FloatArray(16)
    private val model = FloatArray(16); val mvp = FloatArray(16); val invVp = FloatArray(16)

    private var progObj = 0; private var progLine = 0
    private var uMvpObj = 0; private var uModelObj = 0; private var uEyeObj = 0
    private var uColorObj = 0; private var uMetalObj = 0; private var uRoughObj = 0
    private var uEmisObj = 0; private var uAlphaModeObj = 0; private var uCutObj = 0
    private var uHasAlbedoObj = 0; private var uHasRoughObj = 0; private var uHasMetalObj = 0
    private var uHasAObj = 0; private var uAlbedoMapObj = 0; private var uRoughMapObj = 0
    private var uMetalMapObj = 0; private var uAOMapObj = 0
    private var aPosObj = 0; private var aNormObj = 0; private var aUvObj = 0

    @Volatile private var renderScale = 1.0f
    private val textureCache = HashMap<String, TextureSet>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.063f, 0.078f, 0.094f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        progObj = link(vsObj, fsObj); progLine = link(vsLine, fsLine)
        uMvpObj = GLES20.glGetUniformLocation(progObj, "uMVP"); uModelObj = GLES20.glGetUniformLocation(progObj, "uModel")
        uEyeObj = GLES20.glGetUniformLocation(progObj, "uEye"); uColorObj = GLES20.glGetUniformLocation(progObj, "uColor")
        uMetalObj = GLES20.glGetUniformLocation(progObj, "uMetal"); uRoughObj = GLES20.glGetUniformLocation(progObj, "uRough")
        uEmisObj = GLES20.glGetUniformLocation(progObj, "uEmissive"); uAlphaModeObj = GLES20.glGetUniformLocation(progObj, "uAlphaMode")
        uCutObj = GLES20.glGetUniformLocation(progObj, "uCut")
        uHasAlbedoObj = GLES20.glGetUniformLocation(progObj, "uHasAlbedo"); uHasRoughObj = GLES20.glGetUniformLocation(progObj, "uHasRough")
        uHasMetalObj = GLES20.glGetUniformLocation(progObj, "uHasMetal"); uHasAObj = GLES20.glGetUniformLocation(progObj, "uHasAO")
        uAlbedoMapObj = GLES20.glGetUniformLocation(progObj, "uAlbedoMap"); uRoughMapObj = GLES20.glGetUniformLocation(progObj, "uRoughMap")
        uMetalMapObj = GLES20.glGetUniformLocation(progObj, "uMetalMap"); uAOMapObj = GLES20.glGetUniformLocation(progObj, "uAOMap")
        aPosObj = GLES20.glGetAttribLocation(progObj, "aPos"); aNormObj = GLES20.glGetAttribLocation(progObj, "aNorm"); aUvObj = GLES20.glGetAttribLocation(progObj, "aUv")

        GLES20.glUniform1i(uAlbedoMapObj, 0); GLES20.glUniform1i(uRoughMapObj, 1)
        GLES20.glUniform1i(uMetalMapObj, 2); GLES20.glUniform1i(uAOMapObj, 3)
        reportGlCapabilities(); buildGrid()
    }

    private fun reportGlCapabilities() {
        try {
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val astc = ext.contains("GL_EXT_texture_compression_astc")
            val etc2 = ext.contains("GL_OES_compressed_ETC2_RGB8")
            val s3tc = ext.contains("GL_EXT_texture_compression_s3tc")
            val maxTex = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            TextureSet.reportGlSupport(astc, etc2, s3tc, if (maxTex[0] > 0) maxTex[0] else 2048)
        } catch (_: Throwable) { TextureSet.reportGlSupport(false, true, false, 2048) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val sw = (width * renderScale).toInt().coerceAtLeast(1)
        val sh = (height * renderScale).toInt().coerceAtLeast(1)
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glUniformMatrix4fv(uMvpObj, 1, false, vp, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(progLine)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuf)
        GLES20.glEnableVertexAttribArray(aPosLine)
        GLES20.glVertexAttribPointer(aPosLine, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glUniformMatrix4fv(uMvpLine, 1, false, vp, 0)
        GLES20.glUniform4f(uColorLine, 0.32f, 0.36f, 0.42f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridCount)

        GLES20.glUseProgram(progObj)
        GLES20.glUniformMatrix4fv(uMvpObj, 1, false, vp, 0)
        for (obj in scene.objects) {
            if (!obj.visible) continue
            val mesh = obj.mesh
            if (mesh.needsUpload || mesh.glBuffer == 0) uploadMesh(mesh)
            if (mesh.glBuffer == 0 || mesh.vertCount == 0) continue

            obj.modelMatrix(model); GLES20.glUniformMatrix4fv(uMvpObj, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModelObj, 1, false, model, 0)
            val c = obj.material.baseColor; val em = obj.material.emissive
            val mode = when (obj.material.alphaMode) { "BLEND" -> 1f; "MASK" -> 2f; else -> 0f }
            GLES20.glUniform4f(uColorObj, c[0], c[1], c[2], c[3])
            GLES20.glUniform1f(uMetalObj, obj.material.metallic); GLES20.glUniform1f(uRoughObj, obj.material.roughness)
            GLES20.glUniform3f(uEmisObj, em[0], em[1], em[2])
            GLES20.glUniform1f(uAlphaModeObj, mode); GLES20.glUniform1f(uCutObj, obj.material.alphaCutoff)

            val tex = texturesFor(obj)
            GLES20.glUniform1f(uHasAlbedoObj, if (tex.albedoId != 0) 1f else 0f)
            GLES20.glUniform1f(uHasRoughObj, if (tex.roughnessId != 0) 1f else 0f)
            GLES20.glUniform1f(uHasMetalObj, if (tex.metalnessId != 0) 1f else 0f)
            GLES20.glUniform1f(uHasAObj, if (tex.aoId != 0) 1f else 0f)
            bindTexture(0, tex.albedoId); bindTexture(1, tex.roughnessId); bindTexture(2, tex.metalnessId); bindTexture(3, tex.aoId)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer)
            GLES20.glEnableVertexAttribArray(aPosObj); GLES20.glVertexAttribPointer(aPosObj, 3, GLES20.GL_FLOAT, false, 32, 0)
            GLES20.glEnableVertexAttribArray(aNormObj); GLES20.glVertexAttribPointer(aNormObj, 3, GLES20.GL_FLOAT, false, 32, 12)
            if (aUvObj >= 0) { GLES20.glEnableVertexAttribArray(aUvObj); GLES20.glVertexAttribPointer(aUvObj, 2, GLES20.GL_FLOAT, false, 32, 24) }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertCount)
            GLES20.glDisableVertexAttribArray(aPosObj); GLES20.glDisableVertexAttribArray(aNormObj); if (aUvObj >= 0) GLES20.glDisableVertexAttribArray(aUvObj)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    // ---------- MemoryManager.GlResourceOwner ----------
    override fun releaseGlResources() {
        contextLost = true; for (t in textureCache.values) t.release(); textureCache.clear()
        for (o in scene.objects) { o.mesh.glBuffer = 0; o.mesh.needsUpload = true }
    }
    override fun setRenderScale(scale: Float) { renderScale = scale.coerceIn(0.25f, 1.0f) }
    override fun applyQuality(q: com.forge3d.studio.core.engine.DeviceCapabilities.Quality) {
        renderScale = when (q) { com.forge3d.studio.core.engine.DeviceCapabilities.Quality.LOW -> 0.6f; com.forge3d.studio.core.engine.DeviceCapabilities.Quality.MEDIUM -> 0.85f; com.forge3d.studio.core.engine.DeviceCapabilities.Quality.HIGH -> 1.0f }
    }
    fun renderScale(): Float = renderScale

    // ---------- internos ----------
    private var contextLost = false
    private val gridBuf = IntArray(1); private var gridCount = 0
    private var aPosLine = 0; private var uMvpLine = 0; private var uColorLine = 0
    private val scene = com.forge3d.studio.core.scene.Scene() // escena temporal para render standalone
    private val textureCache2 = HashMap<String, TextureSet>()

    private fun texturesFor(obj: com.forge3d.studio.core.scene.SceneObject): TextureSet {
        val m = obj.material; if (!m.hasTextures) return TextureSet.EMPTY
        val key = "${m.albedoPath}|${m.normalPath}|${m.roughnessPath}|${m.metalnessPath}|${m.aoPath}"
        textureCache[key]?.let { return it }
        val set = TextureSet()
        val maxDim = 1024
        set.albedoId = m.albedoPath?.let { set.load(it, maxDim) } ?: 0
        set.roughnessId = m.roughnessPath?.let { set.load(it, maxDim, true) } ?: 0
        set.metalnessId = m.metalnessPath?.let { set.load(it, maxDim, true) } ?: 0
        set.aoId = m.aoPath?.let { set.load(it, maxDim, true) } ?: 0
        textureCache[key] = set; return set
    }
    private fun bindTexture(unit: Int, id: Int) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id) }

    private fun uploadMesh(mesh: Mesh) {
        val vertN = mesh.vertCount; if (vertN == 0) return; val stride = 32
        val bb = java.nio.ByteBuffer.allocateDirect(vertN * stride).order(java.nio.ByteOrder.nativeOrder()); val fb = bb.asFloatBuffer()
        var i = 0; var v = 0; val hasUv = mesh.hasUv
        while (i < mesh.used) {
            fb.put(mesh.pos[i]); fb.put(mesh.pos[i + 1]); fb.put(mesh.pos[i + 2])
            fb.put(mesh.nrm[i]); fb.put(mesh.nrm[i + 1]); fb.put(mesh.nrm[i + 2])
            if (hasUv) { fb.put(mesh.uv[v * 2]); fb.put(mesh.uv[v * 2 + 1]) } else { fb.put(0f); fb.put(0f) }
            i += 3; v++
        }
        fb.position(0); if (mesh.glBuffer == 0) { val buf = IntArray(1); GLES20.glGenBuffers(1, buf, 0); mesh.glBuffer = buf[0] }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertN * stride, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); mesh.needsUpload = false
    }

    private fun buildGrid() {
        val lines = mutableListOf<Float>(); for (i in -10..10) { lines += i.toFloat(); lines += 0f; lines += -10f; lines += i.toFloat(); lines += 0f; lines += 10f; lines += -10f; lines += 0f; lines += i.toFloat(); lines += 10f; lines += 0f }
        gridCount = lines.size / 3; val buf = IntArray(1); GLES20.glGenBuffers(1, buf, 0); gridBuf[0] = buf[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuf[0]); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, lines.size * 4, lines.toFloatArray(), GLES20.GL_STATIC_DRAW); GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun link(vs: String, fs: String): Int {
        val vsId = compileShader(GLES20.GL_VERTEX_SHADER, vs); val fsId = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram(); GLES20.glAttachShader(prog, vsId); GLES20.glAttachShader(prog, fsId); GLES20.glLinkProgram(prog)
        val ok = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0); if (ok[0] == 0) throw RuntimeException("link falló: ${GLES20.glGetProgramInfoLog(prog)}")
        GLES20.glDeleteShader(vsId); GLES20.glDeleteShader(fsId); return prog
    }
    private fun compileShader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
        val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0); if (ok[0] == 0) throw RuntimeException("shader falló: ${GLES20.glGetShaderInfoLog(id)}")
        return id
    }

    private val vsObj = """
        uniform mat4 uMVP; uniform mat4 uModel; attribute vec4 aPos; attribute vec3 aNorm; attribute vec2 aUv; varying vec3 vN; varying vec3 vW; varying vec2 vUv; void main() { mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz); vN = nm * aNorm; vec4 wp = uModel * aPos; vW = wp.xyz; vUv = aUv; gl_Position = uMVP * aPos; }
    """.trimIndent()
    private val fsObj = """
        precision mediump float; uniform vec3 uEye; uniform vec4 uColor; uniform float uMetal; uniform float uRough; uniform vec3 uEmissive; uniform float uAlphaMode; uniform float uCut; uniform float uHasAlbedo; uniform float uHasRough; uniform float uHasMetal; uniform float uHasAO; uniform sampler2D uAlbedoMap; uniform sampler2D uRoughMap; uniform sampler2D uMetalMap; uniform sampler2D uAOMap; varying vec3 vN; varying vec3 vW; varying vec2 vUv; const float PI = 3.14159265; float distributionGGX(vec3 N, vec3 H, float rough) { float a = rough * rough; float a2 = a * a; float NdotH = max(dot(N, H), 0.0); float d = NdotH * NdotH * (a2 - 1.0) + 1.0; return a2 / max(PI * d * d, 0.0001); } float geometrySchlickGGX(float NdotV, float rough) { float r = rough + 1.0; float k = (r * r) / 8.0; return NdotV / max(NdotV * (1.0 - k) + k, 0.0001); } float geometrySmith(vec3 N, vec3 V, vec3 L, float rough) { return geometrySchlickGGX(max(dot(N, V), 0.0), rough) * geometrySchlickGGX(max(dot(N, L), 0.0), rough); } vec3 fresnelSchlick(float cosTheta, vec3 F0) { return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0); } vec3 brdf(vec3 N, vec3 V, vec3 L, vec3 albedo, float metal, float rough, vec3 F0) { vec3 H = normalize(V + L); float NDF = distributionGGX(N, H, rough); float G = geometrySmith(N, V, L, rough); vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0); vec3 spec = (NDF * G * F) / max(4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0), 0.0001); vec3 kD = (vec3(1.0) - F) * (1.0 - metal); return (kD * albedo / PI + spec) * max(dot(N, L), 0.0); } void main() { if (uAlphaMode > 1.5 && uColor.a < uCut) discard; vec3 N = normalize(vN); vec3 V = normalize(uEye - vW); vec3 L1 = normalize(vec3(0.5, 0.9, 0.4)); vec3 L2 = normalize(vec3(-0.6, 0.3, -0.5)); vec4 baseTex = uHasAlbedo > 0.5 ? texture2D(uAlbedoMap, vUv) : vec4(1.0); vec3 albedo = uColor.rgb * baseTex.rgb; float rough = uRough; if (uHasRough > 0.5) rough = clamp(texture2D(uRoughMap, vUv).g, 0.03, 1.0); float metal = uMetal; if (uHasMetal > 0.5) metal = clamp(texture2D(uMetalMap, vUv).b, 0.0, 1.0); float ao = 1.0; if (uHasAO > 0.5) ao = clamp(texture2D(uAOMap, vUv).r, 0.0, 1.0); vec3 F0 = mix(vec3(0.04), albedo, metal); vec3 lit = brdf(N, V, L1, albedo, metal, rough, F0) * 3.2; lit += brdf(N, V, L2, albedo, metal, rough, F0) * 1.1; lit += albedo * 0.20 * ao; vec3 col = lit + uEmissive; float a = uColor.a * baseTex.a; if (uAlphaMode > 1.5) { if (a < uCut) discard; } gl_FragColor = vec4(col, a); }
    """.trimIndent()
    private val vsLine = "uniform mat4 uMVP; attribute vec4 aPos; void main() { gl_Position = uMVP * aPos; }".trimIndent()
    private val fsLine = "precision mediump float; uniform vec4 uColor; void main() { gl_FragColor = uColor; }".trimIndent()
}
