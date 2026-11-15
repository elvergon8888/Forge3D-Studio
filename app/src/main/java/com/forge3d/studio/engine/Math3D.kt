package com.forge3d.studio.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Utilidades matemáticas 3D originales (unidades en metros, Y hacia arriba). */
object Math3D {

    fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
        out[0] = ay * bz - az * by
        out[1] = az * bx - ax * bz
        out[2] = ax * by - ay * bx
    }

    fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    fun normalize(v: FloatArray) {
        val l = length(v[0], v[1], v[2])
        if (l > 1e-12f) {
            v[0] /= l; v[1] /= l; v[2] /= l
        } else {
            v[0] = 0f; v[1] = 1f; v[2] = 0f
        }
    }

    /** Intersección rayo-triángulo (Möller–Trumbore). Devuelve t (distancia) o null. */
    fun rayTriangle(
        o: FloatArray, d: FloatArray,
        a: FloatArray, b: FloatArray, c: FloatArray
    ): Float? {
        val e1 = FloatArray(3)
        val e2 = FloatArray(3)
        val p = FloatArray(3)
        val tq = FloatArray(3)
        e1[0] = b[0] - a[0]; e1[1] = b[1] - a[1]; e1[2] = b[2] - a[2]
        e2[0] = c[0] - a[0]; e2[1] = c[1] - a[1]; e2[2] = c[2] - a[2]
        cross(d[0], d[1], d[2], e2[0], e2[1], e2[2], p)
        val det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2]
        if (abs(det) < 1e-10f) return null
        val inv = 1f / det
        tq[0] = o[0] - a[0]; tq[1] = o[1] - a[1]; tq[2] = o[2] - a[2]
        val u = (tq[0] * p[0] + tq[1] * p[1] + tq[2] * p[2]) * inv
        if (u < 0f || u > 1f) return null
        val q2 = FloatArray(3)
        cross(tq[0], tq[1], tq[2], e1[0], e1[1], e1[2], q2)
        val v = (d[0] * q2[0] + d[1] * q2[1] + d[2] * q2[2]) * inv
        if (v < 0f || u + v > 1f) return null
        val t = (e2[0] * q2[0] + e2[1] * q2[1] + e2[2] * q2[2]) * inv
        return if (t >= 0f) t else null
    }

    /** Test de caja envolvente (slab test). */
    fun rayAabb(o: FloatArray, d: FloatArray, mn: FloatArray, mx: FloatArray): Boolean {
        var tmin = 0f
        var tmax = Float.MAX_VALUE
        for (i in 0..2) {
            val di = d[i]; val oi = o[i]
            if (abs(di) < 1e-9f) {
                if (oi < mn[i] || oi > mx[i]) return false
            } else {
                var t1 = (mn[i] - oi) / di
                var t2 = (mx[i] - oi) / di
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                tmin = max(tmin, t1)
                tmax = min(tmax, t2)
                if (tmin > tmax) return false
            }
        }
        return true
    }

    fun quatFromAxisAngle(axis: Int, deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val s = sin(r / 2).toFloat()
        val c = cos(r / 2).toFloat()
        return when (axis) {
            0 -> floatArrayOf(s, 0f, 0f, c)
            1 -> floatArrayOf(0f, s, 0f, c)
            else -> floatArrayOf(0f, 0f, s, c)
        }
    }

    fun quatMul(a: FloatArray, b: FloatArray): FloatArray {
        val aw = a[3]; val ax = a[0]; val ay = a[1]; val az = a[2]
        val bw = b[3]; val bx = b[0]; val by = b[1]; val bz = b[2]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    /** Cuaternión desde euler en grados con orden X, luego Y, luego Z (como modelMatrix). */
    fun quatFromEuler(xDeg: Float, yDeg: Float, zDeg: Float): FloatArray {
        val qx = quatFromAxisAngle(0, xDeg)
        val qy = quatFromAxisAngle(1, yDeg)
        val qz = quatFromAxisAngle(2, zDeg)
        return quatMul(quatMul(qx, qy), qz)
    }
}

/** Lista de floats creciente sin boxing. */
class FloatList(cap: Int = 1024) {
    var a = FloatArray(cap)
    var n = 0
        private set

    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }

    fun add3(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
}

/** Lista de ints creciente sin boxing. */
class IntList(cap: Int = 1024) {
    var a = IntArray(cap)
    var n = 0
        private set

    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
}
