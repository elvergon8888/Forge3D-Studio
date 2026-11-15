package com.forge3d.studio.engine

import android.opengl.GLES20
import android.opengl.GLSurfaceView

import com.forge3d.studio.mcp.AppModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renderizador GLES20 original: sombreado tipo PBR, rejilla y ejes.
 *
 * Implementa [MemoryManager.GlResourceOwner] para poder liberar los VBOs y bajar la resolución
 * de render cuando el sistema avisa de presión de memoria.
 */
class GLRenderer : GLSurfaceView.Renderer, MemoryManager.GlResourceOwner {

    /**
     * Escala de render: 1.0 = resolución completa, 0.5 = mitad de píxeles.
     * Se reduce automáticamente bajo presión de memoria.
     */
    @Volatile private var renderScale = 1.0f

    /** Calidad gráfica vigente (afecta a la resolución de render). */
    @Volatile private var quality = DeviceCapabilities.Quality.MEDIUM

    /** Buffer pendiente de liberar (lo consume el hilo GL). */
    private val pendingDeletes = ArrayList<Int>()

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
    private var aUvObj = 0

    // Uniforms de mapas PBR
    private var uHasAlbedoObj = 0
    private var uHasRoughObj = 0
    private var uHasMetalObj = 0
    private var uHasAObj = 0
    private var uAlbedoMapObj = 0
    private var uRoughMapObj = 0
    private var uMetalMapObj = 0
    private var uAOMapObj = 0

