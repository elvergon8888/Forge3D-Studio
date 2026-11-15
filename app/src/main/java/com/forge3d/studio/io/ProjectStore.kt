package com.forge3d.studio.io

import com.forge3d.studio.engine.Camera3D
import com.forge3d.studio.engine.Material
import com.forge3d.studio.engine.Mesh
import com.forge3d.studio.engine.Primitives
import com.forge3d.studio.engine.Scene
import com.forge3d.studio.engine.SceneObject
import com.forge3d.studio.mcp.AppModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Guardado/carga de proyectos .m3d.json (primitivas por parámetros, importados en binario). */
object ProjectStore {

    private const val MESH_MAGIC = "F3DM"

    fun listProyectos(): List<String> {
        val d = AppModel.projectsDir()
        return d.listFiles { f -> f.name.endsWith(".m3d.json") }
            ?.map { it.name.removeSuffix(".m3d.json") }
            ?.sorted()
            ?: emptyList()
    }

    @Synchronized
    fun guardar(nombre: String, scene: Scene, camera: Camera3D): File {
        val safe = sanitize(nombre)
        val meshDir = File(AppModel.projectsDir(), "meshes")
        if (!meshDir.exists()) meshDir.mkdirs()

        val root = JSONObject()
        root.put("formato", "m3d-json")
        root.put("version", 1)
        root.put("nombre", safe)
        root.put("guardado", java.time.Instant.now().toString())
        root.put("generador", "Forge3D Studio 1.0.0")

        val arr = JSONArray()
        for (obj in scene.objects) {
            val o = JSONObject()
            o.put("id", obj.id)
            o.put("nombre", obj.name)
            o.put("tipo", obj.tipo)
            obj.primitiva?.let { o.put("primitiva", it) }
            o.put("posicion", f3(obj.position))
            o.put("rotacion", f3(obj.rotation))
            o.put("escala", f3(obj.scale))
            o.put("visible", obj.visible)
            val mt = obj.material
            val m = JSONObject()
                .put("nombre", mt.name)
                .put("baseColor", JSONArray(listOf(
                    mt.baseColor[0].toDouble(), mt.baseColor[1].toDouble(),
                    mt.baseColor[2].toDouble(), mt.baseColor[3].toDouble())))
                .put("metallic", mt.metallic.toDouble())
                .put("roughness", mt.roughness.toDouble())
                .put("emissive", JSONArray(listOf(
                    mt.emissive[0].toDouble(), mt.emissive[1].toDouble(), mt.emissive[2].toDouble())))
                .put("alphaMode", mt.alphaMode)
                .put("alphaCutoff", mt.alphaCutoff.toDouble())
            o.put("material", m)
            if (obj.primitiva == null) {
                val mf = File(meshDir, "${obj.id}.f3dm")
                saveMeshBin(mf, obj.mesh)
                o.put("meshArchivo", "meshes/${obj.id}.f3dm")
            }
            arr.put(o)
        }
        root.put("objetos", arr)
        root.put("camara", JSONObject()
            .put("objetivo", f3(camera.target))
            .put("yaw", camera.yaw.toDouble())
            .put("pitch", camera.pitch.toDouble())
            .put("distancia", camera.distance.toDouble()))

        val f = File(AppModel.projectsDir(), "$safe.m3d.json")
        f.writeText(root.toString(2), Charsets.UTF_8)
        return f
    }

