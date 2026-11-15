package com.forge3d.studio.engine



object Limits {
    const val MAX_OBJECTS = 300
    const val MAX_TRIS = 1_000_000L
}

class SceneObject(
    val id: String,
    var name: String,
    mesh: Mesh,
    val material: Material = Material()
) {
    /**
     * Malla del objeto.
     *
     * Es `var` porque las operaciones de edición (subdividir, extruir, bisel, soldar) generan
     * una malla nueva. Al reasignar se mantiene el refcount: la malla anterior solo se libera
     * (y se encola para `glDeleteBuffers`) cuando su último usuario desaparece.
     */
    var mesh: Mesh = mesh
        set(value) {
            val vieja = field
            field = value
            if (vieja !== value) {
                vieja.users--
                if (vieja.users <= 0) {
                    vieja.needsUpload = false
                    // El renderer borrará el VBO cuando le llegue la cola de liberación.
                    com.forge3d.studio.mcp.AppModel.meshDeleteQueue.add(vieja)
                }
            }
        }

    val position = floatArrayOf(0f, 0f, 0f)   // metros
    val rotation = floatArrayOf(0f, 0f, 0f)   // grados X,Y,Z
    val scale = floatArrayOf(1f, 1f, 1f)
    var visible = true

    /** "primitiva" | "importado" */
    var tipo = "primitiva"

    /** cubo | esfera | cilindro | plano | null (importado) */
    var primitiva: String? = null

    private val tmpModel = FloatArray(16)

    fun modelMatrix(out: FloatArray) {
        Mat4.identity(out, 0)
        Mat4.translate(out, 0, position[0], position[1], position[2])
        Mat4.rotate(out, 0, rotation[0], 1f, 0f, 0f)
        Mat4.rotate(out, 0, rotation[1], 0f, 1f, 0f)
        Mat4.rotate(out, 0, rotation[2], 0f, 0f, 1f)
        Mat4.scale(out, 0, scale[0], scale[1], scale[2])
    }

    /** AABB mundo en [out] (6). */
    fun worldBounds(out: FloatArray): FloatArray {
        modelMatrix(tmpModel)
        mesh.transformedBounds(tmpModel, out)
        return out
    }
}

class Scene {

    val objects = ArrayList<SceneObject>()
    @Volatile var selected: String? = null
    private var counter = 0
    private val typeCount = HashMap<String, Int>()

    @Synchronized
    fun nextId(): String {
        counter++
        return "obj_%04d".format(counter)
    }

    @Synchronized
    fun autoName(tipo: String): String {
        val n = (typeCount[tipo] ?: 0) + 1
        typeCount[tipo] = n
        val label = when (tipo) {
            Primitives.CUBE -> "Cubo"
            Primitives.SPHERE -> "Esfera"
            Primitives.CYLINDER -> "Cilindro"
            Primitives.PLANE -> "Plano"
            else -> "Modelo"
        }
        return "${label}_$n"
    }

    @Synchronized
    fun totalTris(): Long {
        var t = 0L
        for (o in objects) t += o.mesh.triangleCount
        return t
    }

    @Synchronized
    fun byId(id: String?): SceneObject? {
        if (id == null) return null
        for (o in objects) if (o.id == id) return o
        return null
    }

    @Synchronized
    fun canAdd(tris: Long): Boolean =
        objects.size < Limits.MAX_OBJECTS && totalTris() + tris <= Limits.MAX_TRIS

    @Synchronized
    fun add(obj: SceneObject): Boolean {
        if (objects.size >= Limits.MAX_OBJECTS) return false
        if (totalTris() + obj.mesh.triangleCount > Limits.MAX_TRIS) return false
        objects.add(obj)
        return true
    }

    @Synchronized
    fun remove(id: String): SceneObject? {
        val it = objects.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (o.id == id) {
                it.remove()
                if (selected == id) selected = null
                o.mesh.users--
                return o
            }
        }
        return null
    }

    @Synchronized
    fun duplicate(id: String): SceneObject? {
        val src = byId(id) ?: return null
        if (objects.size >= Limits.MAX_OBJECTS) return null
        if (totalTris() + src.mesh.triangleCount > Limits.MAX_TRIS) return null
        val mesh = src.mesh
        mesh.users++
        val obj = SceneObject(nextId(), src.name + "_copia", mesh, src.material.copy())
        obj.tipo = src.tipo
        obj.primitiva = src.primitiva
        for (i in 0..2) {
            obj.position[i] = src.position[i]
            obj.rotation[i] = src.rotation[i]
            obj.scale[i] = src.scale[i]
        }
        obj.position[0] += 0.25f
        objects.add(obj)
        return obj
    }

    @Synchronized
    fun clear() {
        for (o in objects) o.mesh.users--
        objects.clear()
        selected = null
    }

    /** AABB mundo de toda la escena, o false si vacía. */
    @Synchronized
    fun worldAabb(out: FloatArray): Boolean {
        if (objects.isEmpty()) return false
        var first = true
        val b = FloatArray(6)
        for (o in objects) {
            if (!o.visible) continue
            o.worldBounds(b)
            if (first) {
                for (i in 0..2) out[i] = b[i]
                for (i in 3..5) out[i] = b[i]
                first = false
            } else {
                for (i in 0..2) out[i] = minOf(out[i], b[i])
                for (i in 3..5) out[i] = maxOf(out[i], b[i])
            }
        }
        return !first
    }
}
