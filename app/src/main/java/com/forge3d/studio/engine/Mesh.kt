package com.forge3d.studio.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Malla como "triángulo sopa" (posiciones + normales, sin índices).
 * Unidades: metros. Límite por malla: 1M triángulos.
 */
class Mesh {

    var pos = FloatArray(4096)
        private set
    var nrm = FloatArray(4096)
        private set
    var used = 0 // floats ocupados (3 por vértice)
        private set

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

    /** Número de referencias (para duplicados que comparten malla). */
    var users = 1

    // Lado GL: VBO interleavado (pos3+nrm3 por vértice)
    @Volatile var glBuffer = 0
    @Volatile var needsUpload = false

    val vertCount: Int get() = used / 3
    val triangleCount: Int get() = used / 9

    private fun ensure(extra: Int) {
        if (used + extra > pos.size) {
            var cap = pos.size
            while (cap < used + extra) cap = cap * 2
            pos = pos.copyOf(cap)
            nrm = nrm.copyOf(cap)
        }
    }

    private fun growBounds(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
        minX = min(minX, min(ax, min(bx, cx))); maxX = max(maxX, max(ax, max(bx, cx)))
        minY = min(minY, min(ay, min(by, cy))); maxY = max(maxY, max(ay, max(by, cy)))
        minZ = min(minZ, min(az, min(bz, cz))); maxZ = max(maxZ, max(az, max(bz, cz)))
    }

    fun addTriangle(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        nx: Float, ny: Float, nz: Float
    ) {
        ensure(9)
        var o = used
        pos[o] = ax; pos[o + 1] = ay; pos[o + 2] = az
        pos[o + 3] = bx; pos[o + 4] = by; pos[o + 5] = bz
        pos[o + 6] = cx; pos[o + 7] = cy; pos[o + 8] = cz
        nrm[o] = nx; nrm[o + 1] = ny; nrm[o + 2] = nz
        nrm[o + 3] = nx; nrm[o + 4] = ny; nrm[o + 5] = nz
        nrm[o + 6] = nx; nrm[o + 7] = ny; nrm[o + 8] = nz
        used += 9
        growBounds(ax, ay, az, bx, by, bz, cx, cy, cz)
    }

    fun addTriangle(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray) {
        addTriangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], n[0], n[1], n[2])
    }

    /** Recalcula normales planas por cara (para imports sin normales). */
    fun recomputeNormals() {
        val e1 = FloatArray(3)
        val e2 = FloatArray(3)
        val n = FloatArray(3)
        var o = 0
        while (o + 9 <= used) {
            val ax = pos[o]; val ay = pos[o + 1]; val az = pos[o + 2]
            val bx = pos[o + 3]; val by = pos[o + 4]; val bz = pos[o + 5]
            val cx = pos[o + 6]; val cy = pos[o + 7]; val cz = pos[o + 8]
            e1[0] = bx - ax; e1[1] = by - ay; e1[2] = bz - az
            e2[0] = cx - ax; e2[1] = cy - ay; e2[2] = cz - az
            Math3D.cross(e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], n)
            Math3D.normalize(n)
            for (k in 0..2) {
                val vo = o + k * 3
                nrm[vo] = n[0]; nrm[vo + 1] = n[1]; nrm[vo + 2] = n[2]
            }
            o += 9
        }
        needsUpload = true
    }

    /** Rellena [out] (6) con AABB mundo usando la matriz de modelo [m]. */
    fun transformedBounds(m: FloatArray, out: FloatArray) {
        var a0 = Float.MAX_VALUE; var a1 = Float.MAX_VALUE; var a2 = Float.MAX_VALUE
        var a3 = -Float.MAX_VALUE; var a4 = -Float.MAX_VALUE; var a5 = -Float.MAX_VALUE
        val p = FloatArray(4)
        var i = 0
        while (i < used) {
            for (k in 0..2) {
                p[0] = pos[i + k * 3]
                p[1] = pos[i + k * 3 + 1]
                p[2] = pos[i + k * 3 + 2]
                p[3] = 1f
                val x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12] * p[3]
                val y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13] * p[3]
                val z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14] * p[3]
                a0 = min(a0, x); a1 = min(a1, y); a2 = min(a2, z)
                a3 = max(a3, x); a4 = max(a4, y); a5 = max(a5, z)
            }
            i += 9
        }
        out[0] = a0; out[1] = a1; out[2] = a2; out[3] = a3; out[4] = a4; out[5] = a5
    }

    /** AABB local en [out] (6). */
    fun localBounds(out: FloatArray) {
        out[0] = minX; out[1] = minY; out[2] = minZ
        out[3] = maxX; out[4] = maxY; out[5] = maxZ
    }

    fun copyData(): Mesh {
        val m = Mesh()
        m.pos = pos.copyOf(used)
        m.nrm = nrm.copyOf(used)
        m.used = used
        m.minX = minX; m.minY = minY; m.minZ = minZ
        m.maxX = maxX; m.maxY = maxY; m.maxZ = maxZ
        m.needsUpload = true
        return m
    }

    companion object {
        /** Construye una malla desde arrays crudos (mismo largo, múltiplo de 9 floats). */
        fun fromRaw(p: FloatArray, n: FloatArray): Mesh? {
            if (p.size != n.size || p.size < 9 || p.size % 9 != 0) return null
            val m = Mesh()
            m.pos = p
            m.nrm = n
            m.used = p.size
            var i = 0
            while (i < p.size) {
                val ax = p[i]; val ay = p[i + 1]; val az = p[i + 2]
                val bx = p[i + 3]; val by = p[i + 4]; val bz = p[i + 5]
                val cx = p[i + 6]; val cy = p[i + 7]; val cz = p[i + 8]
                m.minX = min(m.minX, min(ax, min(bx, cx))); m.maxX = max(m.maxX, max(ax, max(bx, cx)))
                m.minY = min(m.minY, min(ay, min(by, cy))); m.maxY = max(m.maxY, max(ay, max(by, cy)))
                m.minZ = min(m.minZ, min(az, min(bz, cz))); m.maxZ = max(m.maxZ, max(az, max(bz, cz)))
                i += 9
            }
            m.needsUpload = true
            return m
        }
    }
}
