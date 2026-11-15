package com.forge3d.studio.core.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object MemoryManager {
    private const val TAG = "Forge3D/Memory"
    @Volatile private var pressureLevel = 0; @Volatile private var profile: DeviceCapabilities.Profile? = null
    interface GlResourceOwner { fun releaseGlResources(); fun setRenderScale(scale: Float); fun applyQuality(q: DeviceCapabilities.Quality) }
    @Volatile private var glOwner: GlResourceOwner? = null
    interface SceneResourceOwner { fun purgeUnusedMeshes(keepAtLeast: Int): Int; fun onQualityChanged(q: DeviceCapabilities.Quality) }
    @Volatile private var sceneOwner: SceneResourceOwner? = null
    private val floatPool = ConcurrentLinkedQueue<FloatArray>(); private val pooledFloats = AtomicInteger(0); private const val POOL_BLOCK = 4096
    fun obtainFloats(len: Int): FloatArray { if (len <= 0) return FloatArray(0); var buf = floatPool.poll(); while (buf != null && buf.size < len) { floatPool.offer(buf); buf = floatPool.poll() }; if (buf == null) return FloatArray(kotlin.math.maxOf(len, POOL_BLOCK)); pooledFloats.decrementAndGet(); return buf }
    fun releaseFloats(buf: FloatArray) { if (buf.size > POOL_BLOCK * 8) return; floatPool.offer(buf); pooledFloats.incrementAndGet() }
    fun clearPools() { var n = 0; while (floatPool.poll() != null) n++; pooledFloats.set(0); Log.i(TAG, "Pool liberado: $n bloques") }
    fun init(ctx: Context) { profile = DeviceCapabilities.detect(ctx); ctx.registerComponentCallbacks(Callbacks) }
    fun capabilities(): DeviceCapabilities.Profile? = profile
    fun bind(gl: GlResourceOwner?, scene: SceneResourceOwner?) { glOwner = gl; sceneOwner = scene }
    fun pressure(): Int = pressureLevel
    fun quality(): DeviceCapabilities.Quality = profile?.initialQuality ?: DeviceCapabilities.Quality.MEDIUM
    private object Callbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { handleTrim(level) }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
        override fun onLowMemory() { Log.w(TAG, "onLowMemory: presión crítica"); pressureLevel = 3; clearPools(); glOwner?.releaseGlResources(); glOwner?.setRenderScale(0.5f); sceneOwner?.purgeUnusedMeshes(0) }
    }
    fun handleTrim(level: Int) {
        Log.i(TAG, "onTrimMemory level=$level")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> { pressureLevel = 3; clearPools(); glOwner?.releaseGlResources(); glOwner?.setRenderScale(0.5f); sceneOwner?.purgeUnusedMeshes(0) }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> { pressureLevel = 2; clearPools(); glOwner?.setRenderScale(0.75f); sceneOwner?.purgeUnusedMeshes(4) }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> { pressureLevel = 2; clearPools(); glOwner?.setRenderScale(0.75f); sceneOwner?.purgeUnusedMeshes(4) }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> { pressureLevel = 1; clearPools(); glOwner?.setRenderScale(0.85f) }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> { pressureLevel = 1; clearPools() }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> { pressureLevel = 0; clearPools(); sceneOwner?.purgeUnusedMeshes(2) }
        }
        profile?.let { if (DeviceCapabilities.usedHeap() > it.appHeapBudget) { Log.w(TAG, "Heap sobre presupuesto: degradando"); glOwner?.setRenderScale(0.75f); sceneOwner?.purgeUnusedMeshes(4) } }
    }
    fun <T> withMemoryGuard(block: () -> T): T? = try { block() } catch (e: OutOfMemoryError) { Log.e(TAG, "OOM: degradando", e); pressureLevel = 3; clearPools(); glOwner?.releaseGlResources(); glOwner?.setRenderScale(0.5f); sceneOwner?.purgeUnusedMeshes(0); try { System.gc(); block() } catch (e2: OutOfMemoryError) { Log.e(TAG, "OOM persistente", e2); null } catch (e2: Throwable) { Log.e(TAG, "Fallo en reintento", e2); null } } catch (e: Throwable) { Log.e(TAG, "Error GPU/memoria: degradando", e); pressureLevel = 2; glOwner?.setRenderScale(0.6f); null }
    data class Snapshot(val presion: Int, val poolBloques: Int, val heapUsado: Long, val heapNativo: Long, val calidad: String, val minSdk: Int)
    fun snapshot(): Snapshot = Snapshot(pressureLevel, pooledFloats.get(), DeviceCapabilities.usedHeap(), DeviceCapabilities.nativeHeapBytes(), quality().name, android.os.Build.VERSION.SDK_INT)
    fun pooledBlockCount(): Int = pooledFloats.get()
}
