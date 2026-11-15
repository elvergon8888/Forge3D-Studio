package com.forge3d.studio.io

import com.forge3d.studio.engine.FloatList
import com.forge3d.studio.engine.Mesh
import java.io.ByteArrayInputStream

/** Importador Wavefront OBJ (v/vn/f, polígonos → abanico de triángulos). Clean-room. */
object ObjImporter {

    fun import(bytes: ByteArray, name: String): Mesh? {
        val reader = ByteArrayInputStream(bytes).bufferedReader(Charsets.US_ASCII)
        val verts = FloatList(4096)
        val norms = FloatList(4096)
        val out = Mesh()

        try {
            var lineN = 0
            reader.forEachLine { raw ->
                lineN++
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "v" -> if (parts.size >= 4) {
                        verts.add(parts[1].toFloatOrNull() ?: 0f)
                        verts.add(parts[2].toFloatOrNull() ?: 0f)
                        verts.add(parts[3].toFloatOrNull() ?: 0f)
                    }
                    "vn" -> if (parts.size >= 4) {
                        norms.add(parts[1].toFloatOrNull() ?: 0f)
                        norms.add(parts[2].toFloatOrNull() ?: 0f)
                        norms.add(parts[3].toFloatOrNull() ?: 0f)
                    }
                    "f" -> {
                        val nv = parts.size - 1
                        if (nv < 3) return@forEachLine
                        val idx = IntArray(nv)
                        val inorm = IntArray(nv)
                        var anyNorm = true
                        for (i in 0 until nv) {
                            val tok = parts[i + 1].split("/")
                            var vi = (tok[0].toIntOrNull() ?: 0)
                            vi = if (vi < 0) verts.n / 3 + vi else vi - 1
                            idx[i] = vi * 3
                            var ni = if (tok.size >= 3) (tok[2].toIntOrNull() ?: 0) else 0
                            ni = if (ni < 0) norms.n / 3 + ni else ni - 1
                            inorm[i] = ni * 3
                            if (ni < 0) anyNorm = false
                        }
                        // abanico
                        for (k in 1 until nv - 1) {
                            val ax = getf(verts, idx[0]); val ay = getf(verts, idx[0] + 1); val az = getf(verts, idx[0] + 2)
                            val bx = getf(verts, idx[k]); val by = getf(verts, idx[k] + 1); val bz = getf(verts, idx[k] + 2)
                            val cx = getf(verts, idx[k + 1]); val cy = getf(verts, idx[k + 1] + 1); val cz = getf(verts, idx[k + 1] + 2)
                            if (anyNorm) {
                                out.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz,
                                    getf(norms, inorm[0]), getf(norms, inorm[0] + 1), getf(norms, inorm[0] + 2))
                            } else {
                                out.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, 0f, 1f, 0f)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (out.triangleCount == 0) return null
        }

        if (out.triangleCount == 0) return null
        if (norms.n == 0) out.recomputeNormals()
        out.needsUpload = true
        return out
    }

    private fun getf(l: FloatList, i: Int): Float = if (i >= 0 && i < l.n) l.a[i] else 0f
}
