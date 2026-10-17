package com.vezmo.checkout

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentelement.CreateIntentWithConfirmationTokenCallback
import com.stripe.android.paymentelement.EmbeddedPaymentElement
import com.stripe.android.paymentelement.WalletButtonsPreview
import com.stripe.android.paymentelement.WalletButtonsViewClickHandler
import com.stripe.android.paymentelement.rememberEmbeddedPaymentElement
import com.stripe.android.paymentsheet.CreateIntentResult
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.model.PaymentOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DEFAULT_ERROR = "Your payment couldn’t be completed. Please try again."

private const val TAG = "VezmoCheckout"

/** Who and how: a change rebuilds the engine (new keys, wallets or look), never a new amount. */
internal data class EngineSettings(
    val publishableKey: String,
    val connectedAccountId: String?,
    val merchantDisplayName: String,
    /** Google Pay for the express button, when the business offers it. */
    val googlePay: GooglePay?,
    val billingName: String?,
    val billingEmail: String?,
    val appearance: PaymentSheet.Appearance,
    /** Bumped by Try again after a failed load. */
    val attempt: Int,
) {
    data class GooglePay(val countryCode: String, val currencyCode: String, val testEnv: Boolean)
}

/**
 * What is charged: a change updates the engine in place (the buyer switched currency). Cards go to the card form, the
 * sheet's own Card row; every other method to the element's rows. A subscription saves the card for its renewals: a
 * free trial only sets it up ([setup]); a first payment keeps it ([saveForLater]).
 */
internal data class EngineIntent(
    val amount: Long,
    val currency: String,
    val elementTypes: List<String>,
    val card: Boolean,
    val setup: Boolean = false,
    val saveForLater: Boolean = false,
)

internal fun engineIntent(charge: Charge, methods: SheetMethods, config: CheckoutConfig? = null) =
    EngineIntent(
        amount = charge.amountMinor,
        currency = charge.currency.lowercase(),
        elementTypes = methods.elementTypes.filter { it != "card" },
        card = "card" in methods.elementTypes,
        setup = config?.subscription?.trial == true,
        saveForLater = config?.subscription != null,
    )

/**
 * The native payment engine, confirmed through Vezmo's server:
 *
 * - two flow controllers: one for cards only (its sheet is just the card form, which the sheet's Card row opens) and
 *   one with Google Pay that only draws the express button. Each has its own store, so both live in one activity.
 * - an embedded element for the methods with rows of their own (pay-later, Cash App), left unconfigured when there
 *   are none (an empty method list would show every method on the account).
 *
 * Payment flow: the buyer taps Pay → the engine makes a confirmation token and calls back → the session sends its id
 * to Vezmo, which prices and confirms the payment → the session answers with the intent's client secret → the engine
 * finishes any step left (3D Secure, Google Pay, a provider) and reports the result.
 */
@OptIn(WalletButtonsPreview::class)
internal class Engine(private val session: CheckoutSession) {
    lateinit var flow: PaymentSheet.FlowController
    lateinit var walletFlow: PaymentSheet.FlowController
    lateinit var embedded: EmbeddedPaymentElement

    private var cardSecret: CompletableDeferred<CreateIntentResult>? = null
    private var elementSecret: CompletableDeferred<CreateIntentResult>? = null
    private var configuredWith: EngineSettings? = null
    private var cardConfiguration: PaymentSheet.Configuration? = null
    private var walletConfiguration: PaymentSheet.Configuration? = null
    private var elementConfiguration: EmbeddedPaymentElement.Configuration? = null

    /** The settings the card flow and the element last finished loading with (the sheet shows placeholders until). */
    var cardLoaded by mutableStateOf<EngineSettings?>(null)
    var elementLoaded by mutableStateOf<EngineSettings?>(null)

    /**
     * The card flow and the element are set up for what the sheet shows now. False while they are set up again (the
     * buyer switched currency: the rows stay on screen) or after it failed: the engine refuses the card form and
     * payments meanwhile, so the sheet holds the Card row and Pay until then.
     */
    var cardReady by mutableStateOf(false)
    var elementReady by mutableStateOf(false)

    /** Bumped to set the engine up again for the same settings (the card form found it not set up after all). */
    var reconfigure by mutableIntStateOf(0)

    /** The engine can take this way to pay now. */
    fun readyFor(method: Method) =
        when (method) {
            Method.Card -> cardReady
            Method.Element -> elementReady
            else -> true
        }

    /** The card form is being opened: a failure reported meanwhile is the form's, not a payment's. */
    private var openingCard = false
    private var openFailure: Throwable? = null

