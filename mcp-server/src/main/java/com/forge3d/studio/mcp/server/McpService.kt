package com.forge3d.studio.mcp.server

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class McpService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        McpServer.start(port = 8080, host = "127.0.0.1")
        return android.app.Service.START_STICKY
    }
}
