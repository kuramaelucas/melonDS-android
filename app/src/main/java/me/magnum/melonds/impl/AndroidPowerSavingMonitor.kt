package me.magnum.melonds.impl

import android.app.GameManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.magnum.melonds.domain.services.PowerSavingMonitor
import me.magnum.melonds.domain.services.PowerSavingPolicy

class AndroidPowerSavingMonitor(
    private val context: Context,
    private val preferences: SharedPreferences,
) : PowerSavingMonitor {

    private companion object {
        const val AUTOMATIC_POWER_SAVING_PREFERENCE = "enable_automatic_power_saving"
    }

    private val _powerSavingReason = MutableStateFlow<PowerSavingMonitor.Reason?>(null)
    override val powerSavingReason: StateFlow<PowerSavingMonitor.Reason?> = _powerSavingReason.asStateFlow()

    @Volatile private var isThermalThrottlingRequired = false
    @Volatile private var isBatteryGameModeEnabled = false
    @Volatile private var isBatterySaverEnabled = false

    // SharedPreferences only keeps a weak reference to its listeners
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AUTOMATIC_POWER_SAVING_PREFERENCE) {
            updateReason()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startObservingThermalStatus()
        }
        startObservingBatterySaver()
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

    private fun startObservingBatterySaver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun refresh() {
        isBatteryGameModeEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<GameManager>()?.gameMode == GameManager.GAME_MODE_BATTERY
        } else {
            false
        }
        isBatterySaverEnabled = context.getSystemService<PowerManager>()?.isPowerSaveMode == true
        updateReason()
    }

    private fun updateReason() {
        _powerSavingReason.value = PowerSavingPolicy.resolveReason(
            isAutomaticPowerSavingEnabled = preferences.getBoolean(AUTOMATIC_POWER_SAVING_PREFERENCE, true),
            isThermalThrottlingRequired = isThermalThrottlingRequired,
            isBatteryGameModeEnabled = isBatteryGameModeEnabled,
            isBatterySaverEnabled = isBatterySaverEnabled,
        )
    }
}
