package com.forge3d.studio.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.forge3d.studio.core.engine.DeviceCapabilities
import java.io.File

class TextureSet {
    companion object {
        private const val TAG = "Forge3D/Texture"
        const val DEFAULT_MAX_SIZE = 1024
        @Volatile var supportsAstc = false; @Volatile var supportsEtc2 = true; @Volatile var supportsS3tc = false; @Volatile var maxTextureSize = 2048
        fun reportGlSupport(astc: Boolean, etc2: Boolean, s3tc: Boolean, maxTex: Int) {
            supportsAstc = astc; supportsEtc2 = etc2; supportsS3tc = s3tc; maxTextureSize = maxTex
        }
        val EMPTY = TextureSet()
    }

    var albedoId = 0; var normalId = 0; var roughnessId = 0; var metalnessId = 0; var aoId = 0
    var maxSize = 0; var format = DeviceCapabilities.TextureFormat.RGBA8888

    val hasAny: Boolean get() = albedoId != 0 || normalId != 0 || roughnessId != 0 || metalnessId != 0 || aoId != 0

    fun load(path: String, maxDim: Int = DEFAULT_MAX_SIZE, dataMap: Boolean = false): Int {
        return try {
            val file = java.io.File(path); if (!file.exists()) { Log.w(TAG, "No existe: $path"); return 0 }
            val bmp = decodeScaled(file, maxDim) ?: return 0
            val id = upload(bmp, dataMap); bmp.recycle(); id
        } catch (e: OutOfMemoryError) { Log.e(TAG, "OOM textura $path", e); 0 } catch (e: Throwable) { Log.e(TAG, "Error textura $path", e); 0 }
    }

    private fun decodeScaled(file: java.io.File, maxDim: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun upload(bmp: Bitmap, dataMap: Boolean): Int {
        val fmt = detectFormat(); format = fmt
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        try { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) } catch (e: Throwable) {
            Log.e(TAG, "Reintento sin compresión", e); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0); format = DeviceCapabilities.TextureFormat.RGBA8888
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); maxSize = kotlin.math.max(bmp.width, bmp.height); return id
    }
    private fun detectFormat(): DeviceCapabilities.TextureFormat {
        val ext = try { GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "" } catch (_: Throwable) { "" }
        return when { ext.contains("GL_EXT_texture_compression_astc") -> DeviceCapabilities.TextureFormat.ASTC_4x4; ext.contains("GL_OES_compressed_ETC2_RGB8") -> DeviceCapabilities.TextureFormat.ETC2_RGB8; ext.contains("GL_EXT_texture_compression_s3tc") -> DeviceCapabilities.TextureFormat.S3TC_DXT1; else -> DeviceCapabilities.TextureFormat.RGBA8888 }
    }
    fun release() { for (id in intArrayOf(albedoId, normalId, roughnessId, metalnessId, aoId)) if (id != 0) { val b = IntArray(1); b[0] = id; GLES20.glDeleteTextures(1, b, 0) }; albedoId = 0; normalId = 0; roughnessId = 0; metalnessId = 0; aoId = 0 }
    fun vramBytes(): Long { if (maxSize == 0) return 0; val px = maxSize.toLong() * maxSize.toLong(); val bpp = when (format) { DeviceCapabilities.TextureFormat.RGBA8888 -> 4f; else -> 0.5f }; var n = 0; for (id in intArrayOf(albedoId, normalId, roughnessId, metalnessId, aoId)) if (id != 0) n++; return (px * bpp * n).toLong() }
}
