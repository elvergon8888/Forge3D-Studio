package com.forge3d.studio.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log

/**
 * Detección de capacidades del dispositivo y presupuesto de memoria.
 *
 * Objetivo: que la app nunca sea terminada por el sistema (ni con stack trace ni sin él) y que
 * degrade la calidad gráfica en lugar de morir. En Android 17 (API 37) los límites por app son
 * más estrictos, así que el presupuesto se calcula de forma conservadora y se deja margen.
 *
 * @see MemoryManager para la parte reactiva (onTrimMemory, pools, degradación).
 */
object DeviceCapabilities {

    private const val TAG = "Forge3D/Capabilities"

    /** Nivel de calidad gráfica adaptativo. */
    enum class Quality {
        /** Sin texturas, resolución de render reducida, sombras off. Dispositivos de gama baja. */
        LOW,

        /** Texturas 1K, resolución completa. Por defecto. */
        MEDIUM,

        /** Texturas 2K, resolución completa, todas las texturas PBR. */
        HIGH
    }

    /** Formato de compresión de textura disponible en el dispositivo. */
    enum class TextureFormat(val glInternalFormat: Int, val glFormat: Int, val glType: Int, val bytesPerPixel: Float) {
        /** Sin compresión: RGBA8888. 4 bytes/px. */
        RGBA8888(0x8058, 0x1908, 0x1401, 4f),

        /** ASTC 4x4: ~0.5 bytes/px. Soportado por casi todos los GPUs Android modernos. */
        ASTC_4x4(0x93B4, 0x1908, 0x1401, 0.5f),

        /** ETC2 RGB8: 0.5 bytes/px. Fallback universal desde Android 10. */
        ETC2_RGB8(0x9274, 0x9274, 0x9274, 0.5f),

        /** S3TC/DXT1: 0.5 bytes/px. GPUs de escritorio y algunos Android. */
        S3TC_DXT1(0x83F1, 0x83F1, 0x83F1, 0.5f)
    }

    /** Resultado de la detección, cacheado por proceso. */
    data class Profile(
        val sdkInt: Int,
        val model: String,
        val totalRamBytes: Long,
        val lowRamDevice: Boolean,
        val heapLimitBytes: Long,
        val supportsVulkan: Boolean,
        val supportsAstc: Boolean,
        val supportsEtc2: Boolean,
        val supportsS3tc: Boolean,
        val maxTextureSize: Int,
        val initialQuality: Quality
    ) {
        /** Presupuesto de heap para la app: 55% del límite, con suelo y techo. */
        val appHeapBudget: Long
            get() = (heapLimitBytes * 55 / 100).coerceIn(24L * 1024 * 1024, 256L * 1024 * 1024)

        /** Presupuesto de VRAM para texturas: 25% del heap disponible. */
        val textureBudget: Long
            get() = (appHeapBudget * 25 / 100)

        /** Formato de textura elegido según capacidades del GPU. */
        val textureFormat: TextureFormat
            get() = when {
                supportsAstc -> TextureFormat.ASTC_4x4
                supportsEtc2 -> TextureFormat.ETC2_RGB8
                supportsS3tc -> TextureFormat.S3TC_DXT1
                else -> TextureFormat.RGBA8888
            }

        /**
         * Límite de lado de textura según RAM y calidad.
         * Una textura 4K RGBA8888 ocupa ~32 MB; con ASTC/ETC2 baja a ~4 MB.
         */
        fun maxTextureDimension(quality: Quality): Int = when (quality) {
            Quality.LOW -> 512
            Quality.MEDIUM -> if (totalRamBytes < 3L * 1024 * 1024 * 1024) 1024 else 2048
            Quality.HIGH -> if (totalRamBytes < 4L * 1024 * 1024 * 1024) 2048 else 4096
        }
    }

    @Volatile
    private var cached: Profile? = null

    /** Detecta (o devuelve de caché) el perfil de capacidades del dispositivo. */
    fun detect(ctx: Context): Profile {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = if (mi.totalMem > 0) mi.totalMem else Runtime.getRuntime().maxMemory()
            val heapLimit = Runtime.getRuntime().maxMemory()
            val lowRam = am.isLowRamDevice

            // Soporte de compresión de textura: se consulta al renderer GL.
            // Aquí usamos heurísticas conservadoras; GLRenderer confirma con GLES.
            val astc = Build.VERSION.SDK_INT >= 21 // ASTC está disponible en la mayoría desde Android 5
            val etc2 = Build.VERSION.SDK_INT >= 21 // ETC2 es obligatorio en GLES 3.0
            val s3tc = false

            val quality = when {
                lowRam || total < 2L * 1024 * 1024 * 1024 -> Quality.LOW
                total < 4L * 1024 * 1024 * 1024 -> Quality.MEDIUM
                else -> Quality.HIGH
            }

            val p = Profile(
                sdkInt = Build.VERSION.SDK_INT,
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                totalRamBytes = total,
                lowRamDevice = lowRam,
                heapLimitBytes = heapLimit,
                supportsVulkan = false,
                supportsAstc = astc,
                supportsEtc2 = etc2,
                supportsS3tc = s3tc,
                maxTextureSize = 2048,
                initialQuality = quality
            )
            Log.i(TAG, "Perfil: ${p.model} SDK=${p.sdkInt} RAM=${total / 1024 / 1024}MB quality=${p.initialQuality}")
            cached = p
            return p
        }
    }

    /** Actualiza el soporte Vulkan/formatos cuando el renderer GL confirma las extensiones. */
    fun reportGlSupport(vulkan: Boolean, astc: Boolean, etc2: Boolean, s3tc: Boolean, maxTex: Int) {
        val cur = cached ?: return
        cached = cur.copy(
            supportsVulkan = vulkan,
            supportsAstc = astc,
            supportsEtc2 = etc2,
            supportsS3tc = s3tc,
            maxTextureSize = maxTex
        )
    }

    /** RAM libre actual del sistema. */
    fun availableRam(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    /** Uso actual del heap de la app en bytes. */
    fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    /** Heap nativo (no Java) en bytes. */
    fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()
}
