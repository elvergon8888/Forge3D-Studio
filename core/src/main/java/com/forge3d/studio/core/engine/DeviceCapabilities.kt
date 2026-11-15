package com.forge3d.studio.core.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Debug
import android.util.Log

object DeviceCapabilities {
    enum class Quality { LOW, MEDIUM, HIGH }
    enum class TextureFormat(val glInternalFormat: Int, val glFormat: Int, val glType: Int, val bytesPerPixel: Float) {
        RGBA8888(0x8058, 0x1908, 0x1401, 4f), ASTC_4x4(0x93B4, 0x1908, 0x1401, 0.5f),
        ETC2_RGB8(0x9274, 0x9274, 0x9274, 0.5f), S3TC_DXT1(0x83F1, 0x83F1, 0x83F1, 0.5f)
    }
    data class Profile(
        val sdkInt: Int, val model: String, val totalRamBytes: Long, val lowRamDevice: Boolean,
        val heapLimitBytes: Long, val supportsVulkan: Boolean, val supportsAstc: Boolean,
        val supportsEtc2: Boolean, val supportsS3tc: Boolean, val maxTextureSize: Int,
        val initialQuality: Quality
    ) {
        val appHeapBudget: Long get() = (heapLimitBytes * 55 / 100).coerceIn(24L * 1024 * 1024, 256L * 1024 * 1024)
        val textureBudget: Long get() = (appHeapBudget * 25 / 100)
        val textureFormat: TextureFormat get() = when { supportsAstc -> TextureFormat.ASTC_4x4; supportsEtc2 -> TextureFormat.ETC2_RGB8; supportsS3tc -> TextureFormat.S3TC_DXT1; else -> TextureFormat.RGBA8888 }
        fun maxTextureDimension(quality: Quality): Int = when (quality) { Quality.LOW -> 512; Quality.MEDIUM -> if (totalRamBytes < 3L * 1024 * 1024 * 1024) 1024 else 2048; Quality.HIGH -> if (totalRamBytes < 4L * 1024 * 1024 * 1024) 2048 else 4096 }
    }
    @Volatile private var cached: Profile? = null
    fun detect(ctx: Context): Profile {
        cached?.let { return it }
        synchronized(this) { cached?.let { return it }; val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); val total = if (mi.totalMem > 0) mi.totalMem else Runtime.getRuntime().maxMemory(); val heapLimit = Runtime.getRuntime().maxMemory(); val lowRam = am.isLowRamDevice; val quality = when { lowRam || total < 2L * 1024 * 1024 * 1024 -> Quality.LOW; total < 4L * 1024 * 1024 * 1024 -> Quality.MEDIUM; else -> Quality.HIGH }; val p = Profile(Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}", total, lowRam, heapLimit, false, Build.VERSION.SDK_INT >= 21, Build.VERSION.SDK_INT >= 21, false, 2048, quality); Log.i("Forge3D/Capabilities", "Perfil: ${p.model} SDK=${p.sdkInt} RAM=${total / 1024 / 1024}MB quality=${p.initialQuality}"); cached = p; return p }
    }
    fun reportGlSupport(vulkan: Boolean, astc: Boolean, etc2: Boolean, s3tc: Boolean, maxTex: Int) { val cur = cached ?: return; cached = cur.copy(supportsVulkan = vulkan, supportsAstc = astc, supportsEtc2 = etc2, supportsS3tc = s3tc, maxTextureSize = maxTex) }
    fun availableRam(ctx: Context): Long { val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.availMem }
    fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()
}
