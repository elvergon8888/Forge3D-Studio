package com.forge3d.studio.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gestor de memoria reactivo.
 *
 * Responsabilidades:
 * 1. Recibir `onTrimMemory` / `onLowMemory` y liberar recursos (texturas, buffers GL, cachés).
 * 2. Mantener un pool de objetos reutilizables (mallas, listas de floats) para no fragmentar el heap.
 * 3. Degradar la calidad gráfica cuando la presión de memoria sube, en lugar de morir.
 * 4. Capturar `OutOfMemoryError` y `EGL_BAD_ALLOC` y responder con degradación, no con crash.
 *
 * Se registra como `ComponentCallbacks2` de la app (ver `Forge3DApp`), por lo que Android le
 * avisa automáticamente del trim.
 */
object MemoryManager {

    private const val TAG = "Forge3D/Memory"

    /** Nivel actual de presión de memoria: 0 = normal, cuanto más alto, peor. */
    @Volatile
    private var pressureLevel = 0

    @Volatile
    private var profile: DeviceCapabilities.Profile? = null

    /** Callbacks del renderer para poder liberar buffers GL desde cualquier hilo. */
    interface GlResourceOwner {
        /** Libera todos los buffers GPU y fuerza la reconstrucción en el siguiente frame. */
        fun releaseGlResources()

        /** Reduce la resolución de render (0.5 = metade de píxeles). */
        fun setRenderScale(scale: Float)

        /** Aplica la calidad actual (afecta a texturas y LOD). */
        fun applyQuality(q: DeviceCapabilities.Quality)
    }

    @Volatile
    private var glOwner: GlResourceOwner? = null

    /** Callbacks de la escena para soltar mallas no usadas. */
    interface SceneResourceOwner {
        /** Devuelve el número de mallas liberables. */
        fun purgeUnusedMeshes(keepAtLeast: Int): Int

        /** Notifica que la calidad cambió (p. ej. para desasignar texturas). */
        fun onQualityChanged(q: DeviceCapabilities.Quality)
    }

    @Volatile
    private var sceneOwner: SceneResourceOwner? = null

    // ---------------------------------------------------------------- pools

    /** Pool de buffers de floats para evitar reasignaciones en operaciones de malla. */
    private val floatPool = ConcurrentLinkedQueue<FloatArray>()
    private val pooledFloats = AtomicInteger(0)

    /** Tamaño por defecto de los buffers del pool. */
    private const val POOL_BLOCK = 4096

    /**
     * Obtiene un FloatArray del pool (o nuevo si el pool está vacío).
     * [len] es el número de floats que el llamante necesita; el array devuelto tiene al menos eso.
     */
    fun obtainFloats(len: Int): FloatArray {
        if (len <= 0) return FloatArray(0)
        var buf = floatPool.poll()
        while (buf != null && buf.size < len) {
            floatPool.offer(buf)
            buf = floatPool.poll()
        }
        if (buf == null) return FloatArray(maxOf(len, POOL_BLOCK))
        pooledFloats.decrementAndGet()
        return buf
    }

    /** Devuelve un FloatArray al pool si no es demasiado grande. */
    fun releaseFloats(buf: FloatArray) {
        if (buf.size > POOL_BLOCK * 8) return // no merece la pena retener buffers enormes
        floatPool.offer(buf)
        pooledFloats.incrementAndGet()
    }

    /** Vacía el pool (llamar en TRIM_MEMORY_UI_HIDDEN). */
    fun clearPools() {
        var n = 0
        while (floatPool.poll() != null) n++
        pooledFloats.set(0)
        Log.i(TAG, "Pool liberado: $n bloques de floats")
    }

    /** Número de bloques retenidos en el pool. */
    fun pooledBlockCount(): Int = pooledFloats.get()

    // ---------------------------------------------------------------- calidad

    /** Registra el perfil del dispositivo. */
    fun init(ctx: Context) {
        profile = DeviceCapabilities.detect(ctx)
        ctx.registerComponentCallbacks(Callbacks)
    }

    /** Perfil detectado del dispositivo. */
    fun capabilities(): DeviceCapabilities.Profile? = profile

    /** Registra los owners para poder liberar recursos. */
    fun bind(gl: GlResourceOwner?, scene: SceneResourceOwner?) {
        glOwner = gl
        sceneOwner = scene
    }

    /** Nivel de presión de memoria actual (0 = normal). */
    fun pressure(): Int = pressureLevel

