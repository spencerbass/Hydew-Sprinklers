package com.spenc.hydew.systems.events

import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.protocol.Color
import com.hypixel.hytale.protocol.Direction
import com.hypixel.hytale.protocol.Position
import com.hypixel.hytale.protocol.SoundCategory
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem
import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy
import com.hypixel.hytale.server.core.asset.type.blocktick.config.TickProcedure
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource
import com.hypixel.hytale.server.core.universe.world.SoundUtil
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

class SprinklerTickProcedure : TickProcedure() {

    override fun onTick(
        world: World,
        chunk: WorldChunk,
        x: Int,
        y: Int,
        z: Int,
        blockId: Int
    ): BlockTickStrategy {
        val blockType = chunk.getBlockType(x, y, z) ?: return BlockTickStrategy.IGNORED
        val tier = SprinklerTier.fromBlockId(blockType.id) ?: return BlockTickStrategy.IGNORED

        val wtr = world.entityStore.store.getResource(WorldTimeResource.getResourceType())
        val hour = wtr.currentHour
        if (hour != 5) return BlockTickStrategy.CONTINUE

        val gameTime = wtr.gameTime
        val minute = gameTime.atZone(ZoneOffset.UTC).minute
        if (minute > 13) return BlockTickStrategy.CONTINUE

        // Rate limit per sprinkler position
        val posKey = PosKey(world.name, x, y, z)
        val n = tickCounter.merge(posKey, 1) { a, b -> a + b } ?: 1
        if (n % 10 != 0) return BlockTickStrategy.CONTINUE

        // Particles
        spawnSprayParticles(world, x, y, z, tier.particleScale)

        // Sound once per sprinkler per day (not per player)
        val dayIndex = gameTime.epochSecond / SECONDS_PER_DAY
        playSprinklerSoundOncePerDay(world, posKey, dayIndex)

        // Water y-1 around sprinkler with ramping probability
        val chance = wateringChance(minute)
        waterSoilAround(world, tier, x, y - 1, z, gameTime, chance)

        return BlockTickStrategy.CONTINUE
    }

    // ========== Particles ==========

    private fun spawnSprayParticles(world: World, x: Int, y: Int, z: Int, scale: Float) {
        // (posX, posY, posZ, yaw, pitch, roll)
        for (e in EMITTERS) {
            val packet = SpawnParticleSystem(
                "Water_Splash",
                Position(x + e.px, y + e.py, z + e.pz),
                Direction(e.yaw, e.pitch, e.roll),
                scale,
                Color(0, 0, 127)
            )
            for (player in world.playerRefs) {
                player.packetHandler.writeNoCache(packet)
            }
        }
    }

    private data class Emitter(
        val px: Double, val py: Double, val pz: Double,
        val yaw: Float, val pitch: Float, val roll: Float
    )

    // ========== Sound (dedup per player per day) ==========

    private data class SoundKey(val pos: PosKey, val dayIndex: Long)

    private fun playSprinklerSoundOncePerDay(world: World, pos: PosKey, dayIndex: Long) {
        val soundIndex = sprinklerSoundIndex
        if (soundIndex == Int.MIN_VALUE || soundIndex == 0) return

        val key = SoundKey(pos, dayIndex)
        if (playedSound.putIfAbsent(key, true) != null) return

        // Optional: prevent unbounded growth by clearing old days occasionally
        // (cheap heuristic: if map gets big, drop everything except today)
        if (playedSound.size > 50_000) {
            playedSound.keys.removeIf { it.dayIndex != dayIndex }
        }

        SoundUtil.playSoundEvent3d(
            soundIndex,
            SoundCategory.SFX,
            pos.x.toDouble(),
            pos.y.toDouble(),
            pos.z.toDouble(),
            world.entityStore.store
        )
    }

    // ========== Watering ==========

    private fun wateringChance(minute: Int, endMinute: Int = 13): Double {
        val t = minute.coerceIn(0, endMinute) / endMinute.toDouble()
        return t * t // ease-in
    }

