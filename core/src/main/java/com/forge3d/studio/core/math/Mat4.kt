package com.forge3d.studio.core.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Mat4 {
    private const val PI = 3.14159265358979323846

    fun identity(m: FloatArray, offset: Int = 0) {
        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = 1f; m[offset + 5] = 1f; m[offset + 10] = 1f; m[offset + 15] = 1f
    }

    fun translate(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {
        m[offset + 12] += m[offset] * x + m[offset + 4] * y + m[offset + 8] * z
        m[offset + 13] += m[offset + 1] * x + m[offset + 5] * y + m[offset + 9] * z
        m[offset + 14] += m[offset + 2] * x + m[offset + 6] * y + m[offset + 10] * z
        m[offset + 15] += m[offset + 3] * x + m[offset + 7] * y + m[offset + 11] * z
    }

    fun scale(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) m[offset + i] *= x
        for (i in 0 until 4) m[offset + 4 + i] *= y
        for (i in 0 until 4) m[offset + 8 + i] *= z
    }

    fun rotate(m: FloatArray, offset: Int, degrees: Float, x: Float, y: Float, z: Float) {
        var len = sqrt(x * x + y * y + z * z)
        if (len < 1e-7f) return
        val ax = x / len; val ay = y / len; val az = z / len
        val rad = degrees * PI / 180.0f
        val s = sin(rad); val c = cos(rad); val t = 1f - c
        val r00 = c + ax * ax * t; val r01 = ax * ay * t - az * s; val r02 = ax * az * t + ay * s
        val r10 = ay * ax * t + az * s; val r11 = c + ay * ay * t; val r12 = ay * az * t - ax * s
        val r20 = az * ax * t - ay * s; val r21 = az * ay * t + ax * s; val r22 = c + az * az * t
        for (i in 0 until 4) {
            val base = offset + i * 4
            val m0 = m[base]; val m1 = m[base + 1]; val m2 = m[base + 2]; val m3 = m[base + 3]
            m[base] = m0 * r00 + m1 * r10 + m2 * r20
            m[base + 1] = m0 * r01 + m1 * r11 + m2 * r21
            m[base + 2] = m0 * r02 + m1 * r12 + m2 * r22
            m[base + 3] = m3
        }
    }

    fun multiply(result: FloatArray, roff: Int, lhs: FloatArray, loff: Int, rhs: FloatArray, roff2: Int) {
        val tmp = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += lhs[loff + k * 4 + i] * rhs[roff2 + j * 4 + k]
            tmp[j * 4 + i] = sum
        }
        System.arraycopy(tmp, 0, result, roff, 16)
    }

    fun transformPoint(m: FloatArray, offset: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[offset] * x + m[offset + 4] * y + m[offset + 8] * z + m[offset + 12]
        out[1] = m[offset + 1] * x + m[offset + 5] * y + m[offset + 9] * z + m[offset + 13]
        out[2] = m[offset + 2] * x + m[offset + 6] * y + m[offset + 10] * z + m[offset + 14]
    }

    fun lookAt(m: FloatArray, offset: Int,
               ex: Float, ey: Float, ez: Float,
               cx: Float, cy: Float, cz: Float,
               ux: Float, uy: Float, uz: Float) {
        var fx = ex - cx; var fy = ey - cy; var fz = ez - cz
        val rl = 1f / sqrt(fx * fx + fy * fy + fz * fz)
        fx *= rl; fy *= rl; fz *= rl
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val rl2 = 1f / sqrt(sx * sx + sy * sy + sz * sz)
        sx *= rl2; sy *= rl2; sz *= rl2
        val uux = sy * fz - sz * fy; val uuy = sz * fx - sx * fz; val uuz = sx * fy - sy * fx
        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = sx; m[offset + 1] = uux; m[offset + 2] = -fx
        m[offset + 4] = sy; m[offset + 5] = uuy; m[offset + 6] = -fy
        m[offset + 8] = sz; m[offset + 9] = uuz; m[offset + 10] = -fz
        m[offset + 12] = -(sx * ex + sy * ey + sz * ez)
        m[offset + 13] = -(uux * ex + uuy * ey + uuz * ez)
        m[offset + 14] = fx * ex + fy * ey + fz * ez
        m[offset + 15] = 1f
    }

    fun perspective(m: FloatArray, offset: Int, fovy: Float, aspect: Float, near: Float, far: Float) {
        val f = 1.0f / tan(fovy * PI / 360.0f)
        for (i in 0 until 16) m[offset + i] = 0f
        m[offset] = f / aspect
        m[offset + 5] = f
        m[offset + 10] = (far + near) / (near - far)
        m[offset + 11] = -1f
        m[offset + 14] = (2f * far * near) / (near - far)
    }

    fun invert(m: FloatArray, offset: Int, inv: FloatArray, ioff: Int): Boolean {
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
        val id = 1f / det
        inv[ioff] = (m11 * c5 - m12 * c4 + m13 * c3) * id
        inv[ioff + 1] = (-m01 * c5 + m02 * c4 - m03 * c3) * id
        inv[ioff + 2] = (m31 * s5 - m32 * s4 + m33 * s3) * id
        inv[ioff + 3] = (-m21 * s5 + m22 * s4 - m23 * s3) * id
        inv[ioff + 4] = (-m10 * c5 + m12 * c2 - m13 * c1) * id
        inv[ioff + 5] = (m00 * c5 - m02 * c2 + m03 * c1) * id
        inv[ioff + 6] = (-m30 * s5 + m32 * s2 - m33 * s1) * id
        inv[ioff + 7] = (m20 * s5 - m22 * s2 + m23 * s1) * id
        inv[ioff + 8] = (m10 * c4 - m11 * c2 + m13 * c0) * id
        inv[ioff + 9] = (-m00 * c4 + m01 * c2 - m03 * c0) * id
        inv[ioff + 10] = (m30 * s4 - m31 * s2 + m33 * s0) * id
        inv[ioff + 11] = (-m20 * s4 + m21 * s2 - m23 * s0) * id
        inv[ioff + 12] = (-m10 * c3 + m11 * c1 - m12 * c0) * id
        inv[ioff + 13] = (m00 * c3 - m01 * c1 + m02 * c0) * id
        inv[ioff + 14] = (-m30 * s3 + m31 * s1 - m32 * s0) * id
        inv[ioff + 15] = (m20 * s3 - m21 * s1 + m22 * s0) * id
        return true
    }

    fun equalsApprox(a: FloatArray, b: FloatArray, eps: Float = 1e-4f): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (abs(a[i] - b[i]) > eps) return false
        return true
    }
}
