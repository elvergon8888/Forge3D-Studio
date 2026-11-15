package com.forge3d.studio.io

import android.util.Xml
import com.forge3d.studio.engine.FloatList
import com.forge3d.studio.engine.IntList
import com.forge3d.studio.engine.Mesh
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Importador 3MF (ZIP con modelo XML 3D). Clean-room. */
object ThreeMfImporter {

    fun import(bytes: ByteArray, name: String): Mesh? {
        val verts = FloatList(4096)
        val tris = IntList(4096)
        var found = false
        try {
            val zin = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zin.nextEntry
            while (entry != null) {
                val en = entry.name.lowercase()
                if (en.endsWith(".model")) {
                    found = true
                    val parser = Xml.newPullParser()
                    parser.setInput(zin, null)
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (parser.name.lowercase()) {
                                "vertex" -> {
                                    verts.add(attrF(parser, "x"))
                                    verts.add(attrF(parser, "y"))
                                    verts.add(attrF(parser, "z"))
                                }
                                "triangle" -> {
                                    tris.add(attrI(parser, "v1"))
                                    tris.add(attrI(parser, "v2"))
                                    tris.add(attrI(parser, "v3"))
                                }
                            }
                        }
                        ev = parser.next()
                    }
                    break
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
            zin.close()
        } catch (e: Exception) {
            if (tris.n == 0) return null
        }
        if (!found || tris.n < 3 || verts.n < 9) return null

        val m = Mesh()
        val n = FloatArray(3)
        var i = 0
        while (i + 3 <= tris.n) {
            val a = tris.a[i] * 3
            val b = tris.a[i + 1] * 3
            val c = tris.a[i + 2] * 3
            if (a + 2 < verts.n && b + 2 < verts.n && c + 2 < verts.n) {
                val ax = verts.a[a]; val ay = verts.a[a + 1]; val az = verts.a[a + 2]
                val bx = verts.a[b]; val by = verts.a[b + 1]; val bz = verts.a[b + 2]
                val cx = verts.a[c]; val cy = verts.a[c + 1]; val cz = verts.a[c + 2]
                faceNormal(ax, ay, az, bx, by, bz, cx, cy, cz, n)
                m.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, n[0], n[1], n[2])
            }
            i += 3
        }
        if (m.triangleCount == 0) return null
        m.needsUpload = true
        return m
    }

    private fun attrF(p: XmlPullParser, name: String): Float = p.getAttributeValue(null, name)?.toFloatOrNull() ?: 0f
    private fun attrI(p: XmlPullParser, name: String): Int = p.getAttributeValue(null, name)?.toIntOrNull() ?: 0

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
