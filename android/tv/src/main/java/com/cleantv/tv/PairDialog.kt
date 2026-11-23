package com.cleantv.tv

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * "Allow <phone> to control this TV?" — shown on the TV when a phone asks to pair.
 * Allow gives that phone a key; Deny (or Back, or 2 minutes) refuses it.
 */
object PairDialog {
    private var shown: AlertDialog? = null
    private var lastRequest: String? = null

    /** Called regularly by the visible TV Cast screen; shows each new request once. */
    fun showIfPending(activity: Activity) {
        val req = Auth.pending() ?: return
        if (req == lastRequest) return
        lastRequest = req
        show(activity)
    }

    private fun show(activity: Activity) {
        if (activity.isFinishing) return
        shown?.dismiss()
        var answered = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Allow \"${Auth.pairingName}\"?")
            .setMessage("This phone wants to play videos and pictures on this TV and control TV Cast.\n\nChoose Allow only if it's your phone. You can remove phones later in Clear history → Forget paired phones.")
            .setPositiveButton("Allow") { _, _ -> answered = true; Auth.answer(activity.applicationContext, true) }
            .setNegativeButton("Deny") { _, _ -> answered = true; Auth.answer(activity.applicationContext, false) }
            .create()
        dialog.setOnDismissListener {
            if (!answered) Auth.answer(activity.applicationContext, false)
            if (shown === dialog) shown = null
        }
        shown = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        // Request expires after 2 minutes.
        dialog.window?.decorView?.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 2 * 60 * 1000L)
    }
}