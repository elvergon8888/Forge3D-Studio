package com.forge3d.studio.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.forge3d.studio.R

/** Servicio en primer plano que mantiene el servidor MCP vivo. */
class McpService : Service() {

    companion object {
        const val CHANNEL_ID = "mcp_status"
        const val NOTIF_ID = 1
        const val EXTRA_LAN = "lan"

        @Volatile var server: McpServer? = null
            private set

        val running: Boolean get() = server?.running == true

        fun start(ctx: Context, lan: Boolean) {
            val i = Intent(ctx, McpService::class.java)
            i.putExtra(EXTRA_LAN, lan)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val lan = intent?.getBooleanExtra(EXTRA_LAN, false) ?: false
        if (server == null || !server!!.running) {
            val s = McpServer(bindAny = lan)
            Thread {
                s.start()
            }.start()
            server = s
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.mcp_channel_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val title = getString(R.string.mcp_notif_title)
        val text = getString(R.string.mcp_notif_text)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        }
    }
}
