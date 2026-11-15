package com.forge3d.studio.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Matrices 4x4 en formato column-major (igual que OpenGL) y operaciones TRS.
 *
 * Sustituye a `android.opengl.Matrix` en el núcleo del motor. Motivo: `android.opengl.Matrix`
 * no está disponible en las pruebas unitarias de la JVM (el stub del SDK lanza
 * "Method not mocked"), lo que hacía imposible testear la jerarquía de huesos y el skinning.
 * Con esta implementación, `engine/` es 100% Java puro y testeable sin emulador.
 */
object Mat4 {

    /** Pone la identidad en `m` a partir de [offset]. */
    fun identity(m: FloatArray, offset: Int = 0) {
        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = 1f
        m[offset + 5] = 1f
        m[offset + 10] = 1f
        m[offset + 15] = 1f
    }

    /**
     * `m = m * T(x,y,z)`.
     *
     * En formato column-major la traslación solo afecta a la cuarta columna (índices 12..15),
     * igual que hace `android.opengl.Matrix.translateM`.
     */
    fun translate(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {
        m[offset + 12] += m[offset] * x + m[offset + 4] * y + m[offset + 8] * z
        m[offset + 13] += m[offset + 1] * x + m[offset + 5] * y + m[offset + 9] * z
        m[offset + 14] += m[offset + 2] * x + m[offset + 6] * y + m[offset + 10] * z
        m[offset + 15] += m[offset + 3] * x + m[offset + 7] * y + m[offset + 11] * z
    }

    /**
     * `m = m * S(x,y,z)`.
     *
     * Escala cada columna por su eje: la columna 0 por x, la 1 por y, la 2 por z. La cuarta
     * columna (w) no se toca, igual que `android.opengl.Matrix.scaleM`.
     */
    fun scale(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) m[offset + i] *= x
        for (i in 0 until 4) m[offset + 4 + i] *= y
        for (i in 0 until 4) m[offset + 8 + i] *= z
    }

    /**
     * `m = m * R(eje, grados)`.
     * El eje se normaliza; si es nulo (0,0,0) no se rota.
     */
    fun rotate(m: FloatArray, offset: Int, degrees: Float, x: Float, y: Float, z: Float) {
        var len = sqrt(x * x + y * y + z * z)
        if (len < 1e-7f) return
        val ax = x / len
        val ay = y / len
        val az = z / len
        val rad = degrees * PI / 180.0
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        val t = 1f - c

        // R = I + s*K + t*K*K, con K la matriz antisimétrica del eje.
        val r00 = c + ax * ax * t
        val r01 = ax * ay * t - az * s
        val r02 = ax * az * t + ay * s
        val r10 = ay * ax * t + az * s
        val r11 = c + ay * ay * t
        val r12 = ay * az * t - ax * s
        val r20 = az * ax * t - ay * s
        val r21 = az * ay * t + ax * s
        val r22 = c + az * az * t

        // m = m * R  (column-major)
        for (i in 0 until 4) {
            val base = offset + i * 4
            val m0 = m[base]; val m1 = m[base + 1]; val m2 = m[base + 2]; val m3 = m[base + 3]
            m[base] = m0 * r00 + m1 * r10 + m2 * r20
            m[base + 1] = m0 * r01 + m1 * r11 + m2 * r21
            m[base + 2] = m0 * r02 + m1 * r12 + m2 * r22
            m[base + 3] = m3
        }
    }

