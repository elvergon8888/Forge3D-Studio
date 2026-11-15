package com.forge3d.studio.core.animation

import com.forge3d.studio.core.math.Mat4
import com.forge3d.studio.core.scene.Mesh
import kotlin.math.sqrt

/**
 * Animación esquelética: huesos, jerarquía, clips con keyframes y skinning LBS.
 * 100% Kotlin puro, testeable en JVM.
 */
object Animation {

    data class Key(val time: Float, val value: Float)

    class Track(val bone: String) {
        val position = ArrayList<Key>()
        val rotation = ArrayList<Key>()
        val scale = ArrayList<Key>()
        fun addPosition(t: Float, v: FloatArray) { position.add(Key(t, v[0])); position.add(Key(t, v[1])); position.add(Key(t, v[2])) }
        fun addRotation(t: Float, v: FloatArray) { rotation.add(Key(t, v[0])); rotation.add(Key(t, v[1])); rotation.add(Key(t, v[2])) }
        fun addScale(t: Float, v: FloatArray) { scale.add(Key(t, v[0])); scale.add(Key(t, v[1])); scale.add(Key(t, v[2])) }
        val keyframeCount: Int get() = position.size / 3
    }

    class AnimationClip(val name: String) {
        val tracks = ArrayList<Track>()
        var duration: Float = 0f
        fun track(bone: String): Track = tracks.firstOrNull { it.bone == bone } ?: also { tracks.add(Track(bone)) }
        fun updateDuration() {
            var d = 0f
            for (t in tracks) { for (k in t.position) if (k.time > d) d = k.time; for (k in t.rotation) if (k.time > d) d = k.time; for (k in t.scale) if (k.time > d) d = k.time }
            duration = d
        }
    }

    class Bone(
        val name: String,
        var parent: String? = null,
        val localPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
        val localRotation: FloatArray = floatArrayOf(0f, 0f, 0f),
        val localScale: FloatArray = floatArrayOf(1f, 1f, 1f)
    ) {
        val worldMatrix = FloatArray(16); val localMatrix = FloatArray(16)
    }

    class Skeleton {
        val bones = LinkedHashMap<String, Bone>()
        fun add(bone: Bone): Bone { bones[bone.name] = bone; return bone }
        fun byName(name: String): Bone? = bones[name]
        fun roots(): List<Bone> = bones.values.filter { it.parent == null || !bones.containsKey(it.parent) }

