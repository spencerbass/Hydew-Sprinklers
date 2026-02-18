package com.spenc.hydew.systems.events

import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.protocol.Direction
import com.hypixel.hytale.protocol.Position
import com.hypixel.hytale.protocol.SoundCategory
import com.hypixel.hytale.protocol.ToClientPacket
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem
import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy
import com.hypixel.hytale.server.core.asset.type.blocktick.config.TickProcedure
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource
import com.hypixel.hytale.server.core.universe.world.SoundUtil
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

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
        val typeId = blockType.id

        val tier = SprinklerTier.fromBlockId(typeId) ?: return BlockTickStrategy.IGNORED

        // Time gate: only spray while audio is "on" (5:00–5:13)
        val wtr = world.entityStore.store.getResource(WorldTimeResource.getResourceType())
        val hour = wtr.currentHour
        if (hour < 5 || hour >= 6) return BlockTickStrategy.CONTINUE

        val minute = wtr.gameTime.atZone(ZoneOffset.UTC).minute
        if (minute > 13) return BlockTickStrategy.CONTINUE

        // Rate limit per sprinkler position (particles + watering attempts)
        val sprinklerPos = PosKey(world.name, x, y, z)
        val n = tickCounter.merge(sprinklerPos, 1) { a, b -> a + b } ?: 1
        if (n % 10 != 0) return BlockTickStrategy.CONTINUE // every 10 ticks

        // ---- Particles ----
        spawnSprayParticles(world, x, y, z, tier.particleScale)

        // ---- Sound: once per player per sprinkler per day ----
        playSprinklerSoundOncePerDay(world, sprinklerPos, wtr.gameTime)

        // ---- Water adjacent soil (soil is at y-1) with ramping probability ----
        val chance = wateringChance(minute, endMinute = 13)
        waterSoilAround(world, tier, x, y - 1, z, wtr.gameTime, chance)

        return BlockTickStrategy.CONTINUE
    }

    // ========== Particles ==========

    private fun spawnSprayParticles(world: World, x: Int, y: Int, z: Int, scale: Float) {
        val emitters = listOf(
            Pair(Position(x + 0.75, y + 0.30, z + 0.50), Direction(0f, 0f, -1f)),
            Pair(Position(x + 0.50, y + 0.30, z + 0.75), Direction(0f, 1f, 0f)),
            Pair(Position(x + 0.25, y + 0.30, z + 0.50), Direction(0f, 0f, 1f)),
            Pair(Position(x + 0.50, y + 0.30, z + 0.25), Direction(0f, -1f, 0f)),
        )

        for ((pos, dir) in emitters) {
            val packet = SpawnParticleSystem("Water_Splash", pos, dir, scale, null) as ToClientPacket
            for (player in world.playerRefs) {
                player.packetHandler.writeNoCache(packet)
            }
        }
    }

    // ========== Sound (dedup per player per day) ==========
    private data class SoundKey(val pos: PosKey, val dayIndex: Long)
    private val lastPlayedSound = ConcurrentHashMap<SoundKey, Boolean>()

    private fun playSprinklerSoundOncePerDay(world: World, pos: PosKey, gameTime: Instant) {
        val soundIndex = sprinklerSoundIndex
        if (soundIndex == Int.MIN_VALUE || soundIndex == 0) return

        val dayIndex = gameTime.epochSecond / SECONDS_PER_DAY
        val key = SoundKey(pos, dayIndex)

        // If already played for THIS sprinkler TODAY, don't play again
        if (lastPlayedSound.putIfAbsent(key, true) != null) return

        // Play 3D sound at sprinkler location, using engine's spatial targeting
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

    private fun wateringChance(minute: Int, endMinute: Int): Double {
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
        val targets = tier.targets(sx, soilY, sz)
        if (targets.isEmpty()) return

        val wetUntil = gameTime.plus(SECONDS_PER_DAY, ChronoUnit.SECONDS)
        val tilledUntil = gameTime.plus(SECONDS_PER_DAY * 2, ChronoUnit.SECONDS)

        world.execute {
            val chunkStore = world.chunkStore.store

            for ((tx, ty, tz) in targets) {
                if (getRandom().nextDouble() >= chance) continue

                val chunkAt = world.getChunk(ChunkUtil.indexChunkFromBlock(tx, tz)) ?: continue

                val blockRef = chunkAt.getBlockComponentEntity(tx, ty, tz)
                    ?: BlockModule.ensureBlockEntity(chunkAt, tx, ty, tz)
                    ?: continue

                val soil = chunkStore.getComponent(blockRef, TilledSoilBlock.getComponentType())
                    ?: continue

                soil.setWateredUntil(wetUntil)
                soil.setDecayTime(tilledUntil)

                chunkAt.setTicking(tx, ty, tz, true)

                val blockIndex = ChunkUtil.indexBlock(tx, ty, tz)
                chunkAt.blockChunk?.getSectionAtBlockY(ty)?.apply {
                    scheduleTick(blockIndex, wetUntil)
                    scheduleTick(blockIndex, tilledUntil)
                }

                chunkAt.setTicking(tx, ty + 1, tz, true)
            }
        }
    }

    // ========== Types / helpers ==========

    private data class PosKey(val worldId: String, val x: Int, val y: Int, val z: Int)

    private enum class SprinklerTier(
        val id: String,
        val particleScale: Float,
        val radius: Int,
        val isCross: Boolean
    ) {
        // Crude: cross r=1
        CRUDE("Crude_Sprinkler", 0.25f, radius = 1, isCross = true),

        // Squares r=1..5
        COPPER("Copper_Sprinkler", 0.5f, radius = 1, isCross = false),
        IRON("Iron_Sprinkler", 1.0f, radius = 2, isCross = false),
        THORIUM("Thorium_Sprinkler", 1.0f, radius = 3, isCross = false),
        COBALT("Cobalt_Sprinkler", 2.0f, radius = 4, isCross = false),
        ADAMANTITE("Adamantite_Sprinkler", 2.0f, radius = 5, isCross = false);

        fun targets(sx: Int, sy: Int, sz: Int): Array<Triple<Int, Int, Int>> =
            if (isCross) buildCross(sx, sy, sz, radius) else buildSquare(sx, sy, sz, radius)

        companion object {
            fun fromBlockId(id: String): SprinklerTier? =
                entries.firstOrNull { it.id == id }

            private fun buildCross(sx: Int, sy: Int, sz: Int, r: Int): Array<Triple<Int, Int, Int>> {
                val list = ArrayList<Triple<Int, Int, Int>>(r * 4)
                for (d in 1..r) {
                    list.add(Triple(sx + d, sy, sz))
                    list.add(Triple(sx - d, sy, sz))
                    list.add(Triple(sx, sy, sz + d))
                    list.add(Triple(sx, sy, sz - d))
                }
                return list.toTypedArray()
            }

            private fun buildSquare(sx: Int, sy: Int, sz: Int, r: Int): Array<Triple<Int, Int, Int>> {
                val list = ArrayList<Triple<Int, Int, Int>>((2 * r + 1) * (2 * r + 1) - 1)
                for (dx in -r..r) {
                    for (dz in -r..r) {
                        if (dx == 0 && dz == 0) continue
                        list.add(Triple(sx + dx, sy, sz + dz))
                    }
                }
                return list.toTypedArray()
            }
        }
    }

    companion object {
        private const val SECONDS_PER_DAY: Long = 86_400L

        private val tickCounter = ConcurrentHashMap<PosKey, Int>()
        private val lastPlayedDay = ConcurrentHashMap<SoundKey, Long>()

        private val sprinklerSoundIndex: Int by lazy {
            SoundEvent.getAssetMap().getIndex("Sprinkler")
        }

        @JvmField
        val CODEC = BuilderCodec.builder(SprinklerTickProcedure::class.java, ::SprinklerTickProcedure).build()
    }
}
