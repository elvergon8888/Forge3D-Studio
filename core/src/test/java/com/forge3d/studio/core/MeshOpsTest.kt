package com.forge3d.studio.core

import com.forge3d.studio.core.math.Mat4
import com.forge3d.studio.core.ops.MeshOps
import com.forge3d.studio.core.primitives.Primitives
import com.forge3d.studio.core.scene.Mesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mat4Test {
    @Test fun translate_moves_only_fourth_column() {
        val m = FloatArray(16); Mat4.identity(m); Mat4.translate(m, 0, 1f, 2f, 3f)
        assertEquals(1f, m[12], 1e-5f); assertEquals(2f, m[13], 1e-5f); assertEquals(3f, m[14], 1e-5f)
        assertEquals(1f, m[15], 1e-5f); assertEquals(1f, m[0], 1e-5f); assertEquals(1f, m[5], 1e-5f); assertEquals(1f, m[10], 1e-5f)
    }
    @Test fun scale_scales_each_column_by_axis() {
        val m = FloatArray(16); Mat4.identity(m); Mat4.scale(m, 0, 2f, 3f, 4f)
        assertEquals(2f, m[0], 1e-5f); assertEquals(3f, m[5], 1e-5f); assertEquals(4f, m[10], 1e-5f); assertEquals(1f, m[15], 1e-5f)
    }
    @Test fun rotate_90_degrees_in_y_moves_x_to_z() {
        val m = FloatArray(16); Mat4.identity(m); Mat4.rotate(m, 0, 90f, 0f, 1f, 0f)
        val out = FloatArray(3); Mat4.transformPoint(m, 0, 1f, 0f, 0f, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f); assertTrue("abs(out[2])>0.5", abs(out[2]) > 0.5f)
    }
    @Test fun invert_recovers_original_matrix() {
        val m = FloatArray(16); Mat4.identity(m); Mat4.translate(m, 0, 3f, 4f, 5f); Mat4.rotate(m, 0, 30f, 0f, 1f, 0f)
        val inv = FloatArray(16); assertTrue(Mat4.invert(m, 0, inv, 0))
        val p = FloatArray(3); val back = FloatArray(3)
        Mat4.transformPoint(m, 0, 1f, 2f, 3f, p); Mat4.transformPoint(inv, 0, p[0], p[1], p[2], back)
        assertEquals(1f, back[0], 1e-3f); assertEquals(2f, back[1], 1e-3f); assertEquals(3f, back[2], 1e-3f)
    }
    @Test fun perspective_produces_valid_projection() {
        val m = FloatArray(16); Mat4.perspective(m, 0, 50f, 1.5f, 0.05f, 1000f)
        assertEquals(0f, m[15], 1e-6f); assertEquals(-1f, m[11], 1e-6f); assertTrue(m.all { it.isFinite() })
    }
}

class MeshOpsTest {
    private fun cubo(): Mesh = Primitives.cube()!!
    @Test fun weld_groups_coincident_vertices() {
        val m = cubo(); val top = MeshOps.weld(m)
        assertTrue("debe reducir vertices", top.vertCount < m.vertCount); assertEquals(m.triangleCount, top.triCount)
    }
    @Test fun unwrap_preserves_triangle_count() {
        val m = cubo(); val top = MeshOps.weld(m); val out = MeshOps.unwrap(top)
        assertEquals(m.triangleCount, out.triangleCount)
    }
    @Test fun subdivide_quadruples_triangles() {
        val m = cubo(); val antes = m.triangleCount; val sub = MeshOps.subdivide(m)
        assertEquals(antes * 4, sub.triangleCount); assertTrue(sub.triangleCount <= 1_000_000L)
    }
    @Test fun extrude_displaces_vertices_along_normal() {
        val m = cubo(); val original = m.pos.copyOf(m.used); val out = MeshOps.extrude(m, 0.1f)
        var distinto = false
        for (i in 0 until original.size step 3) if (abs(original[i] - out.pos[i]) > 1e-5f) { distinto = true; break }
        assertTrue("extrusion debe mover vertices", distinto)
    }
    @Test fun extrude_zero_amount_returns_intact_copy() {
        val m = cubo(); val out = MeshOps.extrude(m, 0f); assertEquals(m.used, out.used)
    }
    @Test fun bevel_contracts_vertices_toward_center() {
        val m = cubo(); val antes = m.pos.copyOf(m.used); val out = MeshOps.bevel(m, 0.5f)
        var cambio = false
        for (i in 0 until antes.size) if (abs(antes[i] - out.pos[i]) > 1e-5f) { cambio = true; break }
        assertTrue("el bisel debe modificar geometria", cambio)
    }
    @Test fun smooth_normals_do_not_alter_position() {
        val m = cubo(); val antes = m.pos.copyOf(m.used); MeshOps.smoothNormals(m)
        for (i in 0 until antes.size) assertEquals(antes[i], m.pos[i], 1e-6f)
    }
    @Test fun topological_stats_are_coherent() {
        val m = cubo(); val (verts, edges, tris) = MeshOps.stats(m)
        assertEquals(m.triangleCount, tris); assertTrue(verts > 0); assertTrue(edges > 0); assertEquals(18, edges)
    }
    @Test fun recomputeBounds_reflects_geometry() {
        val m = cubo(); m.recomputeBounds(); assertEquals(-0.5f, m.minX, 1e-5f); assertEquals(0.5f, m.maxX, 1e-5f)
    }
    @Test fun addVertex_does_not_break_vertex_count() {
        val m = Mesh(); val n0 = m.vertCount; m.addVertex(1f, 2f, 3f, 0f, 1f, 0f); assertEquals(n0 + 1, m.vertCount)
    }
    private fun abs(v: Float) = kotlin.math.abs(v)
}