        fun updateWorldMatrices() {
            val visited = HashSet<String>()
            for (r in roots()) updateRecursive(r, visited)
        }
        private fun updateRecursive(bone: Bone, visited: HashSet<String>) {
            if (!visited.add(bone.name)) return
            Mat4.identity(bone.localMatrix)
            Mat4.translate(bone.localMatrix, 0, bone.localPosition[0], bone.localPosition[1], bone.localPosition[2])
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[0], 1f, 0f, 0f)
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[1], 0f, 1f, 0f)
            Mat4.rotate(bone.localMatrix, 0, bone.localRotation[2], 0f, 0f, 1f)
            Mat4.scale(bone.localMatrix, 0, bone.localScale[0], bone.localScale[1], bone.localScale[2])
            val parent = bone.parent?.let { bones[it] }
            if (parent != null) Mat4.multiply(bone.worldMatrix, 0, parent.worldMatrix, 0, bone.localMatrix, 0)
            else System.arraycopy(bone.localMatrix, 0, bone.worldMatrix, 0, 16)
            for (child in bones.values) if (child.parent == bone.name) updateRecursive(child, visited)
        }

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

        fun resetPose() {
            for (b in bones.values) { b.localPosition[0] = 0f; b.localPosition[1] = 0f; b.localPosition[2] = 0f
                b.localRotation[0] = 0f; b.localRotation[1] = 0f; b.localRotation[2] = 0f
                b.localScale[0] = 1f; b.localScale[1] = 1f; b.localScale[2] = 1f }
        }
    }

    class Skin(val maxInfluences: Int = 4) {
        var boneIndex = IntArray(0); var weight = FloatArray(0)
        fun alloc(vertexCount: Int) { boneIndex = IntArray(vertexCount * maxInfluences); weight = FloatArray(vertexCount * maxInfluences) }
        fun set(vertex: Int, bone: Int, w: Float) {
            if (vertex < 0 || vertex >= boneIndex.size / maxInfluences) return
            val base = vertex * maxInfluences
            var slot = -1; var minW = Float.MAX_VALUE
            for (k in 0 until maxInfluences) {
                if (weight[base + k] <= 0f) { slot = k; break }
                if (weight[base + k] < minW) { minW = weight[base + k]; slot = k }
            }
            if (slot < 0) return
            boneIndex[base + slot] = bone; weight[base + slot] = w.coerceIn(0f, 1f)
            var sum = 0f; for (k in 0 until maxInfluences) sum += weight[base + k]
            if (sum > 1e-6f) for (k in 0 until maxInfluences) weight[base + k] /= sum
        }
        fun autoAssign(skeleton: Skeleton, vertexPositions: FloatArray, bonePositions: FloatArray, falloff: Float) {
            val vertexCount = vertexPositions.size / 3; alloc(vertexCount)
            val boneCount = bonePositions.size / 3; if (boneCount == 0) return
            for (v in 0 until vertexCount) {
                val vx = vertexPositions[v * 3]; val vy = vertexPositions[v * 3 + 1]; val vz = vertexPositions[v * 3 + 2]
                var best = -1; var bestDist = Float.MAX_VALUE; var second = -1; var secondDist = Float.MAX_VALUE
                for (b in 0 until boneCount) {
                    val bx = bonePositions[b * 3]; val by = bonePositions[b * 3 + 1]; val bz = bonePositions[b * 3 + 2]
                    val d = dist(vx, vy, vz, bx, by, bz)
                    if (d < bestDist) { secondDist = bestDist; second = best; bestDist = d; best = b }
                    else if (d < secondDist) { secondDist = d; second = b }
                }
                if (best >= 0) set(v, best, 1f)
                if (second >= 0 && falloff > 0f) { val w = (falloff / (falloff + secondDist)).coerceIn(0f, 0.9f); if (w > 0.05f) set(v, second, w) }
            }
        }
    }

    fun skinVertex(skeleton: Skeleton, skin: Skin, vertexIndex: Int, px: Float, py: Float, pz: Float, out: FloatArray) {
        val base = vertexIndex * skin.maxInfluences; var totalW = 0f; var rx = 0f; var ry = 0f; var rz = 0f
        for (k in 0 until skin.maxInfluences) {
            val w = skin.weight[base + k]; if (w <= 0f) continue
            val boneIdx = skin.boneIndex[base + k]
            val bone = skeleton.bones.values.elementAtOrNull(boneIdx) ?: continue
            val m = bone.worldMatrix
            val x = m[0] * px + m[4] * py + m[8] * pz + m[12]
            val y = m[1] * px + m[5] * py + m[9] * pz + m[13]
            val z = m[2] * px + m[6] * py + m[10] * pz + m[14]
            rx += x * w; ry += y * w; rz += z * w; totalW += w
        }
        if (totalW > 1e-6f) { out[0] = rx / totalW; out[1] = ry / totalW; out[2] = rz / totalW }
        else { out[0] = px; out[1] = py; out[2] = pz }
    }

    fun deformMesh(mesh: Mesh, skeleton: Skeleton, skin: Skin): Mesh {
        skeleton.updateWorldMatrices(); val out = Mesh(); val p = FloatArray(3); var i = 0
        while (i + 9 <= mesh.used) {
            for (k in 0..2) { val vo = i + k * 3
                skinVertex(skeleton, skin, vo / 3, mesh.pos[vo], mesh.pos[vo + 1], mesh.pos[vo + 2], p)
                out.addVertex(p[0], p[1], p[2], mesh.nrm[vo], mesh.nrm[vo + 1], mesh.nrm[vo + 2])
            }
            i += 9
        }
        out.recomputeBounds(); out.needsUpload = true; return out
    }

    private fun interpolate(keys: List<Key>, time: Float): FloatArray {
        val out = FloatArray(3); if (keys.size < 3) return out
        val frameCount = keys.size / 3; if (frameCount == 1) { out[0] = keys[0].value; out[1] = keys[1].value; out[2] = keys[2].value; return out }
        val t = time.coerceAtLeast(0f); var f = 0
        while (f < frameCount - 1 && keys[(f + 1) * 3].time <= t) f++
        val f0 = f; val f1 = min(f + 1, frameCount - 1); val t0 = keys[f0 * 3].time; val t1 = keys[f1 * 3].time
        val alpha = if (t1 - t0 > 1e-6f) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
        for (c in 0..2) { val a = keys[f0 * 3 + c].value; val b = keys[f1 * 3 + c].value; out[c] = a + (b - a) * alpha }
        return out
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz; return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
