package com.forge3d.studio.app

import android.app.Application
import com.forge3d.studio.mcp.server.McpServer
import com.forge3d.studio.mcp.server.tools.SceneTools

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val sceneTools = SceneTools()
        McpServer.registerAll(sceneTools.registerAll())
        McpServer.start(port = 8080, host = "127.0.0.1")
    }
}
