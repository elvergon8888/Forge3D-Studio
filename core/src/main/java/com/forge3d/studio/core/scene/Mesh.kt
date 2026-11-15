package com.forge3d.studio.core.scene

/**
 * Geometría: "sopa de triángulos" con posiciones, normales y opcionalmente UVs.
 * Independiente de Android: testeable en JVM pura.
 */
class Mesh {
    var pos = FloatArray(4096) private set
    var nrm = FloatArray(4096) private set
    var uv = FloatArray(0) private set
    var used = 0 private set // floats ocupados (3 por vértice)

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

    /** Número de referencias (para duplicados que comparten malla). */
    var users = 1
    var needsUpload = false

    val vertCount: Int get() = used / 3
    val triangleCount: Int get() = used / 9
    val hasUv: Boolean get() = used > 0 && uv.size >= vertCount * 2

    private fun ensure(extra: Int) {
        if (used + extra > pos.size) {
            var cap = pos.size
            while (cap < used + extra) cap = cap * 2
            pos = pos.copyOf(cap); nrm = nrm.copyOf(cap)
        }
        val uvNeeded = (used + extra) / 3 * 2
        if (uv.size < uvNeeded) uv = uv.copyOf(uvNeeded)
    }

    private fun growBounds(ax: Float, ay: Float, az: Float,
                           bx: Float, by: Float, bz: Float,
                           cx: Float, cy: Float, cz: Float) {
        minX = min(minX, min(ax, min(bx, cx))); maxX = max(maxX, max(ax, max(bx, cx)))
        minY = min(minY, min(ay, min(by, cy))); maxY = max(maxY, max(ay, max(by, cy)))
        minZ = min(minZ, min(az, min(bz, cz))); maxZ = max(maxZ, max(az, max(bz, cz)))
    }

    fun addTriangle(ax: Float, ay: Float, az: Float,
                    bx: Float, by: Float, bz: Float,
                    cx: Float, cy: Float, cz: Float,
                    nx: Float, ny: Float, nz: Float) {
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

    fun addTriangleUv(ax: Float, ay: Float, az: Float,
                      bx: Float, by: Float, bz: Float,
                      cx: Float, cy: Float, cz: Float,
                      nx: Float, ny: Float, nz: Float,
                      u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float) {
        addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz)
        val u = (used - 9) / 3 * 2
        uv[u] = u0; uv[u + 1] = v0
        uv[u + 2] = u1; uv[u + 3] = v1
        uv[u + 4] = u2; uv[u + 5] = v2
    }

    fun addVertex(x: Float, y: Float, z: Float,
                  nx: Float, ny: Float, nz: Float) {
        ensure(3)
        var o = used
        pos[o] = x; pos[o + 1] = y; pos[o + 2] = z
        nrm[o] = nx; nrm[o + 1] = ny; nrm[o + 2] = nz
        used += 3
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    fun recomputeBounds() {
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; minZ = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 3 <= used) {
            val x = pos[i]; val y = pos[i + 1]; val z = pos[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        if (used == 0) { minX = 0f; minY = 0f; minZ = 0f; maxX = 0f; maxY = 0f; maxZ = 0f }
    }

    fun copyData(): Mesh {
        val m = Mesh()
        m.pos = pos.copyOf(used); m.nrm = nrm.copyOf(used); m.uv = uv.copyOf(uv.size)
        m.used = used; m.minX = minX; m.minY = minY; m.minZ = minZ
        m.maxX = maxX; m.maxY = maxY; m.maxZ = maxZ; return m
    }

    companion object {
        fun fromRaw(p: FloatArray, n: FloatArray, u: FloatArray? = null): Mesh? {
            if (p.size != n.size || p.size < 9 || p.size % 9 != 0) return null
            val m = Mesh(); m.pos = p; m.nrm = n; m.used = p.size
            if (u != null) m.uv = u
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
            return m
        }
    }
}
