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

    init {
        instance = this
        LOGGER.atInfo().log("Starting $name version ${manifest.version}")
    }

    override fun setup() {
        super.setup()

        TickProcedure.CODEC.register(
            "spenc:sprinkler_tick",
            SprinklerTickProcedure::class.java,
            SprinklerTickProcedure.CODEC
        )
    }

}
