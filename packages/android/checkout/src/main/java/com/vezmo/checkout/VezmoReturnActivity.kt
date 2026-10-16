package com.vezmo.checkout

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where your return link lands after a bank app, a pay-later provider or a browser step. It brings the open Vezmo
 * sheet back to the front (closing the browser step above it, which then reports to the sheet), or your app when no
 * sheet is open. Add your return link to it in your app's manifest; see the README.
 */
class VezmoReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (VezmoCheckoutActivity.open > 0) {
            Intent(this, VezmoCheckoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        next?.let { runCatching { startActivity(it) } }
        finish()
    }
}
