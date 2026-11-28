package com.cleantv.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After the app is updated Android kills the sharing service and never restarts it, so the TV
 * suddenly shows "isn't sharing". Restart sharing (and re-announce to the TV) right away; the
 * usual idle rule still stops it 2 minutes later if the TV isn't using it.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.lastTv(ctx) == null) return
        try {
            ServerService.start(ctx)
        } catch (e: Exception) {
            Log.w("TvCast", "restart after update failed", e)
        }
    }
}
