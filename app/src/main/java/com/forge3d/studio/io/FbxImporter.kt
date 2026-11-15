package com.forge3d.studio.io

import com.forge3d.studio.engine.Mesh
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Importador FBX minimalista (clean-room): binario 7.x (offsets 32/64 bits,
 * arrays zlib) y ASCII (Vertices + PolygonVertexIndex). Solo geometría
 * poligonal; sin materiales, UVs, huesos ni animaciones.
 */
object FbxImporter {

    fun import(bytes: ByteArray, name: String): Mesh? {
        return if (bytes.size > 31 &&
            String(bytes, 0, 20, StandardCharsets.US_ASCII) == "Kaydara FBX Binary  "
        ) {
            importBinary(bytes)
        } else if (looksAsciiFbx(bytes)) {
            importAscii(bytes)
        } else null
    }

    private fun looksAsciiFbx(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 8192), StandardCharsets.US_ASCII)
        return head.contains("FBXHeaderExtension")
    }

    // ---------------- ASCII ----------------

    private fun importAscii(b: ByteArray): Mesh? {
        val text = String(b, StandardCharsets.ISO_8859_1)
        val vertsRe = Regex("Vertices:\\s*\\*\\d+\\s*\\{([^}]*)\\}")
        val idxRe = Regex("PolygonVertexIndex:\\s*\\*\\d+\\s*\\{([^}]*)\\}")
        val mv = vertsRe.find(text) ?: return null
        val mi = idxRe.find(text) ?: return null
        val verts = ArrayList<Float>(1024)
        for (t in mv.groupValues[1].split(',')) t.trim().toFloatOrNull()?.let { verts.add(it) }
        val polys = ArrayList<Int>(1024)
        for (t in mi.groupValues[1].split(',')) t.trim().toIntOrNull()?.let { polys.add(it) }
        if (verts.size < 9 || polys.size < 3) return null
        val m = Mesh()
        triangulate(polys, verts.toFloatArray(), m)
        if (m.triangleCount == 0) return null
        m.recomputeNormals()
        m.needsUpload = true
        return m
    }

    // ---------------- Binario ----------------

    private class Cur(val b: ByteArray) {
        var o = 0
        fun u8(): Int { val v = b[o].toInt() and 0xFF; o += 1; return v }
        fun u32(): Long {
            val v = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            o += 4; return v
        }
        fun i64(): Long {
            val v = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
            o += 8; return v
        }
        fun i32(): Int {
            val v = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
            o += 4; return v
        }
        fun bytes(n: Int): ByteArray {
            if (n < 0 || o + n > b.size) throw IllegalArgumentException("FBX truncado")
            val r = b.copyOfRange(o, o + n); o += n; return r
        }
    }

    private class Node(val name: String, val props: List<Any?>, val children: List<Node>)

    private fun importBinary(b: ByteArray): Mesh? {
        return try {
            val version = ByteBuffer.wrap(b, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val use64 = version >= 7500
            val c = Cur(b)
            c.o = 27
            val root = ArrayList<Node>()
            while (c.o + 13 <= b.size) {
                val n = readNode(c, use64) ?: break
                root.add(n)
            }
            var verts: FloatArray? = null
            var idx: IntArray? = null
            findGeometry(root, listOf(), { v, i ->
                // acumulamos la primera geometría válida
                if (verts == null && i != null && v != null && i.size >= 3 && v.size >= 9) {
                    verts = v
                    idx = i
                }
            })
            if (verts == null || idx == null) return null
            val m = Mesh()
            triangulate(idx!!.toList(), verts!!, m)
            if (m.triangleCount == 0) return null
            m.recomputeNormals()
            m.needsUpload = true
            m
        } catch (e: Exception) {
            null
        }
    }

    private fun findGeometry(
        nodes: List<Node>, path: List<String>,
        sink: (FloatArray?, IntArray?) -> Unit
    ) {
        for (n in nodes) {
            val p = path + n.name
            if (n.name == "Geometry") {
                var verts: FloatArray? = null
                var idx: IntArray? = null
                for (ch in n.children) {
                    if (ch.name == "Vertices" && ch.props.isNotEmpty()) verts = asFloats(ch.props[0])
                    if (ch.name == "PolygonVertexIndex" && ch.props.isNotEmpty()) idx = asInts(ch.props[0])
                }
                sink(verts, idx)
            } else {
                findGeometry(n.children, p, sink)
            }
        }
    }

    private fun readNode(c: Cur, use64: Boolean): Node? {
        val nullSize = if (use64) 25 else 13
        if (c.o + nullSize > c.b.size) return null
        val endOff: Long = if (use64) c.i64() else c.u32()
        val numProps: Long = if (use64) c.i64() else c.u32()
        val propLen: Long = if (use64) c.i64() else c.u32()
        if (endOff == 0L) return null
        val nameLen = c.u8()
        val name = String(c.bytes(nameLen), StandardCharsets.US_ASCII)
        val propsEnd = c.o + propLen.toInt()
        val props = ArrayList<Any?>()
        var p = 0L
        while (p < numProps && c.o < propsEnd) {
            props.add(readProp(c))
            p++
        }
        c.o = propsEnd
        val children = ArrayList<Node>()
        while (c.o < endOff - nullSize) {
            val before = c.o
            val sub = readNode(c, use64)
            if (sub == null) break
            children.add(sub)
            if (c.o <= before) break
        }
        c.o = endOff.toInt()
        return Node(name, props, children)
    }

    private fun readProp(c: Cur): Any? {
        val t = c.u8().toChar()
        return when (t) {
            'C' -> c.u8() == 1
            'I' -> c.i32()
            'F' -> ByteBuffer.wrap(c.bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float
            'D' -> ByteBuffer.wrap(c.bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
            'L' -> c.i64()
            'd' -> readArrayD(c)
            'f' -> readArrayF(c)
            'i' -> readArrayI(c, false)
            'l' -> readArrayI(c, true)
            'S', 'R' -> {
                val len = c.i32()
                if (len <= 0) null else String(c.bytes(len), StandardCharsets.UTF_8)
            }
            'Y' -> c.bytes(2)
            else -> null
        }
    }

    private fun readArrayD(c: Cur): DoubleArray? {
        val arrLen = c.u32().toInt()
        val encoding = c.u32().toInt()
        val compLen = c.u32().toInt()
        if (arrLen <= 0 || compLen <= 0 || compLen > 1_000_000_000) return null
        val data = c.bytes(compLen)
        val raw = if (encoding == 1) inflate(data) else data
        if (raw.size < arrLen * 8) return null
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(arrLen) { bb.double }
    }

    private fun readArrayF(c: Cur): FloatArray? {
        val arrLen = c.u32().toInt()
        val encoding = c.u32().toInt()
        val compLen = c.u32().toInt()
        if (arrLen <= 0 || compLen <= 0 || compLen > 1_000_000_000) return null
        val data = c.bytes(compLen)
        val raw = if (encoding == 1) inflate(data) else data
        if (raw.size < arrLen * 4) return null
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(arrLen) { bb.float }
    }

    private fun readArrayI(c: Cur, long64: Boolean): Any? {
        val arrLen = c.u32().toInt()
        val encoding = c.u32().toInt()
        val compLen = c.u32().toInt()
        if (arrLen <= 0 || compLen <= 0 || compLen > 1_000_000_000) return null
        val data = c.bytes(compLen)
        val raw = if (encoding == 1) inflate(data) else data
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return if (long64) {
            if (raw.size < arrLen * 8) return null
            LongArray(arrLen) { bb.long }
        } else {
            if (raw.size < arrLen * 4) return null
            IntArray(arrLen) { bb.int }
        }
    }

    private fun asFloats(p: Any?): FloatArray? = when (p) {
        is FloatArray -> p
        is DoubleArray -> FloatArray(p.size) { p[it].toFloat() }
        else -> null
    }

    private fun asInts(p: Any?): IntArray? = when (p) {
        is IntArray -> p
        is LongArray -> IntArray(p.size) { p[it].toInt() }
        else -> null
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    // ---------------- Triangulación ----------------

    private fun triangulate(polys: List<Int>, verts: FloatArray, m: Mesh) {
        val poly = ArrayList<Int>(16)
        for (v in polys) {
            if (v < 0) {
                poly.add(v.inv())
                fan(poly, verts, m)
                poly.clear()
            } else {
                poly.add(v)
            }
        }
        if (poly.size >= 3) fan(poly, verts, m)
    }

    private fun fan(poly: List<Int>, verts: FloatArray, m: Mesh) {
        if (poly.size < 3) return
        val n = FloatArray(3)
        for (k in 1 until poly.size - 1) {
            val a = poly[0] * 3
            val b = poly[k] * 3
            val c = poly[k + 1] * 3
            if (a + 2 >= verts.size || b + 2 >= verts.size || c + 2 >= verts.size) continue
            val ax = verts[a]; val ay = verts[a + 1]; val az = verts[a + 2]
            val bx = verts[b]; val by = verts[b + 1]; val bz = verts[b + 2]
            val cx = verts[c]; val cy = verts[c + 1]; val cz = verts[c + 2]
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            n[0] = e1y * e2z - e1z * e2y
            n[1] = e1z * e2x - e1x * e2z
            n[2] = e1x * e2y - e1y * e2x
            val l = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (l > 1e-12f) { n[0] /= l; n[1] /= l; n[2] /= l }
            m.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, n[0], n[1], n[2])
        }
    }
}
