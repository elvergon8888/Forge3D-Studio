package com.forge3d.studio.core.scene

/**
 * Objeto de escena con transformación TRS, malla compartida (refcount) y material propio.
 */
class SceneObject(
    val id: String,
    var name: String,
    mesh: Mesh,
    val material: Material = Material()
) {
    var mesh: Mesh = mesh
        set(value) {
            val vieja = field
            field = value
            if (vieja !== value) {
                vieja.users--
                if (vieja.users <= 0) vieja.needsUpload = false
            }
        }

    val position = floatArrayOf(0f, 0f, 0f)
    val rotation = floatArrayOf(0f, 0f, 0f)
    val scale = floatArrayOf(1f, 1f, 1f)
    var visible = true
    var tipo = "primitiva"
    var primitiva: String? = null

    private val tmpModel = FloatArray(16)

    fun modelMatrix(out: FloatArray) {
        com.forge3d.studio.core.math.Mat4.identity(out)
        com.forge3d.studio.core.math.Mat4.translate(out, 0, position[0], position[1], position[2])
        com.forge3d.studio.core.math.Mat4.rotate(out, 0, rotation[0], 1f, 0f, 0f)
        com.forge3d.studio.core.math.Mat4.rotate(out, 0, rotation[1], 0f, 1f, 0f)
        com.forge3d.studio.core.math.Mat4.rotate(out, 0, rotation[2], 0f, 0f, 1f)
        com.forge3d.studio.core.math.Mat4.scale(out, 0, scale[0], scale[1], scale[2])
    }

    fun worldBounds(out: FloatArray): FloatArray {
        modelMatrix(tmpModel)
        mesh.transformedBounds(tmpModel, out)
        return out
    }
}

private fun Mesh.transformedBounds(m: FloatArray, out: FloatArray): FloatArray {
    var a0 = Float.MAX_VALUE; var a1 = Float.MAX_VALUE; var a2 = Float.MAX_VALUE
    var a3 = -Float.MAX_VALUE; var a4 = -Float.MAX_VALUE; var a5 = -Float.MAX_VALUE
    val p = FloatArray(4)
    var i = 0
    while (i < used) {
        for (k in 0 until 3) {
            p[0] = pos[i + k * 3]; p[1] = pos[i + k * 3 + 1]; p[2] = pos[i + k * 3 + 2]; p[3] = 1f
            val x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12] * p[3]
            val y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13] * p[3]
            val z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14] * p[3]
            a0 = min(a0, x); a1 = min(a1, y); a2 = min(a2, z)
            a3 = max(a3, x); a4 = max(a4, y); a5 = max(a5, z)
        }
        i += 9
    }
    out[0] = a0; out[1] = a1; out[2] = a2; out[3] = a3; out[4] = a4; out[5] = a5
    return out
}
