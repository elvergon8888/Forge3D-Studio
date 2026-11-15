package com.forge3d.studio.mcp

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Token Bearer del servidor MCP.
 *
 * ## Por qué el token es FIJO (v1.1.0)
 *
 * En v1.0.0 el token se regeneraba desde la UI ("GENERAR TOKEN") y se guardaba en
 * SharedPreferences. El problema: el servidor comparaba contra el valor guardado, así que
 * pulsar "GENERAR TOKEN" dejaba al servidor con el token ANTERIOR en memoria durante toda la
 * sesión y las peticiones empezaban a fallar con 401 aunque la UI mostrara el token nuevo.
 * Peor: en cada arranque de la app el token se regeneraba, rompiendo la conexión del agente.
 *
 * ## Diseño actual
 *
 * - [STATIC_TOKEN] es el token por defecto: **no cambia nunca**. Un agente configura su
 *   cabecera `Authorization` una sola vez y la conexión sobrevive a reinicios de la app.
 * - El modo rotativo sigue disponible para quien lo necesite de forma explícita
 *   (preferencias `forge3d_mcp` → `modo_token` = `rotativo`). En ese modo la rotación
 *   reinicia el servidor MCP para que el valor en memoria y el de la UI coincidan.
 * - `token_activo` se expone en la tool `info_servidor` para que el agente sepa con qué
 *   credencial Authenticarse.
 */
object AuthManager {

    /** Token fijo por defecto. Configura tu agente una sola vez y listo. */
    const val STATIC_TOKEN = "Forge3D-studio"

    private const val PREFS = "forge3d_mcp"
    private const val KEY = "bearer"
    private const val KEY_MODE = "modo_token"
    private const val MODE_ROTATIVO = "rotativo"

    private val rng = SecureRandom()

    /** `true` si el usuario/haforzado el modo rotativo (token que cambia). */
    fun esRotativo(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null) == MODE_ROTATIVO

    /**
     * Token con el que el servidor MCP debe validar las peticiones.
     *
     * En modo fijo devuelve siempre [STATIC_TOKEN]. En modo rotativo devuelve el valor
     * persistido (o genera uno si no existe).
     */
    fun token(ctx: Context): String {
        if (!esRotativo(ctx)) return STATIC_TOKEN
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardado = prefs.getString(KEY, null)
        if (guardado != null) return guardado
        val nuevo = generarNuevo()
        prefs.edit().putString(KEY, nuevo).commit()
        return nuevo
    }

    /**
     * Genera un token nuevo de 256 bits (Base64 URL-safe) y lo persiste.
     *
     * IMPORTANTE: solo tiene efecto en modo rotativo. En modo fijo devuelve el token
     * estático para no romper la conexión de los agentes ya configurados.
     */
    fun generar(ctx: Context): String {
        if (!esRotativo(ctx)) return STATIC_TOKEN
        val nuevo = generarNuevo()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, nuevo).commit()
        return nuevo
    }

    /**
     * Revoca el token persistido.
     *
     * En modo fijo esto NO invalida [STATIC_TOKEN]: el token fijo existe para que la
     * conexión del agente sea estable. Para invalidarlo de verdad hay que pasar a modo
     * rotativo, que es la operation inversa.
     */
    fun revocar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()
    }

    /** Activa/desactiva el modo rotativo. Devuelve el token vigente tras el cambio. */
    fun setModoRotativo(ctx: Context, rotativo: Boolean): String {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, if (rotativo) MODE_ROTATIVO else "fijo")
            .apply()
        return token(ctx)
    }

    private fun generarNuevo(): String {
        val b = ByteArray(32)
        rng.nextBytes(b)
        return Base64.encodeToString(
            b,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
