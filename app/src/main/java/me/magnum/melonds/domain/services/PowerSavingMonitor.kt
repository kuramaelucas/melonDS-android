package me.magnum.melonds.domain.services

import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors device conditions that require the emulator to reduce its power usage, such as the device overheating or the user enabling the battery game mode or the system's
 * battery saver. No reason is reported if the user has disabled automatic power saving.
 */
interface PowerSavingMonitor {

    enum class Reason {
        THERMAL,
        BATTERY_GAME_MODE,
        BATTERY_SAVER,
    }

    /**
     * The reason why power saving is currently required, or null if it's not required.
     */
    val powerSavingReason: StateFlow<Reason?>

    /**
     * Re-evaluates conditions that cannot be observed directly, such as the current game mode.
     */
    fun refresh()
}
