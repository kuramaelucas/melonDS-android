package me.magnum.melonds.domain.services

/**
 * Pure decision rules for power saving, kept free of Android dependencies so that they can be unit tested.
 */
object PowerSavingPolicy {

    const val MAX_FAST_FORWARD_MULTIPLIER = 2f

    /**
     * Picks the reason that is reported to the user. Thermal throttling takes precedence over the battery game mode, which takes precedence over the system's battery saver. No
     * reason is reported if automatic power saving is disabled.
     */
    fun resolveReason(
        isAutomaticPowerSavingEnabled: Boolean,
        isThermalThrottlingRequired: Boolean,
        isBatteryGameModeEnabled: Boolean,
        isBatterySaverEnabled: Boolean,
    ): PowerSavingMonitor.Reason? {
        return when {
            !isAutomaticPowerSavingEnabled -> null
            isThermalThrottlingRequired -> PowerSavingMonitor.Reason.THERMAL
            isBatteryGameModeEnabled -> PowerSavingMonitor.Reason.BATTERY_GAME_MODE
            isBatterySaverEnabled -> PowerSavingMonitor.Reason.BATTERY_SAVER
            else -> null
        }
    }

    /**
     * Limits the fast-forward multiplier while power saving is active. Values <= 0 mean unlimited speed.
     */
    fun limitFastForwardMultiplier(multiplier: Float, isPowerSavingActive: Boolean, isLimitEnabled: Boolean): Float {
        if (!isPowerSavingActive || !isLimitEnabled) {
            return multiplier
        }

        return if (multiplier <= 0 || multiplier > MAX_FAST_FORWARD_MULTIPLIER) {
            MAX_FAST_FORWARD_MULTIPLIER
        } else {
            multiplier
        }
    }
}
