package com.forge3d.studio.mcp

import android.content.Context
import java.security.SecureRandom

/** Token Bearer del servidor MCP, generado y revocable desde la UI. */
object AuthManager {

    private const val PREFS = "forge3d_mcp"
    private const val KEY = "bearer"
    private val rng = SecureRandom()

    fun token(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun generar(ctx: Context): String {
        val b = ByteArray(32)
        rng.nextBytes(b)
        val t = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, t).commit()
        return t
    }

    fun revocar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()
    }
}
