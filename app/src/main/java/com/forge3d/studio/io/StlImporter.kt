package com.forge3d.studio.io

import com.forge3d.studio.engine.Mesh
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Importador STL binario y ASCII. Clean-room. */
object StlImporter {

    fun import(bytes: ByteArray, name: String): Mesh? {
        return if (isBinary(bytes)) importBinary(bytes) else importAscii(bytes)
    }

    private fun isBinary(b: ByteArray): Boolean {
        if (b.size < 84) return false
        val bb = ByteBuffer.wrap(b, 80, 4).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int.toLong() and 0xFFFFFFFFL
        return 84L + count * 50L == b.size.toLong()
    }

    private fun importBinary(b: ByteArray): Mesh? {
        val m = Mesh()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80)
        val count = bb.int
        if (count <= 0 || count > 2_000_000) return null
        val n = FloatArray(3)
        for (i in 0 until count) {
            if (bb.remaining() < 50) break
            bb.float; bb.float; bb.float // normal del archivo (se recalcula)
            val ax = bb.float; val ay = bb.float; val az = bb.float
            val bx = bb.float; val by = bb.float; val bz = bb.float
            val cx = bb.float; val cy = bb.float; val cz = bb.float
            bb.short
            faceNormal(ax, ay, az, bx, by, bz, cx, cy, cz, n)
            m.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, n[0], n[1], n[2])
        }
        if (m.triangleCount == 0) return null
        m.needsUpload = true
        return m
    }

    private fun importAscii(b: ByteArray): Mesh? {
        val m = Mesh()
        val reader = ByteArrayInputStream(b).bufferedReader(Charsets.US_ASCII)
        try {
            var got = 0
            var v0x = 0f; var v0y = 0f; var v0z = 0f
            var v1x = 0f; var v1y = 0f; var v1z = 0f
            val n = FloatArray(3)
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.startsWith("vertex")) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) {
                        val x = p[1].toFloatOrNull() ?: 0f
                        val y = p[2].toFloatOrNull() ?: 0f
                        val z = p[3].toFloatOrNull() ?: 0f
                        when (got) {
                            0 -> { v0x = x; v0y = y; v0z = z; got = 1 }
                            1 -> { v1x = x; v1y = y; v1z = z; got = 2 }
                            else -> {
                                faceNormal(v0x, v0y, v0z, v1x, v1y, v1z, x, y, z, n)
                                m.addTriangle(v0x, v0y, v0z, v1x, v1y, v1z, x, y, z, n[0], n[1], n[2])
                                got = 0
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (m.triangleCount == 0) return null
        }
        if (m.triangleCount == 0) return null
        m.needsUpload = true
        return m
    }

    private fun faceNormal(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                          cx: Float, cy: Float, cz: Float, out: FloatArray) {
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        out[0] = e1y * e2z - e1z * e2y
        out[1] = e1z * e2x - e1x * e2z
        out[2] = e1x * e2y - e1y * e2x
        val l = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        if (l > 1e-12f) { out[0] /= l; out[1] /= l; out[2] /= l } else { out[0] = 0f; out[1] = 1f; out[2] = 0f }
    }
}
