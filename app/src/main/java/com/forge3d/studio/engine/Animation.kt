package com.forge3d.studio.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Animación esquelética: huesos, pesos de skinning y clips.
 *
 * Modelo:
 * - [Bone]: hueso con transformación local (TRS) y un padre. La pose se evalúa en jerarquía.
 * - [Skeleton]: conjunto de huesos + opcionalmente, los pesos de skinning por malla.
 * - [AnimationClip]: una o varias pistas (nombre de hueso -> valores keyframeados) + duración.
 * - [Skin]: pesos por vértice (índice de hueso + peso) para una malla concreta.
 *
 * Todo en Kotlin puro y sin dependencias: la app se mantiene "zero-dependency" y el peso de
 * build sigue siendo trivial.
 */
object Animation {

    /** Rango de valores keyframeados por canal. */
    data class Key(val time: Float, val value: Float)

    /** Pista de animación: valores de un hueso a lo largo del tiempo. */
    class Track(val bone: String) {
        val position = ArrayList<Key>()
        val rotation = ArrayList<Key>()
        val scale = ArrayList<Key>()

        fun addPosition(t: Float, v: FloatArray) {
            position.add(Key(t, v[0])); position.add(Key(t, v[1])); position.add(Key(t, v[2]))
        }

        fun addRotation(t: Float, v: FloatArray) {
            rotation.add(Key(t, v[0])); rotation.add(Key(t, v[1])); rotation.add(Key(t, v[2]))
        }

        fun addScale(t: Float, v: FloatArray) {
            scale.add(Key(t, v[0])); scale.add(Key(t, v[1])); scale.add(Key(t, v[2]))
        }

        /** El número de keys por canal es 3x el número de keyframes. */
        val keyframeCount: Int get() = position.size / 3
    }

    /**
     * Clip de animación: conjunto de pistas y su duración.
     */
    class AnimationClip(val name: String) {
        val tracks = ArrayList<Track>()
        var duration: Float = 0f

        fun track(bone: String): Track {
            val t = tracks.firstOrNull { it.bone == bone }
            if (t != null) return t
            val n = Track(bone)
            tracks.add(n)
            return n
        }

        /** Recalcula la duración como el máximo de los tiempos de las pistas. */
        fun updateDuration() {
            var d = 0f
            for (t in tracks) {
                for (k in t.position) if (k.time > d) d = k.time
                for (k in t.rotation) if (k.time > d) d = k.time
                for (k in t.scale) if (k.time > d) d = k.time
            }
            duration = d
        }
    }

    /**
     * Hueso de un esqueleto.
     */
    class Bone(
        val name: String,
        var parent: String? = null,
        /** Traslación local en metros. */
        val localPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** Rotación local en grados (X, Y, Z). */
        val localRotation: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** Escala local. */
        val localScale: FloatArray = floatArrayOf(1f, 1f, 1f)
    ) {
        /** Matriz de transformación global (column-major, como android.opengl.Matrix). */
        val worldMatrix = FloatArray(16)

        /** Matriz de transformación local. */
        val localMatrix = FloatArray(16)
    }

    /**
     * Esqueleto: huesos organizados en jerarquía.
     */
    class Skeleton {
        val bones = LinkedHashMap<String, Bone>()

        fun add(bone: Bone): Bone {
            bones[bone.name] = bone
            return bone
        }

        fun byName(name: String): Bone? = bones[name]

        /** Raíces (huesos sin padre o cuyo padre no existe). */
        fun roots(): List<Bone> = bones.values.filter { it.parent == null || !bones.containsKey(it.parent) }

        /**
         * Evalúa las matrices global de todos los huesos a partir de sus TRS locales.
         *
         * Se recorre en profundidad: primero el padre, luego el hijo, de modo que la matriz
         * global = global(padre) * local(hijo).
         */
        fun updateWorldMatrices() {
            val visited = HashSet<String>()
            for (r in roots()) updateRecursive(r, visited)
        }

