package com.forge3d.studio.mcp

import com.forge3d.studio.engine.DeviceCapabilities
import com.forge3d.studio.engine.Limits
import com.forge3d.studio.engine.Material
import com.forge3d.studio.engine.MemoryManager
import com.forge3d.studio.engine.Mesh
import com.forge3d.studio.engine.MeshOps
import com.forge3d.studio.engine.Primitives
import com.forge3d.studio.engine.SceneObject
import com.forge3d.studio.io.FbxImporter
import com.forge3d.studio.io.GltfExporter
import com.forge3d.studio.io.ObjImporter
import com.forge3d.studio.io.ProjectStore
import com.forge3d.studio.io.StlImporter
import com.forge3d.studio.io.ThreeMfImporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Registro de tools MCP (26) y su ejecución sobre la escena compartida. */
object McpTools {

    class ToolDef(val name: String, val desc: String, val schema: JSONObject, val handler: (JSONObject) -> String)

    private fun p(type: String, desc: String, items: JSONObject? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", desc)
        items?.let { o.put("items", it) }
        return o
    }

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("type", "object")
        val ps = JSONObject()
        for ((k, v) in props) ps.put(k, v)
        o.put("properties", ps)
        if (required.isNotEmpty()) o.put("required", JSONArray(required))
        return o
    }

    private fun numArrSchema(desc: String): JSONObject =
        p("array", desc, JSONObject().put("type", "number"))

    val tools: List<ToolDef> = listOf(
        ToolDef("info_servidor", "Información del editor y del servidor MCP (versión, límites, estado).",
            schema(),
            { infoServidor() }),
        ToolDef("listar_escena", "Lista todos los objetos de la escena con transformación y material.",
            schema(),
            { listarEscena() }),
        ToolDef("crear_cubo", "Crea un cubo de 1 m de arista.",
            schema("nombre" to p("string", "Nombre opcional"),
                "posicion" to numArrSchema("[x,y,z] en metros"),
                "escala" to numArrSchema("[x,y,z] escala")),
            { crearPrimitiva(Primitives.CUBE, it) }),
        ToolDef("crear_esfera", "Crea una esfera UV de radio 0.5 m.",
            schema("nombre" to p("string", "Nombre opcional"),
                "posicion" to numArrSchema("[x,y,z] en metros"),
                "escala" to numArrSchema("[x,y,z] escala")),
            { crearPrimitiva(Primitives.SPHERE, it) }),
        ToolDef("crear_cilindro", "Crea un cilindro de radio 0.5 m y altura 1 m.",
            schema("nombre" to p("string", "Nombre opcional"),
                "posicion" to numArrSchema("[x,y,z] en metros"),
                "escala" to numArrSchema("[x,y,z] escala")),
            { crearPrimitiva(Primitives.CYLINDER, it) }),
        ToolDef("crear_plano", "Crea un plano de 1 x 1 m en XZ.",
            schema("nombre" to p("string", "Nombre opcional"),
                "posicion" to numArrSchema("[x,y,z] en metros"),
                "escala" to numArrSchema("[x,y,z] escala")),
            { crearPrimitiva(Primitives.PLANE, it) }),
        ToolDef("crear_primitiva", "Crea una primitiva por tipo: cubo|esfera|cilindro|plano.",
            schema("tipo" to p("string", "cubo, esfera, cilindro o plano"),
                "nombre" to p("string", "Nombre opcional"),
                "posicion" to numArrSchema("[x,y,z]"),
                "escala" to numArrSchema("[x,y,z]"), required = listOf("tipo")),
            { a -> crearPrimitiva(a.optString("tipo"), a) }),
        ToolDef("eliminar_objeto", "Elimina un objeto por id.",
            schema("id" to p("string", "Id del objeto"), required = listOf("id")),
            { a -> sync { s ->
                val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
                s.remove(o.id)
                ok("Eliminado ${o.id} (${o.name})")
            } }),
        ToolDef("duplicar_objeto", "Duplica un objeto (comparte la malla, copia material y transformación).",
            schema("id" to p("string", "Id del objeto"), required = listOf("id")),
            { a -> sync { s ->
                val d = s.duplicate(a.optString("id")) ?: return@sync err("No se pudo duplicar (límite o id inválido)")
                ok("Duplicado como ${d.id} (${d.name})")
            } }),
        ToolDef("transformar_objeto", "Aplica transformación: modo mover|rotar|escalar, valor [x,y,z] (metros/grados).",
            schema("id" to p("string", "Id del objeto"),
                "modo" to p("string", "mover | rotar | escalar"),
                "valor" to numArrSchema("[x,y,z]"),
                "relativo" to p("boolean", "true = suma a la transformación actual (defecto true)"),
                required = listOf("id", "modo", "valor")),
            { transformar(it) }),
        ToolDef("objeto_info", "Detalles de un objeto: bounds mundo (m), material, triángulos.",
            schema("id" to p("string", "Id del objeto"), required = listOf("id")),
            { a -> sync { s ->
                val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
                objJson(o)
            } }),
        ToolDef("seleccionar_objeto", "Selecciona un objeto en la UI.",
            schema("id" to p("string", "Id del objeto"), required = listOf("id")),
            { a -> sync { s ->
                val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
                s.selected = o.id
                ok("Seleccionado ${o.id}")
            } }),
        ToolDef("deseleccionar_todo", "Quita la selección actual.", schema(),
            { _ -> sync { s -> s.selected = null; ok("Sin selección") } }),
        ToolDef("aplicar_material", "Aplica material PBR: baseColor ([r,g,b,a] 0-1 o hex #RRGGBB), metallic, roughness, emissive, alphaMode.",
            schema("id" to p("string", "Id del objeto"),
                "material" to p("object", "Propiedades del material"),
                required = listOf("id", "material")),
            { aplicarMaterial(it) }),
        ToolDef("camara_estado", "Estado de la cámara orbital (objetivo, yaw, pitch, distancia, ojo).", schema(),
            { _ -> sync { _ ->
                val c = AppModel.camera
                JSONObject().put("objetivo", f3(c.target)).put("yaw", c.yaw.toDouble())
                    .put("pitch", c.pitch.toDouble()).put("distancia", c.distance.toDouble())
                    .put("ojo", f3(c.eye)).toString()
            } }),
        ToolDef("camara_orbitar", "Orbita la cámara (deltas en grados).",
            schema("yaw" to p("number", "Delta yaw °"), "pitch" to p("number", "Delta pitch °")),
            { a -> sync { _ ->
                AppModel.camera.orbit(a.optDouble("yaw", 0.0).toFloat(), a.optDouble("pitch", 0.0).toFloat())
                ok("Órbita aplicada")
            } }),
        ToolDef("camara_desplazar", "Desplaza la cámara/target en el plano de pantalla (metros).",
            schema("dx" to p("number", "Metros horizontales"), "dy" to p("number", "Metros verticales")),
            { a -> sync { _ ->
                AppModel.camera.pan(a.optDouble("dx", 0.0).toFloat(), a.optDouble("dy", 0.0).toFloat())
                ok("Desplazamiento aplicado")
            } }),
        ToolDef("camara_zoom", "Zoom multiplicativo (0.5 = acercar la mitad, 2 = alejar).",
            schema("factor" to p("number", "Factor > 0"), required = listOf("factor")),
            { a -> sync { _ ->
                AppModel.camera.zoom(a.optDouble("factor", 1.0).toFloat())
                ok("Zoom aplicado")
            } }),
        ToolDef("camara_encuadrar", "Encuadra un objeto o toda la escena ('*' o sin id).",
            schema("id" to p("string", "Id o '*'")),
            { a -> sync { s ->
                val b = FloatArray(6)
                val id = a.optString("id", "*")
                val done = if (id == "*") s.worldAabb(b) else s.byId(id)?.let { it.worldBounds(b); true } ?: false
                if (!done) return@sync err("Nada que encuadrar")
                AppModel.camera.fit(b)
                ok("Encuadrado")
            } }),
        ToolDef("listar_proyectos", "Lista los proyectos .m3d.json guardados.", schema(),
            { _ ->
                JSONArray(ProjectStore.listProyectos()).let {
                    JSONObject().put("proyectos", it).toString()
                }
            }),
        ToolDef("guardar_proyecto", "Guarda la escena como proyecto .m3d.json.",
            schema("nombre" to p("string", "Nombre del proyecto"), required = listOf("nombre")),
            { a -> sync { s ->
                val f = ProjectStore.guardar(a.optString("nombre", "Proyecto"), s, AppModel.camera)
                ok("Guardado ${f.absolutePath} (${f.length()} bytes)")
            } }),
        ToolDef("cargar_proyecto", "Carga un proyecto .m3d.json reemplazando la escena.",
            schema("nombre" to p("string", "Nombre del proyecto"), required = listOf("nombre")),
            { a -> sync { s ->
                val n = ProjectStore.cargar(a.optString("nombre"), s, AppModel.camera)
                if (n == 0) err("Proyecto vacío o no encontrado") else ok("Cargados $n objetos")
            } }),
        ToolDef("exportar_gltf", "Exporta la escena u objetos a .glb o .gltf (glTF 2.0, PBR).",
            schema("ids" to p("array", "Ids a exportar; vacío = toda la escena"),
                "formato" to p("string", "glb | gltf (defecto glb)"),
                "nombre" to p("string", "Nombre de archivo sin extensión")),
            { exportarGltf(it) }),
        ToolDef("importar_modelo", "Importa .obj/.stl/.3mf/.fbx desde una ruta accesible de la app (Datos/ o absoluta dentro de su almacenamiento).",
            schema("ruta" to p("string", "Ruta del archivo"), required = listOf("ruta")),
            { importarModelo(it) }),
        ToolDef("vaciar_escena", "Elimina todos los objetos (requiere confirmar=true).",
            schema("confirmar" to p("boolean", "Debe ser true"), required = listOf("confirmar")),
            { a -> sync { s ->
                if (!a.optBoolean("confirmar", false)) return@sync err("Pasa confirmar=true")
                s.clear()
                ok("Escena vacía")
            } }),
        ToolDef("token_generar", "Genera un nuevo token Bearer. En modo fijo (por defecto) devuelve el token estático sin cambiarlo.", schema(),
            { _ ->
                val t = AuthManager.generar(AppModel.context())
                JSONObject().put("token", t).put("modo", if (AuthManager.esRotativo(AppModel.context())) "rotativo" else "fijo").toString()
            }),
        ToolDef("token_revocar", "Revoca el token Bearer persistido (solo afecta al modo rotativo).", schema(),
            { _ ->
                AuthManager.revocar(AppModel.context())
                ok("Token revocado")
            }),
        ToolDef("malla_weld", "Reconstruye la topología de un objeto: solda vértices duplicados y genera índices.", schema(
            "id" to p("string", "Id del objeto"),
            "epsilon" to p("number", "Tolerancia de soldadura en metros (defecto 1e-5)")),
            { a -> mallaOperacion(a, "weld") }),
        ToolDef("malla_subdividir", "Subdivide la malla de un objeto: cada triángulo se parte en 4.", schema(
            "id" to p("string", "Id del objeto")),
            { a -> mallaOperacion(a, "subdividir") }),
        ToolDef("malla_extruir", "Extruye la malla de un objeto en la dirección de sus normales.", schema(
            "id" to p("string", "Id del objeto"),
            "cantidad" to p("number", "Distancia en metros (negativo reduce)")),
            { a -> mallaOperacion(a, "extruir") }),
        ToolDef("malla_bisel", "Bisela la malla de un objeto (contracción hacia el centro del triángulo).", schema(
            "id" to p("string", "Id del objeto"),
            "factor" to p("number", "0 = sin cambio, 1 = colapsa al centro")),
            { a -> mallaOperacion(a, "bisel") }),
        ToolDef("malla_normales", "Recalcula normales suavizadas (promedia las normales de las caras vecinas).", schema(
            "id" to p("string", "Id del objeto")),
            { a -> mallaOperacion(a, "normales") }),
        ToolDef("malla_estadisticas", "Estadísticas topológicas de un objeto: vértices, aristas y triángulos únicos.", schema(
            "id" to p("string", "Id del objeto")),
            { a -> mallaOperacion(a, "estadisticas") }),
        ToolDef("info_memoria", "Estado de memoria: presión, presupuesto de heap, pools, calidad gráfica y capacidades del dispositivo.", schema(),
            { _ -> infoMemoria() }),
        ToolDef("aplicar_textura", "Asigna mapas de textura PBR a un objeto: albedo, normal, roughness, metalness y AO (rutas dentro del almacenamiento de la app).", schema(
            "id" to p("string", "Id del objeto"),
            "albedo" to p("string", "Ruta de la textura de color base"),
            "normal" to p("string", "Ruta del mapa de normales"),
            "roughness" to p("string", "Ruta del mapa de rugosidad"),
            "metalness" to p("string", "Ruta del mapa de metalicidad"),
            "ao" to p("string", "Ruta del mapa de oclusión ambiental")),
            { a -> aplicarTextura(a) }),
        ToolDef("malla_uv", "Genera UVs automáticas (proyección planar) para un objeto sin coordenadas de textura.", schema(
            "id" to p("string", "Id del objeto")),
            { a -> mallaOperacion(a, "uv") })
    )

    // ---------- helpers ----------

    private inline fun <T> sync(block: (com.forge3d.studio.engine.Scene) -> T): T =
        synchronized(AppModel.lock) { block(AppModel.scene) }

    private fun ok(msg: String): String = JSONObject().put("resultado", msg).toString()

    private fun err(msg: String): String = JSONObject().put("error", msg).toString()

    private fun f3(a: FloatArray): JSONArray =
        JSONArray(listOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble()))

    private fun crearPrimitiva(tipo: String, a: JSONObject): String = sync { s ->
        if (!Primitives.validTypes().contains(tipo)) return@sync err("Tipo inválido: $tipo")
        val mesh = Primitives.create(tipo) ?: return@sync err("No se pudo crear")
        val obj = SceneObject(s.nextId(), a.optString("nombre", "").ifBlank { s.autoName(tipo) }, mesh)
        obj.tipo = "primitiva"
        obj.primitiva = tipo
        val pos = a.optJSONArray("posicion")
        if (pos != null && pos.length() == 3) {
            obj.position[0] = pos.getDouble(0).toFloat()
            obj.position[1] = pos.getDouble(1).toFloat()
            obj.position[2] = pos.getDouble(2).toFloat()
        }
        val esc = a.optJSONArray("escala")
        if (esc != null && esc.length() == 3) {
            obj.scale[0] = esc.getDouble(0).toFloat()
            obj.scale[1] = esc.getDouble(1).toFloat()
            obj.scale[2] = esc.getDouble(2).toFloat()
        }
        if (!s.add(obj)) return@sync err("Límite alcanzado (300 objetos / 1M triángulos)")
        s.selected = obj.id
        objJson(obj)
    }

    private fun transformar(a: JSONObject): String = sync { s ->
        val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
        val modo = a.optString("modo")
        val v = a.optJSONArray("valor")
        if (v == null || v.length() != 3) return@sync err("valor debe ser [x,y,z]")
        val rel = a.optBoolean("relativo", true)
        when (modo) {
            "mover" -> {
                if (rel) for (i in 0..2) o.position[i] += v.getDouble(i).toFloat()
                else for (i in 0..2) o.position[i] = v.getDouble(i).toFloat()
            }
            "rotar" -> {
                if (rel) for (i in 0..2) o.rotation[i] = (o.rotation[i] + v.getDouble(i).toFloat()) % 360f
                else for (i in 0..2) o.rotation[i] = v.getDouble(i).toFloat() % 360f
            }
            "escalar" -> {
                if (rel) for (i in 0..2) o.scale[i] = (o.scale[i] * v.getDouble(i).toFloat()).coerceIn(0.02f, 50f)
                else for (i in 0..2) o.scale[i] = v.getDouble(i).toFloat().coerceIn(0.02f, 50f)
            }
            else -> return@sync err("modo inválido: mover|rotar|escalar")
        }
        objJson(o)
    }

    private fun aplicarMaterial(a: JSONObject): String = sync { s ->
        val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
        val m = a.optJSONObject("material") ?: return@sync err("material requerido")
        val mt = Material()
        m.optJSONArray("baseColor")?.let { bc ->
            if (bc.length() >= 3) {
                val al = if (bc.length() >= 4) bc.getDouble(3).toFloat() else 1f
                mt.baseColor = floatArrayOf(
                    bc.getDouble(0).toFloat(), bc.getDouble(1).toFloat(),
                    bc.getDouble(2).toFloat(), al)
            }
        }
        m.optString("baseColorHex", "").takeIf { it.isNotBlank() }?.let {
            Material.parseHex(it)?.let { c -> mt.baseColor = c }
        }
        if (m.has("metallic")) mt.metallic = m.getDouble("metallic").toFloat().coerceIn(0f, 1f)
        if (m.has("roughness")) mt.roughness = m.getDouble("roughness").toFloat().coerceIn(0f, 1f)
        m.optJSONArray("emissive")?.let { em ->
            if (em.length() == 3) mt.emissive = floatArrayOf(
                em.getDouble(0).toFloat(), em.getDouble(1).toFloat(), em.getDouble(2).toFloat())
        }
        m.optString("alphaMode", "").takeIf { it.isNotBlank() }?.let { mt.alphaMode = it.uppercase() }
        if (m.has("alphaCutoff")) mt.alphaCutoff = m.getDouble("alphaCutoff").toFloat().coerceIn(0f, 1f)
        o.material.applyFrom(mt)
        objJson(o)
    }

    /**
     * Operaciones de malla sobre un objeto de la escena.
     *
     * Todas se ejecutan bajo el lock de la escena y con el guard de memoria, de modo que
     * un OOM no tumbe el proceso sino que degrade la calidad y devuelva un error claro.
     */
    private fun mallaOperacion(a: JSONObject, op: String): String = sync { s ->
        val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")

        // Las estadísticas no consumen memoria apreciable: se resuelven fuera del guard.
        if (op == "estadisticas") {
            val st = MeshOps.stats(o.mesh)
            return@sync JSONObject()
                .put("id", o.id)
                .put("vertices_unicos", st.first)
                .put("aristas_unicas", st.second)
                .put("triangulos", st.third)
                .toString()
        }

        val resultado: String? = MemoryManager.withMemoryGuard {
            when (op) {
                "weld" -> {
                    val eps = a.optDouble("epsilon", MeshOps.WELD_EPSILON.toDouble()).toFloat()
                    val top = MeshOps.weld(o.mesh, eps)
                    val antes = o.mesh.triangleCount
                    o.mesh = MeshOps.unwrap(top)
                    "weld: $antes tri -> ${o.mesh.triangleCount} tri, ${top.vertCount} vertices unicos"
                }

                "subdividir" -> {
                    val antes = o.mesh.triangleCount
                    o.mesh = MeshOps.subdivide(o.mesh)
                    "subdividir: $antes tri -> ${o.mesh.triangleCount} tri"
                }

                "extruir" -> {
                    val cant = a.optDouble("cantidad", 0.05).toFloat()
                    o.mesh = MeshOps.extrude(o.mesh, cant)
                    "extruir: $cant m"
                }

                "bisel" -> {
                    val f = a.optDouble("factor", 0.15).toFloat()
                    o.mesh = MeshOps.bevel(o.mesh, f)
                    "bisel: factor $f"
                }

                "normales" -> {
                    MeshOps.smoothNormals(o.mesh)
                    "normales recalculadas (suavizadas)"
                }

                "uv" -> {
                    o.mesh.generatePlanarUv()
                    "uvs generadas (proyeccion planar): ${o.mesh.vertCount} vertices"
                }

                else -> "Operación desconocida: $op"
            }
        }
        if (resultado == null) {
            err("No se pudo completar '$op': presión de memoria crítica")
        } else if (resultado.startsWith("Operación")) {
            err(resultado)
        } else {
            JSONObject().put("resultado", resultado).put("objeto", o.id).toString()
        }
    }

    /**
     * Asigna mapas de textura a un objeto.
     *
     * Las rutas se resuelven dentro del almacenamiento de la app (`Datos/` o ruta absoluta
     * permitida). Se comprueba la existencia del archivo antes de asignarlo, para que el
     * agente reciba un error claro en vez de una textura rota.
     */
    private fun aplicarTextura(a: JSONObject): String = sync { s ->
        val o = s.byId(a.optString("id")) ?: return@sync err("No existe ${a.optString("id")}")
        var asignadas = 0
        val faltan = StringBuilder()

        fun asignar(clave: String, setter: (String?) -> Unit) {
            val ruta = a.optString(clave, "").trim()
            if (ruta.isEmpty()) return
            val f = File(ruta)
            if (!f.exists()) {
                faltan.append(clave).append(" ")
                return
            }
            setter(f.absolutePath)
            asignadas++
        }

        asignar("albedo") { o.material.albedoPath = it }
        asignar("normal") { o.material.normalPath = it }
        asignar("roughness") { o.material.roughnessPath = it }
        asignar("metalness") { o.material.metalnessPath = it }
        asignar("ao") { o.material.aoPath = it }

        if (asignadas == 0) {
            err("No se asigno ninguna textura. Revisa las rutas${if (faltan.isNotEmpty()) " (faltan: $faltan)" else ""}")
        } else {
            if (!o.mesh.hasUv) o.mesh.generatePlanarUv()
            JSONObject()
                .put("resultado", "$asignadas textura(s) asignadas")
                .put("objeto", o.id)
                .put("uvs", o.mesh.hasUv)
                .toString()
        }
    }

    /** Diagnóstico de memoria y capacidades del dispositivo. */
    private fun infoMemoria(): String {
        val p = MemoryManager.capabilities()
        val snap = MemoryManager.snapshot()
        val o = JSONObject()
        o.put("presion_memoria", snap.presion)
        o.put("calidad_grafica", snap.calidad)
        o.put("heap_usado_mb", snap.heapUsado / 1024 / 1024)
        o.put("heap_nativo_mb", snap.heapNativo / 1024 / 1024)
        o.put("pool_bloques", snap.poolBloques)
        if (p != null) {
            o.put("dispositivo", p.model)
            o.put("sdk", p.sdkInt)
            o.put("ram_total_mb", p.totalRamBytes / 1024 / 1024)
            o.put("ram_libre_mb", DeviceCapabilities.availableRam(AppModel.context()) / 1024 / 1024)
            o.put("presupuesto_heap_mb", p.appHeapBudget / 1024 / 1024)
            o.put("presupuesto_texturas_mb", p.textureBudget / 1024 / 1024)
            o.put("formato_textura", p.textureFormat.name)
            o.put("soporta_astc", p.supportsAstc)
            o.put("soporta_etc2", p.supportsEtc2)
            o.put("soporta_vulkan", p.supportsVulkan)
            o.put("max_textura", p.maxTextureDimension(MemoryManager.quality()))
        }
        return o.toString()
    }

    private fun objJson(o: SceneObject): String {
        val b = o.worldBounds(FloatArray(6))
        return JSONObject()
            .put("id", o.id).put("nombre", o.name).put("tipo", o.tipo)
            .put("posicion", f3(o.position)).put("rotacion", f3(o.rotation)).put("escala", f3(o.scale))
            .put("triangulos", o.mesh.triangleCount)
            .put("bounds_mundo", JSONArray(listOf(
                b[0].toDouble(), b[1].toDouble(), b[2].toDouble(),
                b[3].toDouble(), b[4].toDouble(), b[5].toDouble())))
            .put("material", JSONObject()
                .put("baseColor", JSONArray(listOf(
                    o.material.baseColor[0].toDouble(), o.material.baseColor[1].toDouble(),
                    o.material.baseColor[2].toDouble(), o.material.baseColor[3].toDouble())))
                .put("metallic", o.material.metallic.toDouble())
                .put("roughness", o.material.roughness.toDouble())
                .put("alphaMode", o.material.alphaMode))
            .toString()
    }

    private fun listarEscena(): String = sync { s ->
        val arr = JSONArray()
        for (o in s.objects) {
            arr.put(JSONObject()
                .put("id", o.id).put("nombre", o.name).put("tipo", o.tipo)
                .put("posicion", f3(o.position)).put("escala", f3(o.scale))
                .put("triangulos", o.mesh.triangleCount))
        }
        JSONObject().put("objetos", arr).put("total", arr.length())
            .put("triangulos", s.totalTris()).toString()
    }

    private fun infoServidor(): String = sync { s ->
        JSONObject()
            .put("app", "Forge3D Studio")
            .put("version", "1.0.0")
            .put("protocolo", "MCP Streamable HTTP (JSON-RPC 2.0)")
            .put("host", "127.0.0.1")
            .put("puerto", 8080)
            .put("ruta", "/mcp")
            .put("token_activo", AuthManager.token(AppModel.context()) != null)
            .put("objetos", s.objects.size)
            .put("triangulos", s.totalTris())
            .put("limites", JSONObject().put("objetos", Limits.MAX_OBJECTS).put("triangulos", Limits.MAX_TRIS))
            .toString()
    }

    private fun exportarGltf(a: JSONObject): String = sync { s ->
        val ids = a.optJSONArray("ids")
        val objs = if (ids != null && ids.length() > 0) {
            val list = ArrayList<SceneObject>()
            for (i in 0 until ids.length()) {
                s.byId(ids.optString(i))?.let { list.add(it) }
            }
            if (list.isEmpty()) return@sync err("Ningún id válido")
            list
        } else {
            ArrayList(s.objects)
        }
        if (objs.isEmpty()) return@sync err("Escena vacía")
        val formato = a.optString("formato", "glb").lowercase()
        val nombre = a.optString("nombre", "").ifBlank {
            "escena_" + java.time.LocalTime.now().toString().replace(Regex("[^0-9]"), "").take(6)
        }
        val bytes = if (formato == "gltf") GltfExporter.buildGltf(objs) else GltfExporter.buildGlb(objs)
        val ext = if (formato == "gltf") "gltf" else "glb"
        val f = File(AppModel.exportDir(), "${ProjectStore.sanitize(nombre)}.$ext")
        f.writeBytes(bytes)
        JSONObject()
            .put("ruta", f.absolutePath)
            .put("bytes", bytes.size)
            .put("objetos", objs.size)
            .put("formato", ext)
            .toString()
    }

    private fun importarModelo(a: JSONObject): String {
        val ruta = a.optString("ruta")
        var f = File(ruta)
        if (!f.exists()) {
            val alt = File(AppModel.filesRoot(), ruta)
            if (alt.exists()) f = alt
        }
        if (!f.exists()) {
            val nombre = f.name
            val dl = File("/storage/emulated/0/Download/$nombre")
            if (dl.exists()) f = dl
        }
        if (!f.exists()) return err("Archivo no accesible: $ruta")
        val bytes = try {
            f.readBytes()
        } catch (e: Exception) {
            return err("Sin permiso de lectura: ${e.message}")
        }
        val mesh = importar(bytes, f.name) ?: return err("Formato no reconocido o archivo inválido")
        return sync { s ->
            val obj = SceneObject(s.nextId(), f.nameWithoutExtension, mesh)
            obj.tipo = "importado"
            if (!s.add(obj)) return@sync err("Límite alcanzado (300 objetos / 1M triángulos)")
            s.selected = obj.id
            JSONObject()
                .put("id", obj.id).put("nombre", obj.name)
                .put("triangulos", mesh.triangleCount)
                .put("bounds", JSONArray(listOf(
                    mesh.minX.toDouble(), mesh.minY.toDouble(), mesh.minZ.toDouble(),
                    mesh.maxX.toDouble(), mesh.maxY.toDouble(), mesh.maxZ.toDouble())))
                .toString()
        }
    }

    /** Importa bytes a Mesh según formato (compartido con la UI). */
    fun importar(bytes: ByteArray, name: String): Mesh? {
        val lower = name.lowercase()
        val byExt = when {
            lower.endsWith(".obj") -> { ObjImporter.import(bytes, name) }
            lower.endsWith(".stl") -> { StlImporter.import(bytes, name) }
            lower.endsWith(".3mf") -> { ThreeMfImporter.import(bytes, name) }
            lower.endsWith(".fbx") -> { FbxImporter.import(bytes, name) }
            else -> null
        }
        if (byExt != null) return byExt
        // por contenido
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII)
        return when {
            bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                ThreeMfImporter.import(bytes, name)
            head.startsWith("Kaydara FBX Binary") || head.contains("FBXHeaderExtension") ->
                FbxImporter.import(bytes, name)
            head.trimStart().startsWith("solid") && head.contains("facet") ->
                StlImporter.import(bytes, name)
            else -> ObjImporter.import(bytes, name)
        }
    }

    fun listJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(JSONObject()
                .put("name", t.name)
                .put("description", t.desc)
                .put("inputSchema", t.schema))
        }
        return arr
    }

    /** Devuelve (isError, texto) */
    fun call(name: String, args: JSONObject): Pair<Boolean, String> {
        val t = tools.firstOrNull { it.name == name } ?: return Pair(true, err("Tool desconocida: $name"))
        return try {
            Pair(false, t.handler(args))
        } catch (e: Exception) {
            Pair(true, err("Error ejecutando $name: ${e.message}"))
        }
    }
}