    private fun waterSoilAround(
        world: World,
        tier: SprinklerTier,
        sx: Int,
        soilY: Int,
        sz: Int,
        gameTime: Instant,
        chance: Double
    ) {
        val offsets = tier.offsets
        if (offsets.isEmpty()) return

        val wetUntil = gameTime.plus(SECONDS_PER_DAY, ChronoUnit.SECONDS)
        val tilledUntil = gameTime.plus(SECONDS_PER_DAY * 2, ChronoUnit.SECONDS)
        val rng = ThreadLocalRandom.current()

        world.execute {
            val chunkStore = world.chunkStore.store

            var i = 0
            while (i < offsets.size) {
                if (rng.nextDouble() < chance) {
                    val tx = sx + offsets[i]
                    val tz = sz + offsets[i + 1]

                    val chunkAt = world.getChunk(ChunkUtil.indexChunkFromBlock(tx, tz)) ?: run {
                        i += 2
                        continue
                    }

                    val blockRef = chunkAt.getBlockComponentEntity(tx, soilY, tz)
                        ?: BlockModule.ensureBlockEntity(chunkAt, tx, soilY, tz)
                        ?: run {
                            i += 2
                            continue
                        }

                    val soil = chunkStore.getComponent(blockRef, TilledSoilBlock.getComponentType())
                        ?: run {
                            i += 2
                            continue
                        }

                    soil.setWateredUntil(wetUntil)
                    soil.setDecayTime(tilledUntil)

                    chunkAt.setTicking(tx, soilY, tz, true)

                    val blockIndex = ChunkUtil.indexBlock(tx, soilY, tz)
                    chunkAt.blockChunk?.getSectionAtBlockY(soilY)?.apply {
                        scheduleTick(blockIndex, wetUntil)
                        scheduleTick(blockIndex, tilledUntil)
                    }

                    chunkAt.setTicking(tx, soilY + 1, tz, true)
                }

                i += 2
            }
        }
    }

    // ========== Types / helpers ==========

    private data class PosKey(val worldId: String, val x: Int, val y: Int, val z: Int)


    private enum class SprinklerTier(
        val id: String,
        val particleScale: Float,
        val offsets: IntArray
    ) {
        // Crude: cross r=1
        CRUDE("Crude_Sprinkler", 0.25f, crossOffsets(1)),
        COPPER("Copper_Sprinkler", 0.5f, squareOffsets(1)),
        IRON("Iron_Sprinkler", 1.0f, squareOffsets(2)),
        THORIUM("Thorium_Sprinkler", 1.0f, squareOffsets(3)),
        COBALT("Cobalt_Sprinkler", 2.0f, squareOffsets(4)),
        ADAMANTITE("Adamantite_Sprinkler", 2.0f, squareOffsets(5));

        companion object {
            fun fromBlockId(id: String): SprinklerTier? =
                entries.firstOrNull { it.id == id }
        }
    }

    companion object {
        private const val SECONDS_PER_DAY: Long = 86_400L

        private val tickCounter = ConcurrentHashMap<PosKey, Int>()
        private val playedSound = ConcurrentHashMap<SoundKey, Boolean>()

        private val sprinklerSoundIndex: Int by lazy {
            SoundEvent.getAssetMap().getIndex("Sprinkler")
        }

        private fun crossOffsets(r: Int): IntArray {
            // (dx, dz) pairs
            val arr = IntArray(r * 4 * 2)
            var i = 0
            for (d in 1..r) {
                arr[i++] = d; arr[i++] = 0
                arr[i++] = -d; arr[i++] = 0
                arr[i++] = 0; arr[i++] = d
                arr[i++] = 0; arr[i++] = -d
            }
            return arr
        }

        private fun squareOffsets(r: Int): IntArray {
            // (2r+1)^2 - 1 blocks, each has (dx,dz)
            val count = (2 * r + 1) * (2 * r + 1) - 1
            val arr = IntArray(count * 2)
            var i = 0
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (dx == 0 && dz == 0) continue
                    arr[i++] = dx
                    arr[i++] = dz
                }
            }
            return arr
        }

        // Particle emitters (relative positions + rotations)
        private val EMITTERS = arrayOf(
            Emitter(0.75, 0.30, 0.50, 0f, 0f, -1f),
            Emitter(0.50, 0.30, 0.75, 0f, 1f, 0f),
            Emitter(0.25, 0.30, 0.50, 0f, 0f, 1f),
            Emitter(0.50, 0.30, 0.25, 0f, -1f, 0f),
        )

        @JvmField
        val CODEC = BuilderCodec.builder(SprinklerTickProcedure::class.java, ::SprinklerTickProcedure).build()
    }
}