    /** Calidad gráfica vigente. */
    fun quality(): DeviceCapabilities.Quality = profile?.initialQuality ?: DeviceCapabilities.Quality.MEDIUM

    // ---------------------------------------------------------------- trim

    /**
     * Callback de `ComponentCallbacks2`. Android lo invoca cuando el sistema necesita memoria.
     *
     * Estrategia por nivel:
     * - `TRIM_MEMORY_UI_HIDDEN`: la UI está oculta → es el momento seguro para soltar cachés.
     * - `TRIM_MEMORY_RUNNING_*`: la app es visible pero hay presión → bajar calidad.
     * - `TRIM_MEMORY_BACKGROUND` / `COMPLETE`: presión seria → degradar aggressively.
     */
    private object Callbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            handleTrim(level)
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

        override fun onLowMemory() {
            Log.w(TAG, "onLowMemory: presión crítica")
            pressureLevel = 3
            clearPools()
            glOwner?.releaseGlResources()
            glOwner?.setRenderScale(0.5f)
            sceneOwner?.purgeUnusedMeshes(0)
        }
    }

    /** Procesa un evento de trim de memoria. Público para tests. */
    fun handleTrim(level: Int) {
        val p = profile
        Log.i(TAG, "onTrimMemory level=$level")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                pressureLevel = 3
                clearPools()
                glOwner?.releaseGlResources()
                glOwner?.setRenderScale(0.5f)
                sceneOwner?.purgeUnusedMeshes(0)
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                pressureLevel = 2
                clearPools()
                glOwner?.setRenderScale(0.75f)
                sceneOwner?.purgeUnusedMeshes(4)
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                pressureLevel = 2
                clearPools()
                glOwner?.setRenderScale(0.75f)
                sceneOwner?.purgeUnusedMeshes(4)
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                pressureLevel = 1
                clearPools()
                glOwner?.setRenderScale(0.85f)
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                pressureLevel = 1
                clearPools()
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // La UI no está visible: momento seguro para soltar todo lo recuperable.
                pressureLevel = 0
                clearPools()
                sceneOwner?.purgeUnusedMeshes(2)
            }

            else -> Unit
        }
        // Si el presupuesto se rebasa, degradar.
        p?.let {
            val used = DeviceCapabilities.usedHeap()
            if (used > it.appHeapBudget) {
                Log.w(TAG, "Heap ${used / 1024 / 1024}MB > presupuesto ${it.appHeapBudget / 1024 / 1024}MB: degradando")
                glOwner?.setRenderScale(0.75f)
                sceneOwner?.purgeUnusedMeshes(4)
            }
        }
    }

    // ---------------------------------------------------------------- OOM

    /**
     * Ejecuta [block] capturando `OutOfMemoryError` y `RuntimeException` de GPU.
     *
     * En lugar de dejar que el error mate el proceso, degrada la calidad y reintenta una vez.
     * Devuelve `null` si no fue posible completar la operación.
     */
    fun <T> withMemoryGuard(block: () -> T): T? {
        return try {
            block()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError capturado: degradando calidad", e)
            pressureLevel = 3
            clearPools()
            glOwner?.releaseGlResources()
            glOwner?.setRenderScale(0.5f)
            sceneOwner?.purgeUnusedMeshes(0)
            try {
                System.gc()
                block()
            } catch (e2: OutOfMemoryError) {
                Log.e(TAG, "OOM persistente: la operación se aborta de forma segura", e2)
                null
            } catch (e2: Throwable) {
                Log.e(TAG, "Fallo en reintento tras OOM", e2)
                null
            }
        } catch (e: Throwable) {
            // EGL_BAD_ALLOC y similares llegan aquí como RuntimeException.
            Log.e(TAG, "Error de GPU/memoria capturado: degradando", e)
            pressureLevel = 2
            glOwner?.setRenderScale(0.6f)
            null
        }
    }

    /** Snapshot para la tool `info_servidor` / diagnóstico. */
    fun snapshot(): JSONObjectLike = JSONObjectLike(
        presion = pressureLevel,
        poolBloques = pooledFloats.get(),
        heapUsado = DeviceCapabilities.usedHeap(),
        heapNativo = DeviceCapabilities.nativeHeapBytes(),
        calidad = quality().name,
        minSdk = Build.VERSION.SDK_INT
    )

    /** Estructura ligera para serializar el estado de memoria a JSON. */
    data class JSONObjectLike(
        val presion: Int,
        val poolBloques: Int,
        val heapUsado: Long,
        val heapNativo: Long,
        val calidad: String,
        val minSdk: Int
    )
}
