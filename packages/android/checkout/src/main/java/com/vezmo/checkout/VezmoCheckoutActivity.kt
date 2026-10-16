package com.vezmo.checkout

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * The Vezmo payment sheet's own see-through activity, edge to edge over the app: a dimmed backdrop and the sheet,
 * drawn in Compose. The engine's card form and 3D Secure open on top of it. Closing delivers the [CheckoutResult].
 */
internal class VezmoCheckoutActivity : ComponentActivity() {
    /** The session survives the activity being recreated. */
    class Holder : ViewModel() {
        var session: CheckoutSession? = null

        override fun onCleared() {
            session?.dispose()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open++
        intent.setExtrasClassLoader(CheckoutRequest::class.java.classLoader)
        val request = IntentCompat.getParcelableExtra(intent, CheckoutContract.EXTRA_REQUEST, CheckoutRequest::class.java)
        if (request == null) {
            close(CheckoutResult.Failed(VezmoError(VezmoError.Code.Config, "Open the sheet with VezmoCheckout.present().")))
            return
        }
        val holder = ViewModelProvider(this)[Holder::class.java]
        // Prepared in this process; or, after the process was killed, loaded again from its token.
        val session = holder.session
            ?: PreparedSessions.take(request.clientToken)
            ?: CheckoutSession(VezmoApi(request.configuration.apiBase ?: DEFAULT_API_BASE), request.clientToken, request.configuration.returnUrl).also { s ->
                s.post { s.load() }
            }
        holder.session = session
        bars(dark = false)

        setContent {
            val snap by session.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (request.configuration.style) {
                VezmoStyle.Light -> false
                VezmoStyle.Dark -> true
                VezmoStyle.Automatic -> when (snap.config?.theme) {
                    "light" -> false
                    "dark" -> true
                    else -> systemDark
                }
            }
            LaunchedEffect(dark) { bars(dark) }
            val engine = rememberEngine(session)
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                CheckoutSheet(session, engine, request, dark, onClose = ::close)
            }
        }
    }

    /** Light icons over the dimmed app; the gesture bar matches the sheet. */
    private fun bars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    override fun onDestroy() {
        open--
        super.onDestroy()
    }

    companion object {
        /** Sheets alive in this process: where [VezmoReturnActivity] sends the buyer back. */
        var open = 0
    }

    private fun close(result: CheckoutResult) {
        setResult(RESULT_OK, Intent().putExtra(CheckoutContract.EXTRA_RESULT, result))
        finish()
    }
}