        private fun updateRecursive(bone: Bone, visited: HashSet<String>) {
            if (!visited.add(bone.name)) return // evita ciclos
            // Matriz local desde TRS
            Mat4.identity(bone.localMatrix, 0)
            Mat4.translate(
                bone.localMatrix, 0,
                bone.localPosition[0], bone.localPosition[1], bone.localPosition[2]
            )
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[0], 1f, 0f, 0f)
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[1], 0f, 1f, 0f)
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[2], 0f, 0f, 1f)
            Mat4.scale(bone.localMatrix, 0, bone.localScale[0], bone.localScale[1], bone.localScale[2])

            val parent = bone.parent?.let { bones[it] }
            if (parent != null) {
                Mat4.multiply(bone.worldMatrix, 0, parent.worldMatrix, 0, bone.localMatrix, 0)
            } else {
                System.arraycopy(bone.localMatrix, 0, bone.worldMatrix, 0, 16)
            }
            // Hijos
            for (child in bones.values) if (child.parent == bone.name) updateRecursive(child, visited)
        }

        /**
         * Aplica un clip en el tiempo [time] (segundos), interpolando entre keyframes.
         */
        fun applyClip(clip: AnimationClip, time: Float) {
            for (track in clip.tracks) {
                val bone = bones[track.bone] ?: continue
                val p = interpolate(track.position, time)
                bone.localPosition[0] = p[0]; bone.localPosition[1] = p[1]; bone.localPosition[2] = p[2]
                val r = interpolate(track.rotation, time)
                bone.localRotation[0] = r[0]; bone.localRotation[1] = r[1]; bone.localRotation[2] = r[2]
                val s = interpolate(track.scale, time)
                bone.localScale[0] = s[0]; bone.localScale[1] = s[1]; bone.localScale[2] = s[2]
            }
        }

        /**
         * Restaura la pose de reposo (los TRS locales originales).
         */
        fun resetPose() {
            for (b in bones.values) {
                b.localPosition[0] = 0f; b.localPosition[1] = 0f; b.localPosition[2] = 0f
                b.localRotation[0] = 0f; b.localRotation[1] = 0f; b.localRotation[2] = 0f
                b.localScale[0] = 1f; b.localScale[1] = 1f; b.localScale[2] = 1f
            }
        }
    }

    /**
     * Pesos de skinning para una malla: por cada vértice, hasta [maxInfluences] huesos con su peso.
     */
    class Skin(val maxInfluences: Int = 4) {
        /** boneIndex[v * maxInfluences + k] y weight[v * maxInfluences + k]. */
        var boneIndex = IntArray(0)
        var weight = FloatArray(0)

        /** Crea una piel vacía para una malla con [vertexCount] vértices. */
        fun alloc(vertexCount: Int) {
            boneIndex = IntArray(vertexCount * maxInfluences)
            weight = FloatArray(vertexCount * maxInfluences)
        }

        /** Asigna un peso a un vértice, manteniendo las [maxInfluences] mayores contribuciones. */
        fun set(vertex: Int, bone: Int, w: Float) {
            if (vertex < 0 || vertex >= boneIndex.size / maxInfluences) return
            val base = vertex * maxInfluences
            var slot = -1
            var minW = Float.MAX_VALUE
            for (k in 0 until maxInfluences) {
                if (weight[base + k] <= 0f) { slot = k; break }
                if (weight[base + k] < minW) { minW = weight[base + k]; slot = k }
            }
            if (slot < 0) return
            boneIndex[base + slot] = bone
            weight[base + slot] = w.coerceIn(0f, 1f)
            // Normaliza los pesos del vértice
            var sum = 0f
            for (k in 0 until maxInfluences) sum += weight[base + k]
            if (sum > 1e-6f) {
                for (k in 0 until maxInfluences) weight[base + k] /= sum
            }
        }

        /**
         * Asigna pesos automáticamente por proximidad: cada vértice se une al hueso más cercano
         * (con caída por distancia). Es el método "automático" de skinning.
         */
        fun autoAssign(skeleton: Skeleton, vertexPositions: FloatArray, bonePositions: FloatArray, falloff: Float) {
            val vertexCount = vertexPositions.size / 3
            alloc(vertexCount)
            val boneCount = bonePositions.size / 3
            if (boneCount == 0) return
            for (v in 0 until vertexCount) {
                val vx = vertexPositions[v * 3]; val vy = vertexPositions[v * 3 + 1]; val vz = vertexPositions[v * 3 + 2]
                var best = -1
                var bestDist = Float.MAX_VALUE
                var second = -1
                var secondDist = Float.MAX_VALUE
                for (b in 0 until boneCount) {
                    val bx = bonePositions[b * 3]; val by = bonePositions[b * 3 + 1]; val bz = bonePositions[b * 3 + 2]
                    val d = dist(vx, vy, vz, bx, by, bz)
                    if (d < bestDist) {
                        secondDist = bestDist; second = best
                        bestDist = d; best = b
                    } else if (d < secondDist) {
                        secondDist = d; second = b
                    }
                }
                if (best >= 0) set(v, best, 1f)
                // Segundo hueso con peso según cercanía relativa
                if (second >= 0 && falloff > 0f) {
                    val w = (falloff / (falloff + secondDist)).coerceIn(0f, 0.9f)
                    if (w > 0.05f) set(v, second, w)
                }
            }
        }
    }

    /**
     * Evalúa la posición de un vértice deformado por skinning lineal (LBS).
     *
     * @param out resultado (3 floats)
     */
    fun skinVertex(
        skeleton: Skeleton,
        skin: Skin,
        vertexIndex: Int,
        px: Float, py: Float, pz: Float,
        out: FloatArray
    ) {
        val base = vertexIndex * skin.maxInfluences
        var totalW = 0f
        var rx = 0f; var ry = 0f; var rz = 0f
        for (k in 0 until skin.maxInfluences) {
            val w = skin.weight[base + k]
            if (w <= 0f) continue
            val boneIdx = skin.boneIndex[base + k]
            val bone = skeleton.bones.values.elementAtOrNull(boneIdx) ?: continue
            val m = bone.worldMatrix
            // Transformación por la matriz global del hueso
            val x = m[0] * px + m[4] * py + m[8] * pz + m[12]
            val y = m[1] * px + m[5] * py + m[9] * pz + m[13]
            val z = m[2] * px + m[6] * py + m[10] * pz + m[14]
            rx += x * w; ry += y * w; rz += z * w
            totalW += w
        }
        if (totalW > 1e-6f) {
            out[0] = rx / totalW; out[1] = ry / totalW; out[2] = rz / totalW
        } else {
            out[0] = px; out[1] = py; out[2] = pz
        }
    }

    /** Aplica el skinning a una malla completa, devolviendo una malla deformada. */
    fun deformMesh(mesh: Mesh, skeleton: Skeleton, skin: Skin): Mesh {
        skeleton.updateWorldMatrices()
        val out = Mesh()
        val p = FloatArray(3)
        var i = 0
        while (i + 9 <= mesh.used) {
            for (k in 0..2) {
                val vo = i + k * 3
                skinVertex(skeleton, skin, vo / 3, mesh.pos[vo], mesh.pos[vo + 1], mesh.pos[vo + 2], p)
                out.addVertex(p[0], p[1], p[2], mesh.nrm[vo], mesh.nrm[vo + 1], mesh.nrm[vo + 2])
            }
            i += 9
        }
        out.recomputeBounds()
        out.needsUpload = true
        return out
    }

    /**
     * Interpola un canal de keyframes (3 floats por keyframe) en el tiempo dado.
     *
     * @return vector interpolado de 3 componentes; (0,0,0) si no hay keys.
     */
    private fun interpolate(keys: List<Key>, time: Float): FloatArray {
        val out = FloatArray(3)
        if (keys.size < 3) return out
        val frameCount = keys.size / 3
        if (frameCount == 1) {
            out[0] = keys[0].value; out[1] = keys[1].value; out[2] = keys[2].value
            return out
        }
        val t = time.coerceAtLeast(0f)
        // Encuentra el frame actual
        var f = 0
        while (f < frameCount - 1 && keys[(f + 1) * 3].time <= t) f++
        val f0 = f
        val f1 = min(f + 1, frameCount - 1)
        val t0 = keys[f0 * 3].time
        val t1 = keys[f1 * 3].time
        val alpha = if (t1 - t0 > 1e-6f) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
        for (c in 0..2) {
            val a = keys[f0 * 3 + c].value
            val b = keys[f1 * 3 + c].value
            out[c] = a + (b - a) * alpha
        }
        return out
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