    /** Carga reemplazando la escena. Devuelve número de objetos cargados. */
    @Synchronized
    fun cargar(nombre: String, scene: Scene, camera: Camera3D): Int {
        val safe = sanitize(nombre)
        val f = File(AppModel.projectsDir(), "$safe.m3d.json")
        val text = f.readText(Charsets.UTF_8)
        val root = JSONObject(text)
        val arr = root.optJSONArray("objetos") ?: return 0

        scene.clear()
        var count = 0
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val primitiva = o.optString("primitiva", "")
                val mesh: Mesh = if (primitiva.isNotBlank()) {
                    Primitives.create(primitiva) ?: continue
                } else {
                    val rel = o.optString("meshArchivo", "")
                    if (rel.isBlank()) continue
                    val mf = File(AppModel.projectsDir(), rel)
                    loadMeshBin(mf) ?: continue
                }
                val obj = SceneObject(o.optString("id", scene.nextId()), o.optString("nombre", "Objeto"), mesh)
                obj.tipo = o.optString("tipo", "primitiva")
                obj.primitiva = if (primitiva.isBlank()) null else primitiva
                read3(o.getJSONArray("posicion"), obj.position)
                read3(o.getJSONArray("rotacion"), obj.rotation)
                read3(o.getJSONArray("escala"), obj.scale)
                obj.visible = o.optBoolean("visible", true)
                val m = o.optJSONObject("material")
                if (m != null) {
                    val bc = m.optJSONArray("baseColor")
                    if (bc != null && bc.length() == 4) {
                        obj.material.baseColor = floatArrayOf(
                            bc.getDouble(0).toFloat(), bc.getDouble(1).toFloat(),
                            bc.getDouble(2).toFloat(), bc.getDouble(3).toFloat())
                    }
                    obj.material.metallic = m.getDouble("metallic").toFloat()
                    obj.material.roughness = m.getDouble("roughness").toFloat()
                    val em = m.optJSONArray("emissive")
                    if (em != null && em.length() == 3) {
                        obj.material.emissive = floatArrayOf(
                            em.getDouble(0).toFloat(), em.getDouble(1).toFloat(), em.getDouble(2).toFloat())
                    }
                    obj.material.alphaMode = m.optString("alphaMode", "OPAQUE")
                    obj.material.alphaCutoff = m.getDouble("alphaCutoff").toFloat()
                }
                mesh.needsUpload = true
                if (scene.add(obj)) count++
            } catch (e: Exception) {
                // objeto dañado: se omite
            }
        }
        val cam = root.optJSONObject("camara")
        if (cam != null) {
            read3(cam.optJSONArray("objetivo") ?: JSONArray(), camera.target)
            camera.yaw = cam.optDouble("yaw", 45.0).toFloat()
            camera.pitch = cam.optDouble("pitch", 28.0).toFloat()
            camera.distance = cam.optDouble("distancia", 6.0).toFloat()
        }
        return count
    }

    fun sanitize(nombre: String): String {
        val s = nombre.trim().replace(Regex("[^A-Za-z0-9_\\-\\u00C0-\\u017F]"), "_")
        return if (s.isEmpty()) "Proyecto" else s.take(64)
    }

    private fun f3(a: FloatArray): JSONArray =
        JSONArray(listOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble()))

    private fun read3(arr: JSONArray?, out: FloatArray) {
        if (arr == null) return
        if (arr.length() >= 3) {
            out[0] = arr.getDouble(0).toFloat()
            out[1] = arr.getDouble(1).toFloat()
            out[2] = arr.getDouble(2).toFloat()
        }
    }

    private fun saveMeshBin(f: File, mesh: Mesh) {
        val n = mesh.vertCount
        val bb = ByteBuffer.allocate(12 + n * 24).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MESH_MAGIC.toByteArray(Charsets.US_ASCII))
        bb.putInt(1)
        bb.putInt(n)
        for (i in 0 until mesh.used) {
            bb.putFloat(mesh.pos[i])
        }
        for (i in 0 until mesh.used) {
            bb.putFloat(mesh.nrm[i])
        }
        f.writeBytes(bb.array())
    }

    private fun loadMeshBin(f: File): Mesh? {
        if (!f.exists()) return null
        val b = f.readBytes()
        if (b.size < 12) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        bb.get(magic)
        if (String(magic, Charsets.US_ASCII) != MESH_MAGIC) return null
        val version = bb.int
        if (version != 1) return null
        val n = bb.int
        if (n <= 0 || n > 1_000_000 || b.size < 12 + n * 24) return null
        val pos = FloatArray(n * 9)
        val nrm = FloatArray(n * 9)
        for (i in pos.indices) pos[i] = bb.float
        for (i in nrm.indices) nrm[i] = bb.float
        return Mesh.fromRaw(pos, nrm)
    }
}