    /**
     * Texturas GL cacheadas por material.
     * Se liberan cuando el material cambia o cuando el contexto GL se recrea.
     */
    private val textureCache = HashMap<String, TextureSet>()

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
        attribute vec2 aUv;
        varying vec3 vN;
        varying vec3 vW;
        varying vec2 vUv;
        void main() {
            mat3 nm = mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz);
            vN = nm * aNorm;
            vec4 wp = uModel * aPos;
            vW = wp.xyz;
            vUv = aUv;
            gl_Position = uMVP * aPos;
        }
    """.trimIndent()

    /**
     * Fragment shader PBR (BRDF GGX + Smith + Fresnel Schlick) con mapas opcionales de
     * albedo, rugosidad, metalicidad y oclusión ambiental.
     *
     * Los mapas se activan con uniforms `uHas*` (0/1) para no depender de que haya una
     * textura bindeada: en GLES2, muestrear sin textura devuelve (0,0,0,1) en algunos drivers,
     * lo que ennegrecía el objeto.
     */
    private val fsObj = """
        precision mediump float;
        uniform vec3 uEye;
        uniform vec4 uColor;
        uniform float uMetal;
        uniform float uRough;
        uniform vec3 uEmissive;
        uniform float uAlphaMode;
        uniform float uCut;
        uniform float uHasAlbedo;
        uniform float uHasRough;
        uniform float uHasMetal;
        uniform float uHasAO;
        uniform sampler2D uAlbedoMap;
        uniform sampler2D uRoughMap;
        uniform sampler2D uMetalMap;
        uniform sampler2D uAOMap;
        varying vec3 vN;
        varying vec3 vW;
        varying vec2 vUv;

        const float PI = 3.14159265;

        float distributionGGX(vec3 N, vec3 H, float rough) {
            float a = rough * rough;
            float a2 = a * a;
            float NdotH = max(dot(N, H), 0.0);
            float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
            return a2 / max(PI * d * d, 0.0001);
        }

        float geometrySchlickGGX(float NdotV, float rough) {
            float r = rough + 1.0;
            float k = (r * r) / 8.0;
            return NdotV / max(NdotV * (1.0 - k) + k, 0.0001);
        }

        float geometrySmith(vec3 N, vec3 V, vec3 L, float rough) {
            return geometrySchlickGGX(max(dot(N, V), 0.0), rough) *
                   geometrySchlickGGX(max(dot(N, L), 0.0), rough);
        }

        vec3 fresnelSchlick(float cosTheta, vec3 F0) {
            return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
        }

        vec3 brdf(vec3 N, vec3 V, vec3 L, vec3 albedo, float metal, float rough, vec3 F0) {
            vec3 H = normalize(V + L);
            float NDF = distributionGGX(N, H, rough);
            float G = geometrySmith(N, V, L, rough);
            vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
            vec3 spec = (NDF * G * F) / max(4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0), 0.0001);
            vec3 kD = (vec3(1.0) - F) * (1.0 - metal);
            return (kD * albedo / PI + spec) * max(dot(N, L), 0.0);
        }

        void main() {
            if (uAlphaMode > 1.5 && uColor.a < uCut) discard;

            vec3 N = normalize(vN);
            vec3 V = normalize(uEye - vW);
            vec3 L1 = normalize(vec3(0.5, 0.9, 0.4));
            vec3 L2 = normalize(vec3(-0.6, 0.3, -0.5));

            vec4 baseTex = uHasAlbedo > 0.5 ? texture2D(uAlbedoMap, vUv) : vec4(1.0);
            vec3 albedo = uColor.rgb * baseTex.rgb;

            float rough = uRough;
            if (uHasRough > 0.5) rough = clamp(texture2D(uRoughMap, vUv).g, 0.03, 1.0);

            float metal = uMetal;
            if (uHasMetal > 0.5) metal = clamp(texture2D(uMetalMap, vUv).b, 0.0, 1.0);

            float ao = 1.0;
            if (uHasAO > 0.5) ao = clamp(texture2D(uAOMap, vUv).r, 0.0, 1.0);

            vec3 F0 = mix(vec3(0.04), albedo, metal);
            vec3 lit = brdf(N, V, L1, albedo, metal, rough, F0) * 3.2;
            lit += brdf(N, V, L2, albedo, metal, rough, F0) * 1.1;
            // Luz ambiente difusa con oclusión
            lit += albedo * 0.20 * ao;

            vec3 col = lit + uEmissive;
            float a = uColor.a * baseTex.a;
            if (uAlphaMode > 1.5) { if (a < uCut) discard; }
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

        // El contexto GL puede haberse recreado (pérdida de contexto por presión de memoria):
        // todos los VBOs anteriores son inválidos y hay que regenerarlos.
        contextLost = true

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
        aUvObj = GLES20.glGetAttribLocation(progObj, "aUv")
        uHasAlbedoObj = GLES20.glGetUniformLocation(progObj, "uHasAlbedo")
        uHasRoughObj = GLES20.glGetUniformLocation(progObj, "uHasRough")
        uHasMetalObj = GLES20.glGetUniformLocation(progObj, "uHasMetal")
        uHasAObj = GLES20.glGetUniformLocation(progObj, "uHasAO")
        uAlbedoMapObj = GLES20.glGetUniformLocation(progObj, "uAlbedoMap")
        uRoughMapObj = GLES20.glGetUniformLocation(progObj, "uRoughMap")
        uMetalMapObj = GLES20.glGetUniformLocation(progObj, "uMetalMap")
        uAOMapObj = GLES20.glGetUniformLocation(progObj, "uAOMap")
        // Las samplers se asignan a unidades de textura fijas
        GLES20.glUniform1i(uAlbedoMapObj, 0)
        GLES20.glUniform1i(uRoughMapObj, 1)
        GLES20.glUniform1i(uMetalMapObj, 2)
        GLES20.glUniform1i(uAOMapObj, 3)
        uMvpLine = GLES20.glGetUniformLocation(progLine, "uMVP")
        uColorLine = GLES20.glGetUniformLocation(progLine, "uColor")
        aPosLine = GLES20.glGetAttribLocation(progLine, "aPos")

        // Detección de capacidades reales de la GPU (formatos de textura, tamaño máximo).
        reportGlCapabilities()

        buildGrid()
    }

    /** `true` cuando el contexto GL se recreó y las mallas deben resubir su VBO. */
    @Volatile private var contextLost = false

    /**
     * Confirma al motor las capacidades reales de la GPU: ASTC, ETC2, S3TC y tamaño máximo
     * de textura. Se usa para elegir el formato de compresión y dimensionar las texturas.
     */
    private fun reportGlCapabilities() {
        try {
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val astc = ext.contains("GL_EXT_texture_compression_astc")
            val etc2 = ext.contains("GL_OES_compressed_ETC2_RGB8")
            val s3tc = ext.contains("GL_EXT_texture_compression_s3tc")
            val maxTex = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            DeviceCapabilities.reportGlSupport(false, astc, etc2, s3tc, if (maxTex[0] > 0) maxTex[0] else 2048)
        } catch (e: Throwable) {
            // Si la consulta falla, se mantienen las heurísticas por defecto.
            DeviceCapabilities.reportGlSupport(false, false, true, false, 2048)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width
        viewportH = if (height < 1) 1 else height
        GLES20.glViewport(0, 0, width, viewportH)
        synchronized(AppModel.lock) {
            AppModel.camera.proj(proj, width, viewportH)
        }
    }

    // ---------------- MemoryManager.GlResourceOwner ----------------

    /**
     * Libera todos los buffers GPU. Se invoca desde el hilo de UI (trim de memoria), por lo que
     * el borrado real se hace en el hilo GL en el siguiente frame.
     */
    override fun releaseGlResources() {
        contextLost = true
        // Las texturas también son(context-dependent): se liberan y se recargan.
        for (t in textureCache.values) t.release()
        textureCache.clear()
        synchronized(AppModel.lock) {
            for (o in AppModel.scene.objects) {
                o.mesh.glBuffer = 0
                o.mesh.needsUpload = true
            }
        }
    }

    /**
     * Reduce la resolución de render. El renderer solo guarda el valor: el redimensionado real
     * de la superficie lo aplica [ViewportView] desde el hilo de UI (`setFixedSize`).
     */
    override fun setRenderScale(scale: Float) {
        renderScale = scale.coerceIn(0.25f, 1.0f)
    }

    /** Aplica la calidad gráfica vigente. */
    override fun applyQuality(q: DeviceCapabilities.Quality) {
        quality = q
        setRenderScale(
            when (q) {
                DeviceCapabilities.Quality.LOW -> 0.6f
                DeviceCapabilities.Quality.MEDIUM -> 0.85f
                DeviceCapabilities.Quality.HIGH -> 1.0f
            }
        )
    }

    /** Escala de render actual. */
    fun renderScale(): Float = renderScale

    /**
     * Devuelve (y cachea) el conjunto de texturas de un objeto.
     *
     * La clave de caché combina las rutas de los mapas, así que si el agente cambia la textura
     * del material, se recarga. La carga respeta el presupuesto de VRAM: si se supera, se
     * liberan las texturas cacheadas no usadas antes de cargar una nueva.
     */
    private fun texturesFor(obj: SceneObject): TextureSet {
        val m = obj.material
        if (!m.hasTextures) return EMPTY_TEXTURES
        val key = "${m.albedoPath}|${m.normalPath}|${m.roughnessPath}|${m.metalnessPath}|${m.aoPath}"
        textureCache[key]?.let { return it }

        // Presupuesto de VRAM: si no cabe, libera lo que no se esté usando.
        val budget = MemoryManager.capabilities()?.textureBudget ?: (64L * 1024 * 1024)
        var used = 0L
        for (t in textureCache.values) used += t.vramBytes()
        if (used > budget) {
            val it = textureCache.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.hasAny) {
                    e.value.release()
                    it.remove()
                }
                if (used <= budget) break
            }
        }

        val maxDim = MemoryManager.capabilities()?.maxTextureDimension(MemoryManager.quality())
            ?: TextureSet.DEFAULT_MAX_SIZE
        val set = TextureSet()
        set.albedoId = m.albedoPath?.let { set.load(it, maxDim) } ?: 0
        set.roughnessId = m.roughnessPath?.let { set.load(it, maxDim, true) } ?: 0
        set.metalnessId = m.metalnessPath?.let { set.load(it, maxDim, true) } ?: 0
        set.aoId = m.aoPath?.let { set.load(it, maxDim, true) } ?: 0
        textureCache[key] = set
        return set
    }

    /** Binda una textura en una unidad; si el id es 0, bindea la unidad 0 con textura nula. */
    private fun bindTexture(unit: Int, id: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    }

    companion object {
        /** Conjunto vacío para materiales sin texturas. */
        private val EMPTY_TEXTURES = TextureSet()
    }

    /**
     * Sube la malla al VBO.
     *
     * Layout interleaved: `pos3 + nrm3 + uv2` = 32 bytes por vértice.
     * Los offsets son 0 (posición), 12 (normal) y 24 (UV).
     */
    private fun uploadMesh(mesh: Mesh) {
        val vertN = mesh.vertCount
        if (vertN == 0) return
        val stride = 32
        val bb = ByteBuffer.allocateDirect(vertN * stride).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val hasUv = mesh.hasUv
        var i = 0
        var v = 0
        while (i < mesh.used) {
            fb.put(mesh.pos[i]); fb.put(mesh.pos[i + 1]); fb.put(mesh.pos[i + 2])
            fb.put(mesh.nrm[i]); fb.put(mesh.nrm[i + 1]); fb.put(mesh.nrm[i + 2])
            if (hasUv) {
                fb.put(mesh.uv[v * 2]); fb.put(mesh.uv[v * 2 + 1])
            } else {
                fb.put(0f); fb.put(0f)
            }
            i += 3
            v++
        }
        fb.position(0)
        if (mesh.glBuffer == 0) {
            val buf = IntArray(1)
            GLES20.glGenBuffers(1, buf, 0)
            mesh.glBuffer = buf[0]
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertN * stride, fb, GLES20.GL_STATIC_DRAW)
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
            Mat4.multiply(vp, 0, proj, 0, view, 0)
            Mat4.invert(vp, 0, invVp, 0)

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
                Mat4.multiply(mvp, 0, vp, 0, model, 0)
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

                // Mapas de textura: se cargan/cachean por material y se bindean por unidad.
                val tex = texturesFor(obj)
                GLES20.glUniform1f(uHasAlbedoObj, if (tex.albedoId != 0) 1f else 0f)
                GLES20.glUniform1f(uHasRoughObj, if (tex.roughnessId != 0) 1f else 0f)
                GLES20.glUniform1f(uHasMetalObj, if (tex.metalnessId != 0) 1f else 0f)
                GLES20.glUniform1f(uHasAObj, if (tex.aoId != 0) 1f else 0f)
                bindTexture(0, tex.albedoId)
                bindTexture(1, tex.roughnessId)
                bindTexture(2, tex.metalnessId)
                bindTexture(3, tex.aoId)

                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.glBuffer)
                GLES20.glEnableVertexAttribArray(aPosObj)
                GLES20.glVertexAttribPointer(aPosObj, 3, GLES20.GL_FLOAT, false, 32, 0)
                GLES20.glEnableVertexAttribArray(aNormObj)
                GLES20.glVertexAttribPointer(aNormObj, 3, GLES20.GL_FLOAT, false, 32, 12)
                if (aUvObj >= 0) {
                    GLES20.glEnableVertexAttribArray(aUvObj)
                    GLES20.glVertexAttribPointer(aUvObj, 2, GLES20.GL_FLOAT, false, 32, 24)
                }

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
                if (aUvObj >= 0) GLES20.glDisableVertexAttribArray(aUvObj)
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
            Mat4.multiply(vp, 0, proj, 0, view, 0)
            Mat4.invert(vp, 0, invVp, 0)

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
