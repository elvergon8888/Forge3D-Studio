package com.forge3d.studio.core.ops

import com.forge3d.studio.core.math.Mat4
import com.forge3d.studio.core.scene.Mesh
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Operaciones de edición de malla. Todas puras: devuelven malla NUEVA sin mutar la original.
 */
object MeshOps {
    const val WELD_EPSILON = 1e-5f

    class Topology(
        val positions: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val triCount: Int
    ) {
        val vertCount: Int get() = positions.size / 3
    }

    fun weld(mesh: Mesh, eps: Float = WELD_EPSILON): Topology {
        val positions = FloatArray(mesh.used)
        val normals = FloatArray(mesh.used)
        val indices = IntArray(mesh.triangleCount * 3)
        val cellSize = if (eps <= 0f) WELD_EPSILON else eps
        val cells = HashMap<Long, MutableList<Int>>()
        val eps2 = cellSize * cellSize
        var v = 0; var t = 0; var o = 0
        while (o + 9 <= mesh.used) {
            for (k in 0..2) {
                val vo = o + k * 3
                val x = mesh.pos[vo]; val y = mesh.pos[vo + 1]; val z = mesh.pos[vo + 2]
                val cx = kotlin.math.floor((x / cellSize)).toLong()
                val cy = kotlin.math.floor((y / cellSize)).toLong()
                val cz = kotlin.math.floor((z / cellSize)).toLong()
                var found = -1
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        for (dz in -1..1) {
                            val bucket = cells[hashCell(cx + dx, cy + dy, cz + dz)] ?: continue
                            for (cand in bucket) {
                                val co = cand * 3
                                val ddx = positions[co] - x; val ddy = positions[co + 1] - y; val ddz = positions[co + 2] - z
                                if (ddx * ddx + ddy * ddy + ddz * ddz <= eps2) { found = cand; break }
                            }
                            if (found >= 0) break
                        }
                        if (found >= 0) break
                    }
                    if (found >= 0) break
                }
                val idx: Int
                if (found >= 0) idx = found else {
                    idx = v; val no = v * 3
                    positions[no] = x; positions[no + 1] = y; positions[no + 2] = z
                    normals[no] = mesh.nrm[vo]; normals[no + 1] = mesh.nrm[vo + 1]; normals[no + 2] = mesh.nrm[vo + 2]
                    v++; cells.getOrPut(hashCell(cx, cy, cz)) { ArrayList() }.add(idx)
                }
                indices[t * 3 + k] = idx
            }
            t++; o += 9
        }
        return Topology(positions.copyOf(v * 3), normals.copyOf(v * 3), indices.copyOf(t * 3), t)
    }

    private fun hashCell(x: Long, y: Long, z: Long): Long =
        (x * 73856093L) xor (y * 19349663L) xor (z * 83492791L)

    fun unwrap(top: Topology): Mesh {
        val m = Mesh(); var t = 0
        while (t < top.triCount) {
            val i0 = top.indices[t * 3]; val i1 = top.indices[t * 3 + 1]; val i2 = top.indices[t * 3 + 2]
            if (i0 != i1 && i1 != i2 && i0 != i2) {
                val ao = i0 * 3; val bo = i1 * 3; val co = i2 * 3
                m.addTriangleUv(top.positions[ao], top.positions[ao + 1], top.positions[ao + 2],
                    top.positions[bo], top.positions[bo + 1], top.positions[bo + 2],
                    top.positions[co], top.positions[co + 1], top.positions[co + 2],
                    top.normals[ao], top.normals[ao + 1], top.normals[ao + 2],
                    0f, 0f, 0f, 0f, 0f, 0f)
            }
            t++
        }
        return m
    }

    fun subdivide(mesh: Mesh): Mesh {
        val top = weld(mesh); val nVerts = top.vertCount
        val maxMids = top.triCount * 3
        val allPos = FloatArray(top.positions.size + maxMids * 3)
        val allNrm = FloatArray(top.normals.size + maxMids * 3)
        System.arraycopy(top.positions, 0, allPos, 0, top.positions.size)
        System.arraycopy(top.normals, 0, allNrm, 0, top.normals.size)
        val edgeMid = HashMap<Long, Int>(); var midCount = 0
        fun edgeKey(a: Int, b: Int): Long {
            val lo = if (a < b) a else b; val hi = if (a < b) b else a
            return lo.toLong() * 1000003L + hi.toLong()
        }
        val m = Mesh(); var t = 0
        while (t < top.triCount) {
            val i0 = top.indices[t * 3]; val i1 = top.indices[t * 3 + 1]; val i2 = top.indices[t * 3 + 2]
            val mids = IntArray(3)
            val pairs = arrayOf(intArrayOf(i0, i1), intArrayOf(i1, i2), intArrayOf(i2, i0))
            for (e in 0..2) {
                val a = pairs[e][0]; val b = pairs[e][1]; val key = edgeKey(a, b)
                var mid = edgeMid[key]
                if (mid == null) {
                    mid = nVerts + midCount; edgeMid[key] = mid; val mo = mid * 3
                    val ao = a * 3; val bo = b * 3
                    allPos[mo] = (allPos[ao] + allPos[bo]) / 2f; allPos[mo + 1] = (allPos[ao + 1] + allPos[bo + 1]) / 2f
                    allPos[mo + 2] = (allPos[ao + 2] + allPos[bo + 2]) / 2f
                    allNrm[mo] = (allNrm[ao] + allNrm[bo]) / 2f; allNrm[mo + 1] = (allNrm[ao + 1] + allNrm[bo + 1]) / 2f
                    allNrm[mo + 2] = (allNrm[ao + 2] + allNrm[bo + 2]) / 2f; midCount++
                }
                mids[e] = mid
            }
            emitTri(m, allPos, allNrm, i0, mids[0], mids[2])
            emitTri(m, allPos, allNrm, i1, mids[1], mids[0])
            emitTri(m, allPos, allNrm, i2, mids[2], mids[1])
            emitTri(m, allPos, allNrm, mids[0], mids[1], mids[2])
            t++
        }
        smoothNormals(m); return m
    }

    private fun emitTri(m: Mesh, pos: FloatArray, nrm: FloatArray, a: Int, b: Int, c: Int) {
        val ao = a * 3; val bo = b * 3; val co = c * 3
        m.addTriangleUv(pos[ao], pos[ao + 1], pos[ao + 2], pos[bo], pos[bo + 1], pos[bo + 2],
            pos[co], pos[co + 1], pos[co + 2], nrm[ao], nrm[ao + 1], nrm[ao + 2],
            0f, 0f, 0f, 0f, 0f, 0f)
    }

    fun extrude(mesh: Mesh, amount: Float): Mesh {
        if (amount == 0f) return mesh.copyData()
        val out = mesh.copyData(); var i = 0
        while (i + 9 <= out.used) {
            for (k in 0..2) { val vo = i + k * 3
                out.pos[vo] += out.nrm[vo] * amount; out.pos[vo + 1] += out.nrm[vo + 1] * amount
                out.pos[vo + 2] += out.nrm[vo + 2] * amount
            }
            i += 9
        }
        out.recomputeBounds(); out.needsUpload = true; return out
    }

    fun bevel(mesh: Mesh, factor: Float): Mesh {
        val f = factor.coerceIn(0f, 1f); if (f == 0f) return mesh.copyData()
        val out = mesh.copyData(); var i = 0
        while (i + 9 <= out.used) {
            val cx = (out.pos[i] + out.pos[i + 3] + out.pos[i + 6]) / 3f
            val cy = (out.pos[i + 1] + out.pos[i + 4] + out.pos[i + 7]) / 3f
            val cz = (out.pos[i + 2] + out.pos[i + 5] + out.pos[i + 8]) / 3f
            for (k in 0..2) { val vo = i + k * 3
                out.pos[vo] += (cx - out.pos[vo]) * f; out.pos[vo + 1] += (cy - out.pos[vo + 1]) * f
                out.pos[vo + 2] += (cz - out.pos[vo + 2]) * f
            }
            i += 9
        }
        out.recomputeBounds(); out.needsUpload = true; return out
    }

    fun smoothNormals(mesh: Mesh): Mesh {
        val top = weld(mesh); val acc = FloatArray(top.positions.size)
        var t = 0
        while (t < top.triCount) {
            val i0 = top.indices[t * 3]; val i1 = top.indices[t * 3 + 1]; val i2 = top.indices[t * 3 + 2]
            val ao = i0 * 3; val bo = i1 * 3; val co = i2 * 3
            val e1 = FloatArray(3); val e2 = FloatArray(3); val nn = FloatArray(3)
            e1[0] = top.positions[bo] - top.positions[ao]; e1[1] = top.positions[bo + 1] - top.positions[ao + 1]
            e1[2] = top.positions[bo + 2] - top.positions[ao + 2]
            e2[0] = top.positions[co] - top.positions[ao]; e2[1] = top.positions[co + 1] - top.positions[ao + 1]
            e2[2] = top.positions[co + 2] - top.positions[ao + 2]
            cross(e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], nn); normalize(nn)
            for (vi in intArrayOf(i0, i1, i2)) { val vo = vi * 3
                acc[vo] += nn[0]; acc[vo + 1] += nn[1]; acc[vo + 2] += nn[2]
            }
            t++
        }
        val index = HashMap<Long, Int>()
        for (v in 0 until top.vertCount) {
            val o = v * 3
            index[hashCell(Math.round(top.positions[o] * 100000f).toLong(),
                Math.round(top.positions[o + 1] * 100000f).toLong(),
                Math.round(top.positions[o + 2] * 100000f).toLong())] = v
        }
        var i = 0
        while (i + 9 <= mesh.used) {
            for (k in 0..2) { val vo = i + k * 3
                val key = hashCell(Math.round(mesh.pos[vo] * 100000f).toLong(),
                    Math.round(mesh.pos[vo + 1] * 100000f).toLong(),
                    Math.round(mesh.pos[vo + 2] * 100000f).toLong())
                val idx = index[key]
                if (idx != null) {
                    val to = idx * 3
                    var nx = acc[to]; var ny = acc[to + 1]; var nz = acc[to + 2]
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 1e-8f) { nx /= len; ny /= len; nz /= len }
                    mesh.nrm[vo] = nx; mesh.nrm[vo + 1] = ny; mesh.nrm[vo + 2] = nz
                }
            }
            i += 9
        }
        mesh.needsUpload = true; return mesh
    }

    private fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
        out[0] = ay * bz - az * by; out[1] = az * bx - ax * bz; out[2] = ax * by - ay * bx
    }
    private fun normalize(v: FloatArray) { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (l > 1e-8f) { v[0] /= l; v[1] /= l; v[2] /= l } }

    fun stats(mesh: Mesh): Triple<Int, Int, Int> {
        val top = weld(mesh); val edges = HashSet<Long>(); var t = 0
        while (t < top.triCount) {
            val i0 = top.indices[t * 3]; val i1 = top.indices[t * 3 + 1]; val i2 = top.indices[t * 3 + 2]
            edges.add(edgeKeyOf(i0, i1)); edges.add(edgeKeyOf(i1, i2)); edges.add(edgeKeyOf(i2, i0)); t++
        }
        return Triple(top.vertCount, edges.size, top.triCount)
    }
    private fun edgeKeyOf(a: Int, b: Int): Long {
        val lo = if (a < b) a else b; val hi = if (a < b) b else a
        return lo.toLong() * 1000003L + hi.toLong()
    }

    fun generatePlanarUv(mesh: Mesh) {
        if (mesh.vertCount == 0) return
        val out = FloatArray(mesh.vertCount * 2); var o = 0; var v = 0
        while (o + 9 <= mesh.used) {
            val nx = mesh.nrm[o]; val ny = mesh.nrm[o + 1]; val nz = mesh.nrm[o + 2]
            val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
            val uAxis = when { ax >= ay && ax >= az -> 1; ay >= az -> 2; else -> 0 }
            for (k in 0..2) {
                val vo = o + k * 3; val x = mesh.pos[vo]; val y = mesh.pos[vo + 1]; val z = mesh.pos[vo + 2]
                val uu: Float; val vv: Float
                when (uAxis) {
                    1 -> { uu = z; vv = y }
                    2 -> { uu = x; vv = z }
                    else -> { uu = x; vv = y }
                }
                out[v * 2] = uu; out[v * 2 + 1] = vv; v++
            }
            o += 9
        }
        mesh.uv = out
    }
}
