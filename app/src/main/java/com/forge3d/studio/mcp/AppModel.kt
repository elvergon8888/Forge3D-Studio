package com.forge3d.studio.mcp

import android.content.Context
import com.forge3d.studio.engine.Camera3D
import com.forge3d.studio.engine.Scene
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Estado global compartido entre UI (hilo principal), render GL y servidor MCP. */
object AppModel {
    /** Candado global para toda mutación de escena/cámara. */
    val lock = Any()

    val scene = Scene()
    val camera = Camera3D()

    @Volatile var appContext: Context? = null

    /** Mallas cuyo VBO debe liberarse en el hilo GL. */
    val meshDeleteQueue = ConcurrentLinkedQueue<com.forge3d.studio.engine.Mesh>()

    fun context(): Context = appContext
        ?: throw IllegalStateException("AppModel.appContext no inicializado")

    fun filesRoot(): File {
        val base = context().getExternalFilesDir(null)
        val d = File(base ?: context().filesDir, "Datos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun projectsDir(): File {
        val d = File(filesRoot(), "Proyectos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun exportDir(): File {
        val d = File(filesRoot(), "Export")
        if (!d.exists()) d.mkdirs()
        return d
    }
}
