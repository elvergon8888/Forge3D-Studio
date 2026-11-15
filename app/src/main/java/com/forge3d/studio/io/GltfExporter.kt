package com.forge3d.studio.io

import android.util.Base64
import com.forge3d.studio.engine.Math3D
import com.forge3d.studio.engine.SceneObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exportador glTF 2.0 (.gltf embebido y .glb binario). Solo geometría + materiales PBR. */
object GltfExporter {

    fun buildGlb(objects: List<SceneObject>): ByteArray = build(objects, glb = true)

    fun buildGltf(objects: List<SceneObject>): ByteArray = build(objects, glb = false)

    private fun build(objects: List<SceneObject>, glb: Boolean): ByteArray {
        val bin = ByteArrayOutputStream()
        val buffers = JSONArray()
        val bufferViews = JSONArray()
        val accessors = JSONArray()
        val meshes = JSONArray()
        val nodes = JSONArray()
        val materials = JSONArray()
        val sceneNodes = JSONArray()

        for (obj in objects) {
            val mesh = obj.mesh
            val vertN = mesh.vertCount
            if (vertN == 0) continue

            // bufferView posiciones
            val posOff = bin.size()
            val pb = ByteBuffer.allocate(vertN * 12).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            while (i < mesh.used) {
                val x = mesh.pos[i]; val y = mesh.pos[i + 1]; val z = mesh.pos[i + 2]
                pb.putFloat(x); pb.putFloat(y); pb.putFloat(z)
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                i += 3
            }
            bin.write(pb.array())
            while (bin.size() % 4 != 0) bin.write(0)
            val bvPos = JSONObject()
                .put("buffer", 0)
                .put("byteOffset", posOff)
                .put("byteLength", vertN * 12)
                .put("target", 34962)
            bufferViews.put(bvPos)

            // bufferView normales
            val nrmOff = bin.size()
            val nb = ByteBuffer.allocate(vertN * 12).order(ByteOrder.LITTLE_ENDIAN)
            i = 0
            while (i < mesh.used) {
                nb.putFloat(mesh.nrm[i]); nb.putFloat(mesh.nrm[i + 1]); nb.putFloat(mesh.nrm[i + 2])
                i += 3
            }
            bin.write(nb.array())
            while (bin.size() % 4 != 0) bin.write(0)
            val bvNrm = JSONObject()
                .put("buffer", 0)
                .put("byteOffset", nrmOff)
                .put("byteLength", vertN * 12)
                .put("target", 34962)
            bufferViews.put(bvNrm)

            val accPos = JSONObject()
                .put("bufferView", bufferViews.length() - 2)
                .put("componentType", 5126)
                .put("count", vertN)
                .put("type", "VEC3")
                .put("min", JSONArray(floatArrayOf(minX, minY, minZ).toTypedArray()))
                .put("max", JSONArray(floatArrayOf(maxX, maxY, maxZ).toTypedArray()))
            accessors.put(accPos)
            val accNrm = JSONObject()
                .put("bufferView", bufferViews.length() - 1)
                .put("componentType", 5126)
                .put("count", vertN)
                .put("type", "VEC3")
            accessors.put(accNrm)

            // material
            val mt = obj.material
            val bc = mt.baseColor
            val mat = JSONObject()
                .put("name", mt.name)
                .put("pbrMetallicRoughness", JSONObject()
                    .put("baseColorFactor", JSONArray(listOf(bc[0].toDouble(), bc[1].toDouble(), bc[2].toDouble(), bc[3].toDouble())))
                    .put("metallicFactor", mt.metallic.toDouble())
                    .put("roughnessFactor", mt.roughness.toDouble()))
                .put("emissiveFactor", JSONArray(listOf(mt.emissive[0].toDouble(), mt.emissive[1].toDouble(), mt.emissive[2].toDouble())))
                .put("alphaMode", mt.alphaMode)
                .put("doubleSided", true)
            if (mt.alphaMode == "MASK") mat.put("alphaCutoff", mt.alphaCutoff.toDouble())
            materials.put(mat)

            val prim = JSONObject()
                .put("attributes", JSONObject().put("POSITION", accessors.length() - 2).put("NORMAL", accessors.length() - 1))
                .put("material", materials.length() - 1)
                .put("mode", 4)
            meshes.put(JSONObject().put("name", obj.name).put("primitives", JSONArray(listOf(prim))))

            val q = Math3D.quatFromEuler(obj.rotation[0], obj.rotation[1], obj.rotation[2])
            val node = JSONObject()
                .put("name", obj.name)
                .put("mesh", meshes.length() - 1)
                .put("translation", JSONArray(listOf(
                    obj.position[0].toDouble(), obj.position[1].toDouble(), obj.position[2].toDouble())))
                .put("rotation", JSONArray(listOf(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())))
                .put("scale", JSONArray(listOf(
                    obj.scale[0].toDouble(), obj.scale[1].toDouble(), obj.scale[2].toDouble())))
            nodes.put(node)
            sceneNodes.put(nodes.length() - 1)
        }

        val binBytes = bin.toByteArray()
        val gltf = JSONObject()
            .put("asset", JSONObject()
                .put("version", "2.0")
                .put("generator", "Forge3D Studio 1.0.0")
                .put("copyright", "Generado por Forge3D Studio (original)"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", sceneNodes)))
            .put("nodes", nodes)
            .put("meshes", meshes)
            .put("materials", materials)
            .put("bufferViews", bufferViews)
            .put("accessors", accessors)

        if (glb) {
            var jsonBytes = gltf.toString().toByteArray(Charsets.UTF_8)
            val padJson = (4 - jsonBytes.size % 4) % 4
            if (padJson > 0) {
                val nb2 = ByteArray(jsonBytes.size + padJson)
                System.arraycopy(jsonBytes, 0, nb2, 0, jsonBytes.size)
                for (k in 0 until padJson) nb2[jsonBytes.size + k] = 0x20
                jsonBytes = nb2
            }
            val padBin = (4 - binBytes.size % 4) % 4
            val binPadded = if (padBin > 0) binBytes.copyOf(binBytes.size + padBin) else binBytes
            val total = 12 + 8 + jsonBytes.size + 8 + binPadded.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67) // glTF
            out.putInt(2)
            out.putInt(total)
            out.putInt(jsonBytes.size)
            out.putInt(0x4E4F534A) // JSON
            out.put(jsonBytes)
            out.putInt(binPadded.size)
            out.putInt(0x004E4942) // BIN
            out.put(binPadded)
            return out.array()
        } else {
            val uri = "data:application/octet-stream;base64," +
                Base64.encodeToString(binBytes, Base64.NO_WRAP)
            gltf.put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size).put("uri", uri)))
            return gltf.toString(2).toByteArray(Charsets.UTF_8)
        }
    }
}
