package io.embodit.abalonepush.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.abalonepush.domain.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The abstract marks of the volume (rings, the gutter glow) and the invisible collider shapes. The visible models
 * (board, marbles, direction pads) are GLBs built by tools/build_models.py; only abstract marks are SDK meshes.
 * Creating a resource waits on the renderer, so everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var ringMarble: MeshResource; private set
    lateinit var ringCell: MeshResource; private set
    lateinit var ringPad: MeshResource; private set
    lateinit var ringSmall: MeshResource; private set
    lateinit var glowPlane: MeshResource; private set

    lateinit var cream: Material; private set
    lateinit var amber: Material; private set
    lateinit var red: Material; private set
    lateinit var joinable: Material; private set
    lateinit var last: Material; private set
    lateinit var hover: Material; private set
    lateinit var glow: Material; private set

    lateinit var cellShape: ShapeResource; private set
    lateinit var marbleShape: ShapeResource; private set
    lateinit var padShape: ShapeResource; private set
    lateinit var boardShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                // rings lie in the board plane around the marble's foot (marble R 17 mm): selection 19..23 mm
                async(io) { r.ringMarble = MeshResource.createTorus(outerRingRadius = 0.0235f, innerRingRadius = 0.0190f).keep() },
                async(io) { r.ringCell = MeshResource.createTorus(outerRingRadius = 0.0225f, innerRingRadius = 0.0185f).keep() },
                async(io) { r.ringPad = MeshResource.createTorus(outerRingRadius = 0.0215f, innerRingRadius = 0.0180f).keep() },
                async(io) { r.ringSmall = MeshResource.createTorus(outerRingRadius = 0.0215f, innerRingRadius = 0.0195f).keep() },
                async(io) { r.glowPlane = MeshResource.createPlane(0.13f, 0.04f, 0.004f).keep() },
                async(io) { r.cream = unlit(Color(0xFFF5F0E6), 0.95f).keep() },
                async(io) { r.amber = unlit(Color(0xFFD99A2B), 0.95f).keep() },
                async(io) { r.red = unlit(Color(0xFFC8423F), 0.95f).keep() },
                async(io) { r.joinable = unlit(Color(0xFFBFE3EC), 0.55f).keep() },
                async(io) { r.last = unlit(Color(0xFF7FB8D0), 0.45f).keep() },
                async(io) { r.hover = unlit(Color(0xFFFFFFFF), 0.45f).keep() },
                async(io) { r.glow = unlit(Color(0xFFF2B84C), 0.6f).keep() },
                async(io) { r.cellShape = ShapeResource.createBox(Vector3(Layout.CELL_HIT, 0.003f, Layout.CELL_HIT)).keep() },
                async(io) { r.marbleShape = ShapeResource.createSphere(0.019f).keep() },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(Layout.PAD_HIT, 0.006f, Layout.PAD_HIT)).keep() },
                async(io) { r.boardShape = ShapeResource.createBox(Vector3(2 * Layout.BOARD_R, Layout.BOARD_T, 2 * Layout.BOARD_APOTHEM)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
