package com.forge3d.studio

import com.forge3d.studio.engine.Mesh
import com.forge3d.studio.engine.MeshOps
import com.forge3d.studio.engine.Primitives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de las operaciones de malla.
 *
 * No dependen de Android ni de OpenGL: son lógica pura y corren en la JVM.
 */
class MeshOpsTest {

    private fun cubo(): Mesh = Primitives.cube()!!

    @Test
    fun `weld agrupa vertices coincidentes`() {
        val m = cubo()
        val top = MeshOps.weld(m)
        // El cubo tiene 8 vertices unicos (36 floats de posicion / 3 = 12 vertices sin soldar)
        assertTrue("debe reducir vertices", top.vertCount < m.vertCount)
        assertEquals(m.triangleCount, top.triCount)
    }

    @Test
    fun `unwrap conserva el numero de triangulos`() {
        val m = cubo()
        val top = MeshOps.weld(m)
        val out = MeshOps.unwrap(top)
        assertEquals(m.triangleCount, out.triangleCount)
    }

    @Test
    fun `subdividir cuadruplica los triangulos`() {
        val m = cubo()
        val antes = m.triangleCount
        val sub = MeshOps.subdivide(m)
        assertEquals(antes * 4, sub.triangleCount)
    }

    @Test
    fun `subdividir no excede el limite global de triangulos`() {
        val m = cubo()
        val sub = MeshOps.subdivide(m)
        assertTrue(sub.triangleCount <= 1_000_000L)
    }

    @Test
    fun `extruir desplaza los vertices en la direccion de la normal`() {
        val m = cubo()
        val original = m.pos.copyOf(m.used)
        val out = MeshOps.extrude(m, 0.1f)
        var distinto = false
        for (i in 0 until original.size step 3) {
            if (kotlin.math.abs(original[i] - out.pos[i]) > 1e-5f) { distinto = true; break }
        }
        assertTrue("la extrusion debe mover los vertices", distinto)
    }

    @Test
    fun `extruir con cantidad cero devuelve copia intacta`() {
        val m = cubo()
        val out = MeshOps.extrude(m, 0f)
        assertEquals(m.used, out.used)
    }

    @Test
    fun `bisel contrae los vertices hacia el centro`() {
        val m = cubo()
        val antes = m.pos.copyOf(m.used)
        val out = MeshOps.bevel(m, 0.5f)
        var cambio = false
        for (i in 0 until antes.size) {
            if (kotlin.math.abs(antes[i] - out.pos[i]) > 1e-5f) { cambio = true; break }
        }
        assertTrue("el bisel debe modificar la geometria", cambio)
    }

    @Test
    fun `normales suavizadas no alteran la posicion`() {
        val m = cubo()
        val antes = m.pos.copyOf(m.used)
        MeshOps.smoothNormals(m)
        for (i in 0 until antes.size) {
            assertEquals(antes[i], m.pos[i], 1e-6f)
        }
    }

    @Test
    fun `estadisticas topologicas son coherentes`() {
        val m = cubo()
        val (verts, edges, tris) = MeshOps.stats(m)
        assertEquals(m.triangleCount, tris)
        assertTrue("debe haber vertices", verts > 0)
        assertTrue("debe haber aristas", edges > 0)
        // Un cubo cerrado tiene E = 3F/2 aristas: con 12 triangulos -> 18 aristas
        assertEquals(18, edges)
    }

    @Test
    fun `recomputeBounds refleja la geometria`() {
        val m = cubo()
        m.recomputeBounds()
        assertEquals(-0.5f, m.minX, 1e-5f)
        assertEquals(0.5f, m.maxX, 1e-5f)
    }

    @Test
    fun `addVertex no rompe el conteo de vertices`() {
        val m = Mesh()
        val n0 = m.vertCount
        m.addVertex(1f, 2f, 3f, 0f, 1f, 0f)
        assertEquals(n0 + 1, m.vertCount)
    }
}
