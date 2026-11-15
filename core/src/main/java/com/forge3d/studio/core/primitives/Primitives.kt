package com.forge3d.studio.core.primitives

import com.forge3d.studio.core.math.Mat4
import com.forge3d.studio.core.scene.Mesh

object Primitives {
    const val CUBE = "cubo"; const val ESFERA = "esfera"; const val CILINDRO = "cilindro"; const val PLANO = "plano"

    fun validTypes() = listOf(CUBE, ESFERA, CILINDRO, PLANO)

    fun create(type: String): Mesh? = when (type) {
        CUBE -> cube()
        ESFERA -> sphere()
        CILINDRO -> cylinder()
        PLANO -> plane()
        else -> null
    }

    fun cube(): Mesh {
        val m = Mesh(); val n = floatArrayOf(0f, 0f, 0f); val p = 0.5f
        fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, nx: Float, ny: Float, nz: Float) {
            n[0] = nx; n[1] = ny; n[2] = nz
            m.addTriangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], nx, ny, nz)
            m.addTriangle(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], nx, ny, nz)
        }
        face(floatArrayOf(p, -p, -p), floatArrayOf(p, p, -p), floatArrayOf(p, p, p), floatArrayOf(p, -p, p), 1f, 0f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(-p, p, p), floatArrayOf(-p, p, -p), floatArrayOf(-p, -p, -p), -1f, 0f, 0f)
        face(floatArrayOf(-p, p, -p), floatArrayOf(-p, p, p), floatArrayOf(p, p, p), floatArrayOf(p, p, -p), 0f, 1f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(-p, -p, -p), floatArrayOf(p, -p, -p), floatArrayOf(p, -p, p), 0f, -1f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(p, -p, p), floatArrayOf(p, p, p), floatArrayOf(-p, p, p), 0f, 0f, 1f)
        face(floatArrayOf(p, -p, -p), floatArrayOf(-p, -p, -p), floatArrayOf(-p, p, -p), floatArrayOf(p, p, -p), 0f, 0f, -1f)
        return m
    }

    fun sphere(segU: Int = 32, segV: Int = 20): Mesh {
        val m = Mesh(); val r = 0.5f
        fun pt(i: Int, j: Int): FloatArray {
            val phi = 2.0f * kotlin.math.PI * i / segU
            val theta = kotlin.math.PI * j / segV
            return floatArrayOf((r * kotlin.math.sin(theta) * kotlin.math.cos(phi)).toFloat(),
                (r * kotlin.math.cos(theta)).toFloat(),
                (r * kotlin.math.sin(theta) * kotlin.math.sin(phi)).toFloat())
        }
        fun uvOf(i: Int, j: Int): FloatArray = floatArrayOf(
            (2.0f * kotlin.math.PI * (i % segU) / segU).toFloat(),
            (kotlin.math.PI * (j % segV) / segV).toFloat())
        for (j in 0 until segV) {
            for (i in 0 until segU) {
                val a = pt(i, j); val b = pt(i + 1, j); val c = pt(i + 1, j + 1); val d = pt(i, j + 1)
                val na = floatArrayOf(a[0] / r, a[1] / r, a[2] / r)
                val ua = uvOf(i, j); val ub = uvOf(i + 1, j); val uc = uvOf(i + 1, j + 1); val ud = uvOf(i, j + 1)
                m.addTriangleUv(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], na[0], na[1], na[2],
                    ua[0], ua[1], ub[0], ub[1], uc[0], uc[1])
                m.addTriangleUv(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], na[0], na[1], na[2],
                    ua[0], ua[1], uc[0], uc[1], ud[0], ud[1])
            }
        }
        return m
    }

    fun cylinder(seg: Int = 32): Mesh {
        val m = Mesh(); val r = 0.5f; val h = 0.5f
        fun side(i: Int, j: Int, top: Boolean) {
            val a = 2.0f * kotlin.math.PI * i / seg
            val x1 = r * kotlin.math.cos(a).toFloat(); val z1 = r * kotlin.math.sin(a).toFloat()
            val a2 = 2.0f * kotlin.math.PI * (i + 1) / seg
            val x2 = r * kotlin.math.cos(a2).toFloat(); val z2 = r * kotlin.math.sin(a2).toFloat()
            val y = if (top) h else -h
            val nx = kotlin.math.cos(a).toFloat(); val nz = kotlin.math.sin(a).toFloat()
            m.addTriangleUv(x1, y, z1, x2, y, z2, x2, if (top) -h else h, z2, nx, 0f, nz,
                i.toFloat() / seg, if (top) 0f else 1f,
                (i + 1).toFloat() / seg, if (top) 0f else 1f,
                (i + 1).toFloat() / seg, if (top) 1f else 0f)
            m.addTriangleUv(x1, y, z1, x2, if (top) -h else h, z2, x1, if (top) -h else h, z1, nx, 0f, nz,
                i.toFloat() / seg, if (top) 0f else 1f,
                (i + 1).toFloat() / seg, if (top) 1f else 0f,
                i.toFloat() / seg, if (top) 1f else 0f)
        }
        for (i in 0 until seg) { side(i, 0, true); side(i, 0, false) }
        return m
    }

    fun plane(): Mesh {
        val m = Mesh(); val p = 0.5f
        m.addTriangleUv(-p, 0, -p, p, 0, -p, p, 0, p, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f)
        m.addTriangleUv(-p, 0, -p, p, 0, p, -p, 0, p, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 0f)
        return m
    }
}
