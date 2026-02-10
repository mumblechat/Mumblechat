package com.ramapay.app.chat.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import timber.log.Timber

/**
 * BroadcastReceiver that restarts the RelayService after device reboot.
 * 
 * Only restarts if the relay was previously running before the reboot.
 * Checks SharedPreferences for the relay-active flag set by RelayService.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "relay_prefs"
        private const val KEY_RELAY_WAS_ACTIVE = "relay_was_active"
        
        /**
         * Call this when relay starts to mark it as active.
         */
        fun setRelayActive(context: Context, active: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RELAY_WAS_ACTIVE, active)
                .apply()
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        
        Timber.d("$TAG: Boot completed received")
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean(KEY_RELAY_WAS_ACTIVE, false)
        
        if (wasActive) {
            Timber.i("$TAG: Relay was active before reboot - restarting service")
            RelayService.start(context)
        } else {
            Timber.d("$TAG: Relay was not active before reboot - skipping restart")
        }
    }
}
