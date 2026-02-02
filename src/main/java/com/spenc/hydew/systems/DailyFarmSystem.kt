package com.spenc.hydew.systems

import com.spenc.hydew.Main.Companion.LOGGER

class DailyFarmSystem {
    fun onMorning(worldId: String) {
        LOGGER.atInfo().log("Morning tick fired for world=$worldId")
    }
}

