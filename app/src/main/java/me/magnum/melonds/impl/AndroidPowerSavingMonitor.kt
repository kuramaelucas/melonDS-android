package me.magnum.melonds.impl

import android.app.GameManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.magnum.melonds.domain.services.PowerSavingMonitor

class AndroidPowerSavingMonitor(private val context: Context) : PowerSavingMonitor {

    private val _powerSavingReason = MutableStateFlow<PowerSavingMonitor.Reason?>(null)
    override val powerSavingReason: StateFlow<PowerSavingMonitor.Reason?> = _powerSavingReason.asStateFlow()

    @Volatile private var isThermalThrottlingRequired = false
    @Volatile private var isBatteryGameModeEnabled = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startObservingThermalStatus()
        }
        refresh()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startObservingThermalStatus() {
        val powerManager = context.getSystemService<PowerManager>() ?: return
        onThermalStatusChanged(powerManager.currentThermalStatus)
        powerManager.addThermalStatusListener(context.mainExecutor) {
            onThermalStatusChanged(it)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun onThermalStatusChanged(status: Int) {
        // Use hysteresis to avoid constantly switching between modes when the status oscillates around the threshold
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            isThermalThrottlingRequired = true
        } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
            isThermalThrottlingRequired = false
        }
        updateReason()
    }

    override fun refresh() {
        isBatteryGameModeEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<GameManager>()?.gameMode == GameManager.GAME_MODE_BATTERY
        } else {
            false
        }
        updateReason()
    }

    private fun updateReason() {
        _powerSavingReason.value = when {
            isThermalThrottlingRequired -> PowerSavingMonitor.Reason.THERMAL
            isBatteryGameModeEnabled -> PowerSavingMonitor.Reason.BATTERY_GAME_MODE
            else -> null
        }
    }
}
