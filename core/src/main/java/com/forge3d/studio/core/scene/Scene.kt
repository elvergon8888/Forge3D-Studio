package com.forge3d.studio.core.scene

import com.forge3d.studio.core.math.Mat4

object Limits {
    const val MAX_OBJECTS = 300
    const val MAX_TRIS = 1_000_000L
}

/**
 * Escena compartida: lista de objetos, cámara y límites.
 */
class Scene {
    val objects = mutableListOf<SceneObject>()
    var selected: String? = null

    fun nextId(): String = "obj_%04d".format(objects.size + 1)

    fun autoName(tipo: String): String = "${tipo.capitalize()}_${objects.filter { it.tipo == tipo }.size + 1}"

    fun totalTris(): Long = objects.sumOf { it.mesh.triangleCount.toLong() }

    fun byId(id: String): SceneObject? = objects.firstOrNull { it.id == id }

    fun canAdd(tris: Long): Boolean = objects.size < Limits.MAX_OBJECTS && totalTris() + tris <= Limits.MAX_TRIS

    fun add(obj: SceneObject): Boolean {
        if (!canAdd(obj.mesh.triangleCount.toLong())) return false
        objects.add(obj); return true
    }

    fun remove(id: String) { objects.removeIf { it.id == id } }

    fun clear() { objects.clear(); selected = null }

    fun worldAabb(out: FloatArray): Boolean {
        if (objects.isEmpty()) return false
        val first = objects.first()
        first.worldBounds(out)
        for (o in objects.drop(1)) {
            val b = FloatArray(6); o.worldBounds(b)
            out[0] = min(out[0], b[0]); out[1] = min(out[1], b[1]); out[2] = min(out[2], b[2])
            out[3] = max(out[3], b[3]); out[4] = max(out[4], b[4]); out[5] = max(out[5], b[5])
        }
        return true
    }
}

/**
 * Cámara orbital.
 */
class Camera3D {
    val target = floatArrayOf(0f, 0f, 0f)
    var yaw = 0f
    var pitch = 0f
    var distance = 5f
    val eye = floatArrayOf(0f, 0f, 5f)

    fun update() {
        val radY = yaw * kotlin.math.PI / 180.0f
        val radP = pitch * kotlin.math.PI / 180.0f
        eye[0] = target[0] + distance * kotlin.math.cos(radP) * kotlin.math.sin(radY)
        eye[1] = target[1] + distance * kotlin.math.sin(radP)
        eye[2] = target[2] + distance * kotlin.math.cos(radP) * kotlin.math.cos(radY)
    }

    fun orbit(dyaw: Float, dpitch: Float) {
        yaw += dyaw; pitch = (pitch + dpitch).coerceIn(-89f, 89f); update()
    }

    fun zoom(factor: Float) { distance = (distance * factor).coerceIn(0.1f, 100f); update() }

    fun view(m: FloatArray) {
        com.forge3d.studio.core.math.Mat4.lookAt(m, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 1f, 0f)
    }

    fun proj(m: FloatArray, w: Int, h: Int) {
        com.forge3d.studio.core.math.Mat4.perspective(m, 0, 50f, w.toFloat() / h.toFloat(), 0.05f, 1000f)
    }

    fun fit(bounds: FloatArray) {
        val cx = (bounds[0] + bounds[3]) / 2f; val cy = (bounds[1] + bounds[4]) / 2f; val cz = (bounds[2] + bounds[5]) / 2f
        val size = max(max(bounds[3] - bounds[0], bounds[4] - bounds[1]), bounds[5] - bounds[2])
        target.set(cx, cy, cz); distance = size * 1.5f; update()
    }
}

/**
 * Material PBR simplificado (compatible con glTF pbrMetallicRoughness).
 */
class Material(
    var name: String = "Predeterminado",
    var baseColor: FloatArray = floatArrayOf(0.78f, 0.78f, 0.8f, 1f),
    var metallic: Float = 0.05f,
    var roughness: Float = 0.6f,
    var emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    var alphaMode: String = "OPAQUE",
    var alphaCutoff: Float = 0.5f,
    var albedoPath: String? = null,
    var normalPath: String? = null,
    var roughnessPath: String? = null,
    var metalnessPath: String? = null,
    var aoPath: String? = null
) {
    val hasTextures: Boolean get() = albedoPath != null || normalPath != null || roughnessPath != null ||
            metalnessPath != null || aoPath != null

    fun copy(): Material = Material(name, baseColor.copyOf(), metallic, roughness, emissive.copyOf(),
        alphaMode, alphaCutoff, albedoPath, normalPath, roughnessPath, metalnessPath, aoPath)

    fun applyFrom(other: Material) {
        baseColor = other.baseColor.copyOf(); metallic = other.metallic; roughness = other.roughness
        emissive = other.emissive.copyOf(); alphaMode = other.alphaMode; alphaCutoff = other.alphaCutoff
        albedoPath = other.albedoPath; normalPath = other.normalPath; roughnessPath = other.roughnessPath
        metalnessPath = other.metalnessPath; aoPath = other.aoPath
    }
}
