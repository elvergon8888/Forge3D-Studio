package com.forge3d.studio.core

import com.forge3d.studio.core.math.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mat4Test {
    @Test fun translate_displaces_only_fourth_column() {
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
    @Test fun scale_does_not_throw_array_index_out_of_bounds() {
        val m = FloatArray(16); Mat4.identity(m); Mat4.scale(m, 0, 2f, 2f, 2f); Mat4.translate(m, 0, 5f, 5f, 5f); Mat4.rotate(m, 0, 45f, 0f, 1f, 0f)
    }
    @Test fun multiply_composes_in_correct_order() {
        val t = FloatArray(16); Mat4.identity(t); Mat4.translate(t, 0, 10f, 0f, 0f)
        val s = FloatArray(16); Mat4.identity(s); Mat4.scale(s, 0, 2f, 2f, 2f)
        val ts = FloatArray(16); Mat4.multiply(ts, 0, t, 0, s, 0); val out = FloatArray(3); Mat4.transformPoint(ts, 0, 1f, 0f, 0f, out); assertEquals(12f, out[0], 1e-4f)
        val st = FloatArray(16); Mat4.multiply(st, 0, s, 0, t, 0); Mat4.transformPoint(st, 0, 1f, 0f, 0f, out); assertEquals(22f, out[0], 1e-4f)
    }
}
