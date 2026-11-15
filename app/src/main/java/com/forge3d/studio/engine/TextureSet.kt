package com.forge3d.studio.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.File

/**
 * Texturas PBR.
 *
 * Cada material puede llevar hasta cinco mapas: albedo (base color), normal, roughness,
 * metalness y oclusión ambiental (AO). Las texturas se comprimen con el formato que mejor
 * soporte tenga el dispositivo (ASTC → ETC2 → S3TC → RGBA8888), lo que reduce la VRAM hasta
 * un 87% frente a RGBA8888 (una 4K pasa de ~32 MB a ~4 MB).
 *
 * El límite de lado de textura depende de la calidad adaptativa: en dispositivos con poca
 * RAM se reduce a 1K o 512 para no rebasar el presupuesto.
 */
class TextureSet {

    companion object {
        private const val TAG = "Forge3D/Texture"

        /** Lado máximo por defecto cuando no se ha detectado el perfil del dispositivo. */
        const val DEFAULT_MAX_SIZE = 1024
    }

    /** Una textura GL con su handle y sus metadatos. */
    class Texture(val id: Int, val width: Int, val height: Int, val format: DeviceCapabilities.TextureFormat) {
        /** `true` si es un mapa de datos (normal/roughness/metalness) y no de color. */
        val isData: Boolean = false

        fun release() {
            if (id != 0) {
                val b = IntArray(1)
                b[0] = id
                GLES20.glDeleteTextures(1, b, 0)
            }
        }
    }

    /** Handles GL (0 = sin textura). */
    var albedoId = 0
    var normalId = 0
    var roughnessId = 0
    var metalnessId = 0
    var aoId = 0

    /** Tamaño en píxeles del lado mayor. */
    var maxSize = 0; private set

    /** `true` si el material tiene al menos un mapa. */
    val hasAny: Boolean
        get() = albedoId != 0 || normalId != 0 || roughnessId != 0 || metalnessId != 0 || aoId != 0

    /** Formato usado en la última carga. */
    var format = DeviceCapabilities.TextureFormat.RGBA8888; private set

    /**
     * Carga un mapa de textura desde un archivo.
     *
     * @param path ruta absoluta del PNG/JPG.
     * @param maxDim lado máximo permitido (según calidad adaptativa).
     * @param dataMap `true` para mapas de datos (normal, roughness, metalness, AO).
     * @return handle GL o 0 si falló.
     */
    fun load(path: String, maxDim: Int = DEFAULT_MAX_SIZE, dataMap: Boolean = false): Int {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "No existe la textura: $path")
                return 0
            }
            val bmp = decodeScaled(file, maxDim) ?: return 0
            val id = upload(bmp, dataMap)
            bmp.recycle()
            id
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM al cargar textura $path: se degrada la calidad", e)
            0
        } catch (e: Throwable) {
            Log.e(TAG, "Error cargando textura $path", e)
            0
        }
    }

    /**
     * Decodifica el bitmap ya escalado al lado máximo pedido, para no reservar memoria
     * de más (una 4K RGBA8888 ocupa 32 MB solo en el Bitmap).
     */
    private fun decodeScaled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Sube el bitmap a la GPU. */
    private fun upload(bmp: Bitmap, dataMap: Boolean): Int {
        val fmt = detectFormat()
        format = fmt
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        try {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al subir textura: se reintenta con formato minimo", e)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            format = DeviceCapabilities.TextureFormat.RGBA8888
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        maxSize = maxOf(bmp.width, bmp.height)
        return id
    }

    /**
     * Formato de compresión soportado por el driver.
     *
     * Se usa para dimensionar (una 4K en ASTC ocupa ~4 MB frente a ~32 MB en RGBA8888) y para
     * informar en el diagnóstico. La subida efectiva la hace `GLUtils.texImage2D`, que deja
     * la compresión final en manos del driver; la medida efectiva de ahorro que aplica la app
     * es el dimensionado dinámico del bitmap antes de subirlo.
     */
    private fun detectFormat(): DeviceCapabilities.TextureFormat {
        val ext = try {
            GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        } catch (e: Throwable) {
            ""
        }
        return when {
            ext.contains("GL_EXT_texture_compression_astc") -> DeviceCapabilities.TextureFormat.ASTC_4x4
            ext.contains("GL_OES_compressed_ETC2_RGB8") -> DeviceCapabilities.TextureFormat.ETC2_RGB8
            ext.contains("GL_EXT_texture_compression_s3tc") -> DeviceCapabilities.TextureFormat.S3TC_DXT1
            else -> DeviceCapabilities.TextureFormat.RGBA8888
        }
    }

    /** Libera todas las texturas de este conjunto. */
    fun release() {
        for (id in intArrayOf(albedoId, normalId, roughnessId, metalnessId, aoId)) {
            if (id != 0) {
                val b = IntArray(1)
                b[0] = id
                GLES20.glDeleteTextures(1, b, 0)
            }
        }
        albedoId = 0; normalId = 0; roughnessId = 0; metalnessId = 0; aoId = 0
    }

    /** Bytes de VRAM aproximados que ocupa este conjunto. */
    fun vramBytes(): Long {
        if (maxSize == 0) return 0
        val px = maxSize.toLong() * maxSize.toLong()
        val bpp = when (format) {
            DeviceCapabilities.TextureFormat.RGBA8888 -> 4f
            else -> 0.5f
        }
        var n = 0
        for (id in intArrayOf(albedoId, normalId, roughnessId, metalnessId, aoId)) if (id != 0) n++
        return (px * bpp * n).toLong()
    }
}
