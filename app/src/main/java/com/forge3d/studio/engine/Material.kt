package com.forge3d.studio.engine

/** Material PBR simplificado (compatible con glTF pbrMetallicRoughness). */
class Material(
    var name: String = "Predeterminado",
    var baseColor: FloatArray = floatArrayOf(0.78f, 0.78f, 0.8f, 1f),
    var metallic: Float = 0.05f,
    var roughness: Float = 0.6f,
    var emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    var alphaMode: String = "OPAQUE", // OPAQUE | BLEND | MASK
    var alphaCutoff: Float = 0.5f,
    /** Rutas de los mapas de textura (absolutas dentro del almacenamiento de la app). */
    var albedoPath: String? = null,
    var normalPath: String? = null,
    var roughnessPath: String? = null,
    var metalnessPath: String? = null,
    var aoPath: String? = null
) {
    /** `true` si el material referencia alguna textura. */
    val hasTextures: Boolean
        get() = albedoPath != null || normalPath != null || roughnessPath != null ||
                metalnessPath != null || aoPath != null

    fun copy(): Material = Material(
        name,
        baseColor.copyOf(),
        metallic,
        roughness,
        emissive.copyOf(),
        alphaMode,
        alphaCutoff,
        albedoPath,
        normalPath,
        roughnessPath,
        metalnessPath,
        aoPath
    )

    fun applyFrom(other: Material) {
        other.baseColor?.let { baseColor = it.copyOf() }
        metallic = other.metallic
        roughness = other.roughness
        other.emissive?.let { emissive = it.copyOf() }
        alphaMode = other.alphaMode
        alphaCutoff = other.alphaCutoff
        albedoPath = other.albedoPath
        normalPath = other.normalPath
        roughnessPath = other.roughnessPath
        metalnessPath = other.metalnessPath
        aoPath = other.aoPath
    }

    companion object {
        /** "#RRGGBB" o "#AARRGGBB" → FloatArray RGBA 0..1, o null. */
        fun parseHex(hex: String): FloatArray? {
            val h = hex.trim().removePrefix("#")
            return when (h.length) {
                6 -> {
                    val v = h.toLongOrNull(16) ?: return null
                    floatArrayOf(
                        ((v shr 16) and 0xFF).toInt() / 255f,
                        ((v shr 8) and 0xFF).toInt() / 255f,
                        (v and 0xFF).toInt() / 255f,
                        1f
                    )
                }
                8 -> {
                    val v = h.toLongOrNull(16) ?: return null
                    floatArrayOf(
                        ((v shr 16) and 0xFF).toInt() / 255f,
                        ((v shr 8) and 0xFF).toInt() / 255f,
                        (v and 0xFF).toInt() / 255f,
                        ((v shr 24) and 0xFF).toInt() / 255f
                    )
                }
                else -> null
            }
        }
    }
}