    /** After the engine is set up again, open the card form the buyer asked for. */
    private var reopenCard = false

    /** The element has rows of its own to draw. */
    var elementRows by mutableStateOf(false)

    /** Google Pay is set up; [walletsHeight] says whether the phone showed a button. */
    var walletsOn by mutableStateOf(false)

    /** The wallet flow is set up for what the sheet shows: a Google Pay tap before then would fail before Vezmo is asked. */
    var walletReady by mutableStateOf(false)
    var walletsHeight by mutableIntStateOf(0)

    /** Each card the card form reports, numbered so the same card reported again still counts. */
    var cardReport by mutableStateOf<Pair<PaymentOption?, Int>>(null to 0)

    fun reportCard(option: PaymentOption?) {
        cardReport = option to cardReport.second + 1
    }

    val cardPort =
        object : CardFlowPort {
            // The Card row is off until the card flow is set up; a tap that gets here anyway is dropped.
            override fun presentCard() {
                if (cardReady) openCardForm(retry = true)
            }

            override fun confirmCard() {
                if (cardReady) flow.confirm() else session.post { session.engineNotReady() }
            }

            override fun resolveConfirmation(clientSecret: String?, errorMessage: String?) {
                cardSecret?.complete(answer(clientSecret, errorMessage))
                cardSecret = null
            }
        }

    val elementPort =
        object : ElementPort {
            override fun confirm() {
                if (elementReady) embedded.confirm() else session.post { session.engineNotReady() }
            }

            override fun clearSelection() = embedded.clearPaymentOption()

            override fun resolveConfirmation(clientSecret: String?, errorMessage: String?) {
                elementSecret?.complete(answer(clientSecret, errorMessage))
                elementSecret = null
            }
        }

    private fun answer(clientSecret: String?, errorMessage: String?): CreateIntentResult =
        if (!clientSecret.isNullOrEmpty()) {
            CreateIntentResult.Success(clientSecret = clientSecret)
        } else {
            val message = errorMessage?.ifEmpty { null } ?: DEFAULT_ERROR
            CreateIntentResult.Failure(cause = Exception(message), displayMessage = message)
        }

    /** The engine made a confirmation token: hand it to the session and wait for Vezmo's answer. */
    suspend fun awaitClientSecret(card: Boolean, tokenId: String): CreateIntentResult =
        withContext(Dispatchers.Main.immediate) {
            val deferred = CompletableDeferred<CreateIntentResult>()
            // A confirm still waiting is superseded by this one.
            val superseded = CreateIntentResult.Failure(cause = Exception("superseded"), displayMessage = DEFAULT_ERROR)
            if (card) {
                cardSecret?.complete(superseded)
                cardSecret = deferred
            } else {
                elementSecret?.complete(superseded)
                elementSecret = deferred
            }
            session.post { session.onConfirmationToken(tokenId) }
            deferred.await()
        }

    /**
     * Opens the card form. The engine refuses when it isn't set up after all ("not configured", in its own words, never
     * shown): it is set up again and the form opened once more; failing that, the buyer reads ours.
     */
    private fun openCardForm(retry: Boolean) {
        openFailure = null
        openingCard = true
        flow.presentPaymentOptions() // a refusal is reported at once, to onFlowResult
        openingCard = false
        val failure = openFailure ?: return
        Log.w(TAG, "The card form couldn't open", failure)
        if (retry) {
            reopenCard = true
            cardReady = false
            reconfigure++
        } else {
            session.engineNotReady()
        }
    }

    fun onFlowResult(result: PaymentSheetResult) {
        if (openingCard) {
            if (result is PaymentSheetResult.Failed) openFailure = result.error
            return
        }
        when (result) {
            is PaymentSheetResult.Completed -> report(ConfirmStatus.Completed, "")
            is PaymentSheetResult.Canceled -> report(ConfirmStatus.Canceled, "")
            is PaymentSheetResult.Failed -> {
                Log.w(TAG, "The card flow failed", result.error)
                report(ConfirmStatus.Failed, result.error.localizedMessage ?: DEFAULT_ERROR)
            }
        }
    }

    fun onElementResult(result: EmbeddedPaymentElement.Result) =
        when (result) {
            is EmbeddedPaymentElement.Result.Completed -> report(ConfirmStatus.Completed, "")
            is EmbeddedPaymentElement.Result.Canceled -> report(ConfirmStatus.Canceled, "")
            is EmbeddedPaymentElement.Result.Failed -> report(ConfirmStatus.Failed, result.error.localizedMessage ?: DEFAULT_ERROR)
        }

