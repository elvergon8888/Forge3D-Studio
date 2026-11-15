package com.forge3d.studio

import com.forge3d.studio.engine.Animation
import com.forge3d.studio.engine.Primitives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la animación esquelética: huesos, jerarquía, clips y skinning.
 */
class AnimationTest {

    @Test
    fun `el esqueleto resuelve la jerarquia padre hijo`() {
        val sk = Animation.Skeleton()
        sk.add(Animation.Bone("raiz", null, floatArrayOf(0f, 1f, 0f)))
        sk.add(Animation.Bone("hijo", "raiz", floatArrayOf(0f, 1f, 0f)))
        sk.updateWorldMatrices()
        val hijo = sk.byName("hijo")!!
        // El hijo está a 2m de altura: raíz(1m en Y) + hijo(1m en Y) => Y=2
        assertEquals(2f, hijo.worldMatrix[13], 1e-4f)
    }

    @Test
    fun `los ciclos en la jerarquia no cuelgan la recursion`() {
        val sk = Animation.Skeleton()
        sk.add(Animation.Bone("a", "b"))
        sk.add(Animation.Bone("b", "a"))
        // Debe completar sin_STACK_OVERFLOW
        sk.updateWorldMatrices()
        assertEquals(2, sk.bones.size)
    }

    @Test
    fun `un clip interpola entre keyframes`() {
        val sk = Animation.Skeleton()
        sk.add(Animation.Bone("hueso"))
        val clip = Animation.AnimationClip("clip")
        val t = clip.track("hueso")
        t.addPosition(0f, floatArrayOf(0f, 0f, 0f))
        t.addPosition(1f, floatArrayOf(0f, 10f, 0f))
        clip.updateDuration()
        assertEquals(1f, clip.duration, 1e-4f)

        sk.applyClip(clip, 0.5f)
        val b = sk.byName("hueso")!!
        assertEquals(5f, b.localPosition[1], 1e-3f)
    }

    @Test
    fun `la duracion del clip es el maximo de los tiempos`() {
        val clip = Animation.AnimationClip("c")
        val t = clip.track("x")
        t.addPosition(0f, floatArrayOf(0f, 0f, 0f))
        t.addPosition(2.5f, floatArrayOf(1f, 1f, 1f))
        clip.updateDuration()
        assertEquals(2.5f, clip.duration, 1e-4f)
    }

    @Test
    fun `el skinning automatico asigna pesos validos`() {
        val sk = Animation.Skeleton()
        sk.add(Animation.Bone("a"))
        sk.add(Animation.Bone("b"))
        sk.updateWorldMatrices()

        val mesh = Primitives.cube()!!
        val skin = Animation.Skin(maxInfluences = 4)
        skin.autoAssign(sk, mesh.pos, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), 0.5f)

        // Los pesos de cada vertice deben sumar ~1
        val v = mesh.vertCount
        for (i in 0 until v) {
            var sum = 0f
            for (k in 0 until 4) sum += skin.weight[i * 4 + k]
            assertTrue("los pesos deben sumar 1 (vertice $i, suma=$sum)", sum > 0.9f && sum < 1.1f)
        }
    }

    @Test
    fun `deformar una malla no cambia el numero de triangulos`() {
        val sk = Animation.Skeleton()
        sk.add(Animation.Bone("a"))
        sk.updateWorldMatrices()
        val mesh = Primitives.cube()!!
        val skin = Animation.Skin()
        skin.autoAssign(sk, mesh.pos, floatArrayOf(0f, 0f, 0f), 0.5f)
        val out = Animation.deformMesh(mesh, sk, skin)
        assertEquals(mesh.triangleCount, out.triangleCount)
    }
}
