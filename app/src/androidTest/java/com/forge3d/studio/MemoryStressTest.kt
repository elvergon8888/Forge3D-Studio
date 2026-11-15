package com.forge3d.studio

import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge3d.studio.engine.DeviceCapabilities
import com.forge3d.studio.engine.MemoryManager
import com.forge3d.studio.engine.Primitives
import com.forge3d.studio.mcp.AppModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de estabilidad y presión de memoria.
 *
 * Simulan las condiciones que más matan a una app 3D en Android:
 * - `onTrimMemory` en todos sus niveles (el sistema avisa antes de matar el proceso).
 * - Pérdida y restauración de la app (la UI se oculta y vuelve).
 * - Carga repetida de modelos para forzar fragmentación del heap.
 *
 * Requieren un dispositivo o emulador: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStressTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun elPerfilDelDispositivoSeDetecta() {
        MemoryManager.init(ctx)
        val p = MemoryManager.capabilities()
        assertNotNull("debe detectarse el perfil del dispositivo", p)
        assertTrue("el presupuesto de heap debe ser positivo", p!!.appHeapBudget > 0)
        assertTrue("el presupuesto de texturas debe caber en el de heap", p.textureBudget < p.appHeapBudget)
    }

    @Test
    fun todosLosNivelesDeTrimSeProcesanSinCrash() {
        MemoryManager.init(ctx)
        val niveles = intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )
        for (n in niveles) {
            MemoryManager.handleTrim(n)
            assertTrue("la presión no puede ser negativa", MemoryManager.pressure() >= 0)
        }
    }

    @Test
    fun cargarDiezModelosSeguidosNoRevientaElHeap() {
        MemoryManager.init(ctx)
        for (i in 0 until 10) {
            val mesh = Primitives.sphere(segU = 32, segV = 20)
            assertTrue("la malla debe tener geometria", mesh.triangleCount > 0)
            // Simula el ciclo: se dibuja y se suelta
            mesh.glBuffer = 0
        }
        // Tras la carga, el pool debe estar disponible para reutilizar
        val buf = MemoryManager.obtainFloats(2048)
        assertTrue(buf.size >= 2048)
        MemoryManager.releaseFloats(buf)
    }

    @Test
    fun ocultarYRestaurarLaAppConservaLaEscena() {
        MemoryManager.init(ctx)
        val antes = AppModel.scene.objects.size
        // UI oculta: Android envía TRIM_MEMORY_UI_HIDDEN
        MemoryManager.handleTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        // La escena no debe perder objetos por un trim
        assertEquals(antes, AppModel.scene.objects.size)
    }

    @Test
    fun elGuardDeMemoriaAbsorbeUnaExcepcion() {
        MemoryManager.init(ctx)
        var resultado: String? = null
        val r = MemoryManager.withMemoryGuard<String> {
            throw RuntimeException("EGL_BAD_ALLOC simulado")
        }
        resultado = r
        // La app debe sobrevivir: el guard devuelve null en lugar de propagar
        assertEquals(null, resultado)
    }

    @Test
    fun elGuardDeMemoriaDevuelveElValorNormal() {
        MemoryManager.init(ctx)
        val r = MemoryManager.withMemoryGuard { "ok" }
        assertEquals("ok", r)
    }

    @Test
    fun laCalidadSeAjustaAlDispositivo() {
        MemoryManager.init(ctx)
        val p = MemoryManager.capabilities()!!
        val maxTex = p.maxTextureDimension(DeviceCapabilities.Quality.LOW)
        assertTrue("la calidad baja debe limitar la textura", maxTex <= 1024)
    }
}
