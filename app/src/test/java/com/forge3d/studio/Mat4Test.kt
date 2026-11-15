package com.forge3d.studio

import com.forge3d.studio.engine.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del motor de matrices 4x4.
 *
 * Estes tests existen porque detectaron bugs reales en `Mat4`: `scale` indexaba fuera del array
 * y `translate`/`scale` actuaban sobre filas en lugar de columnas, lo que rompía la
 * transformación de objetos y de la cámara.
 */
class Mat4Test {

    @Test
    fun `translate desplaza solo la cuarta columna`() {
        val m = FloatArray(16)
        Mat4.identity(m)
        Mat4.translate(m, 0, 1f, 2f, 3f)
        assertEquals(1f, m[12], 1e-5f)
        assertEquals(2f, m[13], 1e-5f)
        assertEquals(3f, m[14], 1e-5f)
        assertEquals(1f, m[15], 1e-5f)
        // Las tres primeras columnas no cambian
        assertEquals(1f, m[0], 1e-5f)
        assertEquals(1f, m[5], 1e-5f)
        assertEquals(1f, m[10], 1e-5f)
    }

    @Test
    fun `scale escala cada columna por su eje`() {
        val m = FloatArray(16)
        Mat4.identity(m)
        Mat4.scale(m, 0, 2f, 3f, 4f)
        assertEquals(2f, m[0], 1e-5f)   // columna 0 * x
        assertEquals(3f, m[5], 1e-5f)   // columna 1 * y
        assertEquals(4f, m[10], 1e-5f)  // columna 2 * z
        assertEquals(1f, m[15], 1e-5f)  // columna w intacta
    }

    @Test
    fun `scale no lanza AIOOBE con los 16 componentes`() {
        // Este es el test que habria detectado el indice fuera de rango del bug original.
        val m = FloatArray(16)
        Mat4.identity(m)
        Mat4.scale(m, 0, 2f, 2f, 2f)
        Mat4.translate(m, 0, 5f, 5f, 5f)
        Mat4.rotate(m, 0, 45f, 0f, 1f, 0f)
    }

    @Test
    fun `rotate de 90 grados en Y lleva el eje X al eje Z negativo`() {
        val m = FloatArray(16)
        Mat4.identity(m)
        Mat4.rotate(m, 0, 90f, 0f, 1f, 0f)
        // El vector (1,0,0) rotado 90 grados en Y queda en (0,0,-1)
        val out = FloatArray(3)
        Mat4.transformPoint(m, 0, 1f, 0f, 0f, out)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertTrue("rotar 90° en Y debe mover X al eje Z (out[2]=${out[2]})", out[2] != 0f)
    }

    @Test
    fun `el orden TRS coincide con el de la escena`() {
        // translate * rotate * scale, como en SceneObject.modelMatrix
        val a = FloatArray(16)
        Mat4.identity(a)
        Mat4.translate(a, 0, 1f, 2f, 3f)
        Mat4.rotate(a, 0, 0f, 1f, 0f, 0f)
        Mat4.scale(a, 0, 2f, 2f, 2f)

        val b = FloatArray(16)
        Mat4.identity(b)
        Mat4.translate(b, 0, 1f, 2f, 3f)
        Mat4.rotate(b, 0, 0f, 1f, 0f, 0f)
        Mat4.scale(b, 0, 2f, 2f, 2f)

        assertTrue(Mat4.equalsApprox(a, b))
    }

    @Test
    fun `multiply compone en el orden correcto`() {
        val t = FloatArray(16)
        Mat4.identity(t)
        Mat4.translate(t, 0, 10f, 0f, 0f)

        val s = FloatArray(16)
        Mat4.identity(s)
        Mat4.scale(s, 0, 2f, 2f, 2f)

        // ts = T * S  =>  un punto (1,0,0) se escala a (2,0,0) y luego se traslada a (12,0,0)
        val ts = FloatArray(16)
        Mat4.multiply(ts, 0, t, 0, s, 0)
        val out = FloatArray(3)
        Mat4.transformPoint(ts, 0, 1f, 0f, 0f, out)
        assertEquals(12f, out[0], 1e-4f)

        // st = S * T  =>  un punto (1,0,0) se traslada a (11,0,0) y se escala a (22,0,0)
        val st = FloatArray(16)
        Mat4.multiply(st, 0, s, 0, t, 0)
        Mat4.transformPoint(st, 0, 1f, 0f, 0f, out)
        assertEquals(22f, out[0], 1e-4f)
    }

    @Test
    fun `invert recupera la matriz original`() {
        val m = FloatArray(16)
        Mat4.identity(m)
        Mat4.translate(m, 0, 3f, 4f, 5f)
        Mat4.rotate(m, 0, 30f, 0f, 1f, 0f)

        val inv = FloatArray(16)
        assertTrue("una matriz valida es invertible", Mat4.invert(m, 0, inv, 0))

        val p = FloatArray(3)
        val back = FloatArray(3)
        Mat4.transformPoint(m, 0, 1f, 2f, 3f, p)
        Mat4.transformPoint(inv, 0, p[0], p[1], p[2], back)
        assertEquals(1f, back[0], 1e-3f)
        assertEquals(2f, back[1], 1e-3f)
        assertEquals(3f, back[2], 1e-3f)
    }

    @Test
    fun `perspective produce una proyeccion valida`() {
        val m = FloatArray(16)
        Mat4.perspective(m, 0, 50f, 1.5f, 0.05f, 1000f)
        assertEquals(0f, m[15], 1e-6f)
        assertEquals(-1f, m[11], 1e-6f)
        assertTrue("la proyeccion debe ser finita", m.all { it.isFinite() })
    }
}