    private fun report(status: ConfirmStatus, message: String) = session.post { session.onConfirmResult(status, message) }

    /** Sets the engine up for these settings (rebuilt when they change) and this amount. */
    suspend fun configure(context: Context, settings: EngineSettings, intent: EngineIntent) {
        val rebuild = settings != configuredWith
        if (rebuild) {
            // Placeholders until it is built again, even for settings it last finished with (the buyer switched currency
            // and back before the first switch was set up: those rows would show over a flow still being set up).
            cardLoaded = null
            elementLoaded = null
            PaymentConfiguration.init(context.applicationContext, settings.publishableKey, settings.connectedAccountId)
            cardConfiguration = sheetConfiguration(settings, wallets = false)
            walletConfiguration = settings.googlePay?.let { sheetConfiguration(settings, wallets = true) }
            elementConfiguration = elementConfiguration(settings)
            configuredWith = settings
        }
        // None is ready for this amount until set up for it (the element waits for the card flow).
        cardReady = false
        elementReady = false
        walletReady = false

        if (!intent.card) {
            walletsOn = false
        } else {
            val cards = intentConfiguration(intent, listOf("card"))
            val error = configureOne(flow, cards, cardConfiguration!!)
            if (error != null) {
                reopenCard = false
                Log.w(TAG, "The card form couldn't load", error)
                session.setElementError(error.localizedMessage ?: DEFAULT_ERROR)
                return
            }
            cardReady = true
            // A wallet that can't load is left out; the card still works.
            walletsOn = walletConfiguration?.let { configureOne(walletFlow, cards, it) == null } ?: false
            walletReady = walletsOn
            reportCard(flow.getPaymentOption())
        }
        cardLoaded = settings
        if (reopenCard) {
            reopenCard = false
            openCardForm(retry = false)
        }

        elementRows = intent.elementTypes.isNotEmpty()
        if (elementRows) {
            when (val result = embedded.configure(intentConfiguration(intent, intent.elementTypes), elementConfiguration!!)) {
                is EmbeddedPaymentElement.ConfigureResult.Succeeded -> if (rebuild) session.setElementError(null)
                is EmbeddedPaymentElement.ConfigureResult.Failed -> {
                    Log.w(TAG, "The payment options couldn't load", result.error)
                    session.setElementError(result.error.localizedMessage ?: DEFAULT_ERROR)
                    return
                }
            }
        }
        elementReady = true
        elementLoaded = settings
    }

    private suspend fun configureOne(
        flow: PaymentSheet.FlowController,
        intent: PaymentSheet.IntentConfiguration,
        configuration: PaymentSheet.Configuration,
    ): Throwable? {
        val done = CompletableDeferred<Throwable?>()
        flow.configureWithIntentConfiguration(intent, configuration) { success, error ->
            done.complete(if (success) null else error ?: Exception(DEFAULT_ERROR))
        }
        return done.await()
    }

    /** What is charged, with the method types each part of the engine shows. */
    private fun intentConfiguration(intent: EngineIntent, types: List<String>) =
        PaymentSheet.IntentConfiguration(
            mode = if (intent.setup) {
                PaymentSheet.IntentConfiguration.Mode.Setup(
                    currency = intent.currency,
                    setupFutureUse = PaymentSheet.IntentConfiguration.SetupFutureUse.OffSession,
                )
            } else {
                PaymentSheet.IntentConfiguration.Mode.Payment(
                    amount = intent.amount,
                    currency = intent.currency,
                    setupFutureUse = if (intent.saveForLater) PaymentSheet.IntentConfiguration.SetupFutureUse.OffSession else null,
                    // Vezmo's server makes every intent with automatic_async capture; the engine must say the same.
                    captureMethod = PaymentSheet.IntentConfiguration.CaptureMethod.AutomaticAsync,
                )
            },
            paymentMethodTypes = types,
        )

    /** The same business, look and billing details for both flows; Google Pay only for the wallet flow. */
    @SuppressLint("RestrictedApi")
    private fun sheetConfiguration(settings: EngineSettings, wallets: Boolean): PaymentSheet.Configuration {
        val builder = PaymentSheet.Configuration
            .Builder(settings.merchantDisplayName)
            // Vezmo is white label: no processor wallet of its own.
            .link(PaymentSheet.LinkConfiguration(display = PaymentSheet.LinkConfiguration.Display.Never))
            .paymentMethodLayout(PaymentSheet.PaymentMethodLayout.Vertical)
            .appearance(settings.appearance)
        settings.googlePay?.takeIf { wallets }?.let { gp ->
            builder.googlePay(
                PaymentSheet.GooglePayConfiguration(
                    environment = if (gp.testEnv) {
                        PaymentSheet.GooglePayConfiguration.Environment.Test
                    } else {
                        PaymentSheet.GooglePayConfiguration.Environment.Production
                    },
                    countryCode = gp.countryCode,
                    currencyCode = gp.currencyCode,
                ),
            )
        }
        billing(settings)?.let { builder.defaultBillingDetails(it) }
        return builder.build()
    }

