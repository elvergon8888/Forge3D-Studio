package com.forge3d.studio.mcp.server.tools

import com.forge3d.studio.core.engine.MemoryManager
import com.forge3d.studio.core.math.Mat4
import com.forge3d.studio.core.scene.*
import com.forge3d.studio.core.primitives.Primitives
import com.forge3d.studio.mcp.server.ToolDef

/**
 * Herramientas MCP básicas de modelado 3D.
 * Operan sobre una escena compartida en memoria.
 */
class SceneTools {
    private val scene = Scene()

    fun registerAll(): List<ToolDef> = listOf(
        ToolDef("info_servidor", "Información del servidor MCP y escena.",
            mapOf(), { _ ->
                mapOf(
                    "app" to "Forge3D Studio", "version" to "1.1.0",
                    "objetos" to scene.objects.size,
                    "triangulos" to scene.totalTris(),
                    "memoria" to MemoryManager.snapshot()
                )
            }),
        ToolDef("listar_escena", "Lista todos los objetos de la escena.",
            mapOf(), { _ ->
                mapOf("objetos" to scene.objects.map { o ->
                    mapOf("id" to o.id, "nombre" to o.name, "tipo" to o.tipo,
                        "posicion" to o.position, "triangulos" to o.mesh.triangleCount)
                })
            }),
        ToolDef("crear_cubo", "Crea un cubo de 1m de arista en la escena.",
            mapOf("nombre" to mapOf("type" to "string"), "posicion" to mapOf("type" to "object")),
            { args ->
                val mesh = Primitives.cube()
                val obj = SceneObject(scene.nextId(), args["nombre"] as? String ?: "Cubo", mesh)
                obj.position[0] = (args["posicion"] as? Map<*, *>?.get("x") ?: 0f).toFloat()
                obj.position[1] = (args["posicion"] as? Map<*, *>?.get("y") ?: 0.5f).toFloat()
                obj.position[2] = (args["posicion"] as? Map<*, *>?.get("z") ?: 0f).toFloat()
                obj.primitiva = Primitives.CUBE
                if (!scene.add(obj)) throw IllegalStateException("Límite de escena alcanzado")
                scene.selected = obj.id
                mapOf("id" to obj.id, "nombre" to obj.name, "triangulos" to obj.mesh.triangleCount)
            })
    )
}
