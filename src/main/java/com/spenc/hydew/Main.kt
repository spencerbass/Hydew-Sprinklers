package com.spenc.hydew

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.blocktick.config.TickProcedure
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.spenc.hydew.systems.events.SprinklerTickProcedure

class Main(init: JavaPluginInit) : JavaPlugin(init) {

    companion object {
        private lateinit var instance: Main
        val LOGGER: HytaleLogger = HytaleLogger.forEnclosingClass()
        fun getInstance(): Main = instance
    }

//    UNUSED
//    private lateinit var farmSystem: DailyFarmSystem

    init {
        instance = this
        LOGGER.atInfo().log("Hello from $name version ${manifest.version}")
    }

    override fun setup() {
        super.setup()

        TickProcedure.CODEC.register(
            "spenc:sprinkler_tick",
            SprinklerTickProcedure::class.java,
            SprinklerTickProcedure.CODEC
        )

        // Daily logic
        // UNUSED
        // farmSystem = DailyFarmSystem()
    }

//    UNUSED
//    private var future: java.util.concurrent.ScheduledFuture<*>? = null

    override fun start() {
//        UNUSED
//        future = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
//            {
//                try {
//                    morningScheduler.pollOnce()
//                } catch (t: Throwable) {
//                    Main.LOGGER.atSevere().withCause(t).log("pollOnce crashed")
//                }
//            },
//            1, 1, TimeUnit.SECONDS
//        )
    }

//    UNUSED
//    override fun shutdown() {
//        future?.cancel(false)
//    }
}