    @SuppressLint("RestrictedApi")
    private fun elementConfiguration(settings: EngineSettings): EmbeddedPaymentElement.Configuration {
        val builder = EmbeddedPaymentElement.Configuration
            .Builder(settings.merchantDisplayName)
            // Our own Pay button confirms; a form only collects details.
            .formSheetAction(EmbeddedPaymentElement.FormSheetAction.Continue)
            // Vezmo is white label: no processor wallet of its own in the sheet.
            .link(PaymentSheet.LinkConfiguration(display = PaymentSheet.LinkConfiguration.Display.Never))
            .embeddedViewDisplaysMandateText(true)
            .appearance(settings.appearance)
        billing(settings)?.let { builder.defaultBillingDetails(it) }
        return builder.build()
    }

    private fun billing(settings: EngineSettings) =
        if (settings.billingName != null || settings.billingEmail != null) {
            PaymentSheet.BillingDetails(name = settings.billingName, email = settings.billingEmail)
        } else {
            null
        }

    /** The express Google Pay button, when the phone has it. */
    @Composable
    fun WalletButtons(modifier: Modifier = Modifier) {
        val handler = remember {
            WalletButtonsViewClickHandler { wallet ->
                if (!walletReady) {
                    // Still being set up for this amount or currency (the buyer just switched, or it's still loading on
                    // a slow network): the wallet would fail before Vezmo is asked. Our words; the next tap pays.
                    session.post { session.engineNotReady(WALLET_LOADING) }
                    true // handled here: the wallet doesn't start
                } else {
                    session.walletStarted(wallet)
                    false // not handled here: the wallet goes on to pay
                }
            }
        }
        Box(modifier.fillMaxWidth().onSizeChanged { walletsHeight = it.height }) {
            walletFlow.WalletButtons(handler)
        }
    }

    /** The element's own rows (pay-later, Cash App). */
    @Composable
    fun ElementRows() {
        embedded.Content()
    }
}

/**
 * Creates the engine with the sheet. The flow controllers and the element must be created unconditionally, as part of
 * the sheet's first composition, in the sheet's activity.
 */
@Composable
internal fun rememberEngine(session: CheckoutSession): Engine {
    val engine = remember(session) { Engine(session) }
    val cardBuilder = remember(engine) {
        PaymentSheet.FlowController
            .Builder(
                resultCallback = { engine.onFlowResult(it) },
                paymentOptionResultCallback = { engine.reportCard(it.paymentOption) },
            ).createIntentCallback(CreateIntentWithConfirmationTokenCallback { engine.awaitClientSecret(card = true, it.id) })
    }
    val walletBuilder = remember(engine) {
        PaymentSheet.FlowController
            .Builder(
                resultCallback = { engine.onFlowResult(it) },
                paymentOptionResultCallback = { },
            ).createIntentCallback(CreateIntentWithConfirmationTokenCallback { engine.awaitClientSecret(card = true, it.id) })
    }
    val elementBuilder = remember(engine) {
        EmbeddedPaymentElement.Builder(
            createIntentCallback = CreateIntentWithConfirmationTokenCallback { engine.awaitClientSecret(card = false, it.id) },
            resultCallback = { engine.onElementResult(it) },
        )
    }
    engine.flow = cardBuilder.build()
    engine.walletFlow = walletBuilder.build()
    engine.embedded = rememberEmbeddedPaymentElement(elementBuilder)

    // The card the card form reports, with the network's logo, for the sheet's Card row.
    val (option, _) = engine.cardReport
    val card = option?.takeIf { it.paymentMethodType == "card" }
    val painter = card?.iconPainter
    LaunchedEffect(engine.cardReport) {
        session.setCard(card?.let { CardChoice(it.label, painter) })
    }
    LaunchedEffect(engine.embedded) {
        engine.embedded.paymentOption.collect { session.setOption(it?.let { o -> o.label }) }
    }
    DisposableEffect(engine) {
        session.attachCardFlow(engine.cardPort)
        session.attachElement(engine.elementPort)
        onDispose {
            session.attachCardFlow(null)
            session.attachElement(null)
        }
    }
    return engine
}
