package com.vezmo.checkout

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Where your return link lands after a bank app, a pay-later provider or a browser step. It hands the buyer back to
 * the open Vezmo sheet (the browser step closes and the sheet shows the result), or to your app when no sheet is
 * open. Add your return link to it in your app's manifest; see the README.
 */
class VezmoReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (VezmoCheckoutActivity.open > 0) {
            // The engine's own return link: its browser step closes itself (only itself) and reports to the sheet.
            Intent(Intent.ACTION_VIEW, Uri.parse(ENGINE_RETURN_URL + packageName)).setPackage(packageName)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        next?.let { runCatching { startActivity(it) } }
        finish()
    }

    private companion object {
        /** The engine's default return link, handled by its own manifest entry in this app. */
        const val ENGINE_RETURN_URL = "stripesdk://payment_return_url/"
    }
}
