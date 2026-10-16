package com.vezmo.checkout

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Takes a payment with the Vezmo payment sheet.
 *
 * ```
 * // In your activity's onCreate (it registers for the sheet's result, so it must be created early):
 * val vezmo = VezmoCheckout(this, VezmoConfiguration(returnUrl = "acme://vezmo")) { result -> … }
 *
 * // When the buyer taps Pay, with the client token your server got from Vezmo:
 * lifecycleScope.launch {
 *     vezmo.prepare(clientToken)?.let { return@launch showError(it.message) }
 *     vezmo.present()
 * }
 * ```
 *
 * In Compose, use [rememberVezmoCheckout].
 */
class VezmoCheckout internal constructor(
    private val configuration: VezmoConfiguration,
    private val launcher: ActivityResultLauncher<CheckoutRequest>,
    private val onResult: (CheckoutResult) -> Unit,
) {
    /**
     * @param activity your activity, in its `onCreate` (before it is started).
     * @param onResult called with the result each time the sheet closes. Never with an exception.
     */
    constructor(
        activity: ComponentActivity,
        configuration: VezmoConfiguration,
        onResult: (CheckoutResult) -> Unit,
    ) : this(configuration, activity.registerForActivityResult(CheckoutContract()) { onResult(it) }, onResult)

    private val api by lazy { VezmoApi(configuration.apiBase ?: DEFAULT_API_BASE) }
    private var prepared: CheckoutRequest? = null

    /**
     * Loads the session. Call it as soon as you have the client token, then [present].
     *
     * @param clientToken from your server: the API checkout session's `clientToken`.
     * @param merchantDisplayName shown at the top of the sheet. Defaults to the business name on the session.
     * @return null when the session is ready to pay, or why it can't be paid (expired, already paid, no connection…).
     */
    suspend fun prepare(
        clientToken: String,
        merchantDisplayName: String? = null,
    ): VezmoError? = withContext(Dispatchers.Main.immediate) {
        prepareOnMain(clientToken, merchantDisplayName)
    }

    private suspend fun prepareOnMain(
        clientToken: String,
        merchantDisplayName: String?,
    ): VezmoError? {
        prepared = null
        if (clientToken.isBlank()) return VezmoError(VezmoError.Code.Config, "clientToken is required.")
        val session = CheckoutSession(api, clientToken.trim(), configuration.returnUrl)
        session.load()
        val phase = session.state.value.phase
        if (phase is SessionPhase.Closed && phase.result is CheckoutResult.Failed) {
            session.dispose()
            return phase.result.error
        }
        PreparedSessions.put(session)
        prepared = CheckoutRequest(clientToken.trim(), merchantDisplayName, configuration)
        return null
    }

    /** Opens the sheet. The result arrives in the callback you gave this [VezmoCheckout]. */
    fun present() {
        val request = prepared
        if (request == null) {
            onResult(CheckoutResult.Failed(VezmoError(VezmoError.Code.Config, "Call prepare() before present().")))
            return
        }
        prepared = null
        launcher.launch(request)
    }

    companion object {
        /** This SDK's version. */
        const val VERSION: String = BuildConfig.SDK_VERSION
    }
}

/**
 * A [VezmoCheckout] for a Compose screen: [VezmoCheckout.prepare] in a coroutine, then [VezmoCheckout.present].
 * [onResult] is called with the result each time the sheet closes.
 */
@Composable
fun rememberVezmoCheckout(
    configuration: VezmoConfiguration,
    onResult: (CheckoutResult) -> Unit,
): VezmoCheckout {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(CheckoutContract()) { currentOnResult(it) }
    return remember(configuration, launcher) { VezmoCheckout(configuration, launcher) { currentOnResult(it) } }
}

/** What the sheet's activity needs: the session's token and how to show it. */
@kotlinx.parcelize.Parcelize
internal data class CheckoutRequest(
    val clientToken: String,
    val merchantDisplayName: String?,
    val configuration: VezmoConfiguration,
) : android.os.Parcelable

internal class CheckoutContract : ActivityResultContract<CheckoutRequest, CheckoutResult>() {
    override fun createIntent(
        context: Context,
        input: CheckoutRequest,
    ): Intent = Intent(context, VezmoCheckoutActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): CheckoutResult =
        intent
            ?.takeIf { resultCode == Activity.RESULT_OK }
            ?.apply { setExtrasClassLoader(CheckoutResult::class.java.classLoader) }
            ?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT, CheckoutResult::class.java) }
            ?: CheckoutResult.Canceled

    companion object {
        const val EXTRA_REQUEST = "com.vezmo.checkout.REQUEST"
        const val EXTRA_RESULT = "com.vezmo.checkout.RESULT"
    }
}

/**
 * Sessions loaded by [VezmoCheckout.prepare], handed to the sheet's activity in this process. After the process was
 * killed the activity loads the session again from its token.
 */
internal object PreparedSessions {
    private val sessions = mutableMapOf<String, CheckoutSession>()

    fun put(session: CheckoutSession) {
        sessions.put(session.token, session)?.takeIf { it !== session }?.dispose()
    }

    fun take(token: String): CheckoutSession? = sessions.remove(token)
}
