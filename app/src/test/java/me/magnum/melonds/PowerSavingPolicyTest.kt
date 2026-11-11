package me.magnum.melonds

import me.magnum.melonds.domain.services.PowerSavingMonitor.Reason
import me.magnum.melonds.domain.services.PowerSavingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerSavingPolicyTest {

    private fun reason(automatic: Boolean = true, thermal: Boolean = false, gameMode: Boolean = false, saver: Boolean = false) =
        PowerSavingPolicy.resolveReason(automatic, thermal, gameMode, saver)

    @Test
    fun noReasonWhenNothingIsActive() {
        assertNull(reason())
    }

    @Test
    fun thermalTakesPrecedenceOverEverything() {
        assertEquals(Reason.THERMAL, reason(thermal = true, gameMode = true, saver = true))
    }

    @Test
    fun batteryGameModeTakesPrecedenceOverBatterySaver() {
        assertEquals(Reason.BATTERY_GAME_MODE, reason(gameMode = true, saver = true))
    }

    @Test
    fun batterySaverIsReportedWhenAlone() {
        assertEquals(Reason.BATTERY_SAVER, reason(saver = true))
    }

    @Test
    fun noReasonWhenAutomaticPowerSavingIsDisabled() {
        assertNull(reason(automatic = false, thermal = true, gameMode = true, saver = true))
    }

    @Test
    fun fastForwardIsUntouchedWhenPowerSavingIsInactive() {
        assertEquals(-1f, PowerSavingPolicy.limitFastForwardMultiplier(-1f, isPowerSavingActive = false, isLimitEnabled = true), 0f)
        assertEquals(5f, PowerSavingPolicy.limitFastForwardMultiplier(5f, isPowerSavingActive = false, isLimitEnabled = true), 0f)
    }

    @Test
    fun fastForwardIsUntouchedWhenLimitIsDisabled() {
        assertEquals(-1f, PowerSavingPolicy.limitFastForwardMultiplier(-1f, isPowerSavingActive = true, isLimitEnabled = false), 0f)
    }

    @Test
    fun unlimitedFastForwardIsCappedWhenPowerSaving() {
        assertEquals(PowerSavingPolicy.MAX_FAST_FORWARD_MULTIPLIER, PowerSavingPolicy.limitFastForwardMultiplier(-1f, true, true), 0f)
        assertEquals(PowerSavingPolicy.MAX_FAST_FORWARD_MULTIPLIER, PowerSavingPolicy.limitFastForwardMultiplier(0f, true, true), 0f)
    }

    @Test
    fun highFastForwardIsCappedWhenPowerSaving() {
        assertEquals(PowerSavingPolicy.MAX_FAST_FORWARD_MULTIPLIER, PowerSavingPolicy.limitFastForwardMultiplier(8f, true, true), 0f)
    }

    @Test
    fun lowFastForwardIsKeptWhenPowerSaving() {
        assertEquals(1.5f, PowerSavingPolicy.limitFastForwardMultiplier(1.5f, true, true), 0f)
    }
}
