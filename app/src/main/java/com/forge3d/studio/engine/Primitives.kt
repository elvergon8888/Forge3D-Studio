package com.forge3d.studio.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Generadores de primitivas (tamaños en metros, centradas en el origen, Y arriba). */
object Primitives {

    const val CUBE = "cubo"
    const val SPHERE = "esfera"
    const val CYLINDER = "cilindro"
    const val PLANE = "plano"

    fun validTypes(): List<String> = listOf(CUBE, SPHERE, CYLINDER, PLANE)

    fun create(type: String): Mesh? = when (type) {
        CUBE -> cube()
        SPHERE -> sphere()
        CYLINDER -> cylinder()
        PLANE -> plane()
        else -> null
    }

    /** Cubo de arista 1 m. */
    fun cube(): Mesh {
        val m = Mesh()
        val n = floatArrayOf(0f, 0f, 0f)
        fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, nx: Float, ny: Float, nz: Float) {
            n[0] = nx; n[1] = ny; n[2] = nz
            m.addTriangle(a, b, c, n)
            m.addTriangle(a, c, d, n)
        }
        val p = 0.5f
        face(floatArrayOf(p, -p, -p), floatArrayOf(p, p, -p), floatArrayOf(p, p, p), floatArrayOf(p, -p, p), 1f, 0f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(-p, p, p), floatArrayOf(-p, p, -p), floatArrayOf(-p, -p, -p), -1f, 0f, 0f)
        face(floatArrayOf(-p, p, -p), floatArrayOf(-p, p, p), floatArrayOf(p, p, p), floatArrayOf(p, p, -p), 0f, 1f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(-p, -p, -p), floatArrayOf(p, -p, -p), floatArrayOf(p, -p, p), 0f, -1f, 0f)
        face(floatArrayOf(-p, -p, p), floatArrayOf(p, -p, p), floatArrayOf(p, p, p), floatArrayOf(-p, p, p), 0f, 0f, 1f)
        face(floatArrayOf(p, -p, -p), floatArrayOf(-p, -p, -p), floatArrayOf(-p, p, -p), floatArrayOf(p, p, -p), 0f, 0f, -1f)
        return m
    }

    /** Esfera UV de radio 0.5 m. */
    fun sphere(segU: Int = 32, segV: Int = 20): Mesh {
        val m = Mesh()
        val r = 0.5f
        val n = floatArrayOf(0f, 0f, 0f)
        fun pt(i: Int, j: Int): FloatArray {
            val phi = 2.0 * PI * i / segU
            val theta = PI * j / segV
            val x = (r * sin(theta) * cos(phi)).toFloat()
            val y = (r * cos(theta)).toFloat()
            val z = (r * sin(theta) * sin(phi)).toFloat()
            return floatArrayOf(x, y, z)
        }
        // UVs esféricas: u = phi/(2PI), v = theta/PI
        fun uvOf(i: Int, j: Int): FloatArray {
            val u = (2.0 * PI * (i % segU) / segU).toFloat()
            val v = (PI * (j % segV) / segV).toFloat()
            return floatArrayOf(u, v)
        }
        for (j in 0 until segV) {
            for (i in 0 until segU) {
                val a = pt(i, j)
                val b = pt(i + 1, j)
                val c = pt(i + 1, j + 1)
                val d = pt(i, j + 1)
                n[0] = a[0] / r; n[1] = a[1] / r; n[2] = a[2] / r
                val ua = uvOf(i, j); val ub = uvOf(i + 1, j)
                val uc = uvOf(i + 1, j + 1); val ud = uvOf(i, j + 1)
                m.addTriangleUv(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2],
                    n[0], n[1], n[2], ua[0], ua[1], ub[0], ub[1], uc[0], uc[1])
                m.addTriangleUv(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2],
                    n[0], n[1], n[2], ua[0], ua[1], uc[0], uc[1], ud[0], ud[1])
            }
        }
        return m
    }

    /** Cilindro radio 0.5 m, altura 1 m (Y de -0.5 a 0.5), con tapas. */
    fun cylinder(seg: Int = 32): Mesh {
        val m = Mesh()
        val r = 0.5f
        val n = floatArrayOf(0f, 0f, 0f)
        for (i in 0 until seg) {
            val a0 = 2.0 * PI * i / seg
            val a1 = 2.0 * PI * (i + 1) / seg
            val x0 = (r * cos(a0)).toFloat(); val z0 = (r * sin(a0)).toFloat()
            val x1 = (r * cos(a1)).toFloat(); val z1 = (r * sin(a1)).toFloat()
            // lateral
            n[0] = (cos(a0) + cos(a1)).toFloat() * 0.5f; n[1] = 0f; n[2] = (sin(a0) + sin(a1)).toFloat() * 0.5f
            Math3D.normalize(n)
            m.addTriangle(x0, -r, z0, x1, -r, z1, x1, r, z1, n[0], n[1], n[2])
            m.addTriangle(x0, -r, z0, x1, r, z1, x0, r, z0, n[0], n[1], n[2])
            // tapa superior
            n[0] = 0f; n[1] = 1f; n[2] = 0f
            m.addTriangle(0f, r, 0f, x0, r, z0, x1, r, z1, 0f, 1f, 0f)
            // tapa inferior
            m.addTriangle(0f, -r, 0f, x1, -r, z1, x0, -r, z0, 0f, -1f, 0f)
        }
        return m
    }

    /** Plano de 1 x 1 m en XZ (y = 0). */
    fun plane(): Mesh {
        val m = Mesh()
        val n = floatArrayOf(0f, 1f, 0f)
        m.addTriangle(-0.5f, 0f, -0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, 0f, 1f, 0f)
        m.addTriangle(-0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0f, 1f, 0f)
        return m
    }
}