    /** `result = lhs * rhs` (column-major). */
    fun multiply(
        result: FloatArray, resultOffset: Int,
        lhs: FloatArray, lhsOffset: Int,
        rhs: FloatArray, rhsOffset: Int
    ) {
        val tmp = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += lhs[lhsOffset + k * 4 + i] * rhs[rhsOffset + j * 4 + k]
                }
                tmp[j * 4 + i] = sum
            }
        }
        System.arraycopy(tmp, 0, result, resultOffset, 16)
    }

    /**
     * Transforma un punto (x,y,z,1) con la matriz y escribe el resultado en [out] (3).
     */
    fun transformPoint(m: FloatArray, offset: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[offset] * x + m[offset + 4] * y + m[offset + 8] * z + m[offset + 12]
        out[1] = m[offset + 1] * x + m[offset + 5] * y + m[offset + 9] * z + m[offset + 13]
        out[2] = m[offset + 2] * x + m[offset + 6] * y + m[offset + 10] * z + m[offset + 14]
    }

    /** `true` si las dos matrices son iguales dentro de la tolerancia dada. */
    fun equalsApprox(a: FloatArray, b: FloatArray, eps: Float = 1e-4f): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (abs(a[i] - b[i]) > eps) return false
        return true
    }

    /**
     * Matriz de vista (look-at) con el eje `up` dado.
     * Equivalente a `android.opengl.Matrix.setLookAtM`.
     */
    fun lookAt(
        m: FloatArray, offset: Int,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ) {
        // f = eye - center (apunta hacia atrás)
        var fx = eyeX - centerX
        var fy = eyeY - centerY
        var fz = eyeZ - centerZ
        var rl = 1f / sqrt(fx * fx + fy * fy + fz * fz)
        fx *= rl; fy *= rl; fz *= rl

        // s = normalize(f x up)
        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        rl = 1f / sqrt(sx * sx + sy * sy + sz * sz)
        sx *= rl; sy *= rl; sz *= rl

        // u = s x f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = sx; m[offset + 1] = ux; m[offset + 2] = -fx
        m[offset + 4] = sy; m[offset + 5] = uy; m[offset + 6] = -fy
        m[offset + 8] = sz; m[offset + 9] = uz; m[offset + 10] = -fz
        m[offset + 12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        m[offset + 13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[offset + 14] = fx * eyeX + fy * eyeY + fz * eyeZ
        m[offset + 15] = 1f
    }

    /**
     * Matriz de proyección perspectiva.
     * Equivalente a `android.opengl.Matrix.perspectiveM`.
     */
    fun perspective(
        m: FloatArray, offset: Int,
        fovyDegrees: Float, aspect: Float, near: Float, far: Float
    ) {
        val f = (1.0 / tan(fovyDegrees * PI / 180.0 / 2.0)).toFloat()
        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = f / aspect
        m[offset + 5] = f
        m[offset + 10] = (far + near) / (near - far)
        m[offset + 11] = -1f
        m[offset + 14] = (2f * far * near) / (near - far)
        m[offset + 15] = 0f
    }

    /**
     * Invierte una matriz 4x4 (adjugado + determinante).
     * Equivalente a `android.opengl.Matrix.invertM`.
     *
     * @return `true` si la matriz es invertible.
     */
    fun invert(m: FloatArray, offset: Int, inv: FloatArray, invOffset: Int): Boolean {
        val m00 = m[offset]; val m01 = m[offset + 1]; val m02 = m[offset + 2]; val m03 = m[offset + 3]
        val m10 = m[offset + 4]; val m11 = m[offset + 5]; val m12 = m[offset + 6]; val m13 = m[offset + 7]
        val m20 = m[offset + 8]; val m21 = m[offset + 9]; val m22 = m[offset + 10]; val m23 = m[offset + 11]
        val m30 = m[offset + 12]; val m31 = m[offset + 13]; val m32 = m[offset + 14]; val m33 = m[offset + 15]

        val s0 = m00 * m11 - m01 * m10
        val s1 = m00 * m12 - m02 * m10
        val s2 = m00 * m13 - m03 * m10
        val s3 = m01 * m12 - m02 * m11
        val s4 = m01 * m13 - m03 * m11
        val s5 = m02 * m13 - m03 * m12

        val c5 = m22 * m33 - m32 * m23
        val c4 = m21 * m33 - m31 * m23
        val c3 = m21 * m32 - m31 * m22
        val c2 = m20 * m33 - m30 * m23
        val c1 = m20 * m32 - m30 * m22
        val c0 = m20 * m31 - m30 * m21

        val det = s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0
        if (det == 0f) return false
        val invDet = 1f / det

        inv[invOffset] = (m11 * c5 - m12 * c4 + m13 * c3) * invDet
        inv[invOffset + 1] = (-m01 * c5 + m02 * c4 - m03 * c3) * invDet
        inv[invOffset + 2] = (m31 * s5 - m32 * s4 + m33 * s3) * invDet
        inv[invOffset + 3] = (-m21 * s5 + m22 * s4 - m23 * s3) * invDet
        inv[invOffset + 4] = (-m10 * c5 + m12 * c2 - m13 * c1) * invDet
        inv[invOffset + 5] = (m00 * c5 - m02 * c2 + m03 * c1) * invDet
        inv[invOffset + 6] = (-m30 * s5 + m32 * s2 - m33 * s1) * invDet
        inv[invOffset + 7] = (m20 * s5 - m22 * s2 + m23 * s1) * invDet
        inv[invOffset + 8] = (m10 * c4 - m11 * c2 + m13 * c0) * invDet
        inv[invOffset + 9] = (-m00 * c4 + m01 * c2 - m03 * c0) * invDet
        inv[invOffset + 10] = (m30 * s4 - m31 * s2 + m33 * s0) * invDet
        inv[invOffset + 11] = (-m20 * s4 + m21 * s2 - m23 * s0) * invDet
        inv[invOffset + 12] = (-m10 * c3 + m11 * c1 - m12 * c0) * invDet
        inv[invOffset + 13] = (m00 * c3 - m01 * c1 + m02 * c0) * invDet
        inv[invOffset + 14] = (-m30 * s3 + m31 * s1 - m32 * s0) * invDet
        inv[invOffset + 15] = (m20 * s3 - m21 * s1 + m22 * s0) * invDet
        return true
    }

    private const val PI = 3.14159265358979323846
}
