package com.orangepet.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryStatus(val percent: Int, val isCharging: Boolean)

/**
 * Registers **one** [BroadcastReceiver] for three actions
 * (`ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED`,
 * `ACTION_POWER_DISCONNECTED`) so charging state is always known without a
 * second receiver. Never registers twice, never unregisters a receiver
 * that wasn't registered — tracked with [isRegistered].
 */
class BatteryMonitor(private val context: Context) {

    private val _status = MutableStateFlow(BatteryStatus(percent = 100, isCharging = false))
    val status: StateFlow<BatteryStatus> = _status.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false

    fun start() {
        if (isRegistered) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
                    Intent.ACTION_POWER_CONNECTED -> updateCharging(true)
                    Intent.ACTION_POWER_DISCONNECTED -> updateCharging(false)
                }
            }
        }
        receiver = newReceiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Sticky broadcast: registering immediately delivers current state.
        val sticky = context.registerReceiver(newReceiver, filter)
        isRegistered = true
        sticky?.let { handleBatteryChanged(it) }
    }

    fun stop() {
        if (isRegistered) {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
            isRegistered = false
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val statusExtra = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = statusExtra == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusExtra == BatteryManager.BATTERY_STATUS_FULL

        if (level < 0 || scale <= 0) {
            // Invalid reading: still take the charging flag, never divide by zero.
            updateCharging(charging)
            return
        }
        val percent = level * 100 / scale
        _status.value = BatteryStatus(percent = percent, isCharging = charging)
    }

    private fun updateCharging(charging: Boolean) {
        _status.value = _status.value.copy(isCharging = charging)
    }
}
