package com.vezmo.checkout

import androidx.compose.ui.graphics.painter.Painter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the engine's element (pay-later, Cash App rows) can be asked to do. */
internal interface ElementPort {
    fun confirm()

    fun resolveConfirmation(clientSecret: String?, errorMessage: String?)

    fun clearSelection()
}

/** What the card flow can be asked to do: the card form, paying with its card, answering its confirmation. */
internal interface CardFlowPort {
    fun presentCard()

    fun confirmCard()

    fun resolveConfirmation(clientSecret: String?, errorMessage: String?)
}

/** The card from the card form, for the sheet's own Card row. */
internal data class CardChoice(
    /** "•••• 4242". */
    val label: String,
    /** The network's logo, when the engine gives one. */
    val image: Painter?,
)

internal sealed interface SessionPhase {
    data object Loading : SessionPhase

    data class Ready(val error: VezmoError?) : SessionPhase

    data object Paying : SessionPhase

    /** UPI or Pix started: the buyer finishes in their bank app; we watch the session. */
    data class Waiting(val method: QrMethod, val qr: QrPayment) : SessionPhase

    data class Done(val result: CheckoutResult) : SessionPhase

    /** The session can't be paid (expired, already paid, the business can't take payments). */
    data class Closed(val result: CheckoutResult) : SessionPhase
}

/** The selected way to pay: the sheet's Card row, one of the element's rows, or our UPI or Pix row. */
internal enum class Method { Card, Element, Upi, Pix }

internal data class SessionSnapshot(
    val phase: SessionPhase = SessionPhase.Loading,
    val config: CheckoutConfig? = null,
    /** The buyer chose to pay in their own currency. */
    val payLocal: Boolean = false,
    val charge: Charge? = null,
    val methods: SheetMethods? = null,
    /** The element has a method selected and can be paid. */
    val canPay: Boolean = false,
    /** The selected method as the element names it ("Klarna"). */
    val option: String? = null,
    /** The method the buyer paid with, kept for the receipt (the element clears its selection after paying). */
    val paidWith: String? = null,
    /** The same as Vezmo's server read it from the payment (the card's network for its logo). */
    val paidMethod: PaidMethod? = null,
    /** The engine couldn't load (shown in the sheet with Try again). */
    val elementError: String? = null,
    /** Bumped by Try again: rebuilds the engine's views. */
    val elementAttempt: Int = 0,
    val method: Method = Method.Element,
    /** The card typed in the card form, kept when the buyer looks at another method and comes back. */
    val card: CardChoice? = null,
)

private const val PRICE_CHANGED = "The price in your currency just changed. Check the new amount and pay again."

/**
 * One checkout, from the session's client token (or a subscription checkout's token) to a result. The money decisions
 * are the server's: this only moves the buyer's confirmation token to Vezmo and the answer back to the engine.
 */
internal class CheckoutSession(
    private val api: CheckoutApi,
    val token: String,
    private val returnUrl: String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** The token is a subscription checkout's: cards and Google Pay; a first payment or a free trial. */
    val subscription: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(SessionSnapshot())
    val state: StateFlow<SessionSnapshot> = _state
    private val snapshot get() = _state.value

    private var element: ElementPort? = null
    private var cardFlow: CardFlowPort? = null

    /** Which engine view started the payment in progress (its confirmation is answered there). */
    private var confirmViaCard = false
    private var lastPay: PayResponse? = null
    private var lastError: VezmoError? = null
    private var watching: Job? = null
    private var qrIntentId: String? = null

    /** The card form was opened by the buyer: the card it returns becomes the selection. */
    private var awaitingCard = false

    private fun set(change: SessionSnapshot.() -> SessionSnapshot) = _state.update(change)

    /** Runs [block] in the session's own scope (engine callbacks, the buyer coming back from a bank app). */
    fun post(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun attachElement(element: ElementPort?) {
        this.element = element
    }

    fun attachCardFlow(cardFlow: CardFlowPort?) {
        this.cardFlow = cardFlow
    }

    // ── loading and currency ───────────────────────────────────────────────
    suspend fun load() {
        try {
            val config = fetchConfig()
            // The buyer's own currency is offered first when there is an offer (as on the web checkout).
            apply(config, config.localOffer != null)
            // Only out of Loading: after the process was killed mid-payment, the engine's result can arrive first.
            set { if (phase == SessionPhase.Loading) copy(phase = SessionPhase.Ready(null)) else this }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (snapshot.phase == SessionPhase.Loading) close(e)
        }
    }

    /** The buyer switched between their own currency and the price's. */
    fun choosePayLocal(payLocal: Boolean) {
        val config = snapshot.config ?: return
        if (snapshot.phase == SessionPhase.Paying) return
        apply(config, payLocal)
    }

    /** The element's selected method changed (null: nothing selected). Choosing one leaves the card, UPI or Pix. */
    fun setOption(label: String?) {
        val leave = label != null && snapshot.method != Method.Element && snapshot.phase is SessionPhase.Ready
        set { copy(canPay = label != null, option = label?.ifEmpty { null }, method = if (leave) Method.Element else method) }
    }

    /** The buyer tapped the Card row: the card already typed, or the card form to type one. */
    fun chooseCard() {
        if (snapshot.phase !is SessionPhase.Ready) return
        if (snapshot.card != null) {
            element?.clearSelection()
            set { copy(method = Method.Card, phase = SessionPhase.Ready(null)) }
        } else {
            changeCard()
        }
    }

    /** Change (or first type) the card. */
    fun changeCard() {
        if (snapshot.phase !is SessionPhase.Ready) return
        awaitingCard = true
        cardFlow?.presentCard()
    }

    /** The card form reported its card (or none). One the buyer just typed becomes the selection. */
    fun setCard(card: CardChoice?) {
        val chosen = card != null && awaitingCard && snapshot.phase is SessionPhase.Ready
        if (card != null) awaitingCard = false
        if (chosen) element?.clearSelection()
        set {
            if (chosen) copy(card = card, method = Method.Card, phase = SessionPhase.Ready(null)) else copy(card = card)
        }
    }

    fun setElementError(message: String?) {
        set { copy(elementError = message, canPay = if (message != null) false else canPay) }
    }

    fun retryElement() {
        set { copy(elementError = null, elementAttempt = elementAttempt + 1) }
    }

    private fun apply(config: CheckoutConfig, payLocal: Boolean) {
        val charge = chargeOf(config, payLocal && config.localOffer != null)
        val methods = sheetMethods(config, charge)
        set {
            // A panel that this currency doesn't offer falls back to the element.
            val m = when {
                method == Method.Upi && !methods.upi -> Method.Element
                method == Method.Pix && !methods.pix -> Method.Element
                else -> method
            }
            copy(config = config, payLocal = charge.localOffer != null, charge = charge, methods = methods, method = m)
        }
    }

    /** UPI or Pix (the sheet's own rows) or back to the element; one method is selected at a time. */
    fun chooseMethod(method: Method) {
        if (snapshot.phase !is SessionPhase.Ready) return
        if (method != Method.Element) element?.clearSelection()
        set { copy(method = method, phase = SessionPhase.Ready(null)) }
    }

    // ── UPI and Pix ────────────────────────────────────────────────────────
    /** Start UPI or Pix: the UPI app link or the Pix code comes back; then we watch the session until it is paid. */
    suspend fun payWithQr(method: QrMethod, billing: QrBilling) {
        if (snapshot.phase !is SessionPhase.Ready) return
        set { copy(phase = SessionPhase.Paying, paidWith = method.title) }
        try {
            val intent = api.intent(token, snapshot.charge?.localOffer)
            qrIntentId = intent.id
            val qr = api.startQr(
                method,
                QrStart(intent.clientSecret, intent.connectedAccountId, intent.livemode, api.checkoutPageUrl(token), billing),
            )
            set { copy(phase = SessionPhase.Waiting(method, qr)) }
            watch()
        } catch (e: VezmoApiError) {
            if (e.code == VezmoError.Code.Expired || e.code == VezmoError.Code.AlreadyPaid) return close(e)
            // UPI and Pix are always in the buyer's currency: an offer that ran out is fetched again, not resent.
            if (e.reason == "local_price_changed") {
                refreshOffer()
                return set { copy(phase = SessionPhase.Ready(VezmoError(VezmoError.Code.Unknown, PRICE_CHANGED))) }
            }
            set { copy(phase = SessionPhase.Ready(e.toVezmoError())) }
        }
    }

    /** The buyer gave up waiting: back to the sheet (a payment made meanwhile still settles). */
    fun stopWaiting() {
        unwatch()
        if (snapshot.phase is SessionPhase.Waiting) set { copy(phase = SessionPhase.Ready(null)) }
    }

    /** Check now (the buyer came back from their bank app). */
    suspend fun checkNow() {
        if (snapshot.phase !is SessionPhase.Waiting) return
        val status = try {
            api.status(token)
        } catch (_: VezmoApiError) {
            return
        }
        val result = resultFromStatus(status)
        if (result == null || snapshot.phase !is SessionPhase.Waiting) return
        unwatch(currentCoroutineContext()[Job])
        if (result is CheckoutResult.Failed) {
            set { copy(phase = SessionPhase.Ready(VezmoError(VezmoError.Code.Declined, "The payment didn’t go through. Try again."))) }
            return
        }
        // Recorded now rather than when the webhook arrives (as the web checkout does).
        qrIntentId?.let { id -> runCatching { api.confirm(token, id) } }
        set { copy(phase = SessionPhase.Done(result)) }
    }

    private fun watch() {
        unwatch()
        watching = scope.launch {
            // A code lasts 10 minutes; stop watching a little after (every 2 seconds for 11 minutes).
            val self = coroutineContext[Job]
            repeat(330) {
                delay(2_000)
                checkNow()
                if (watching !== self) return@launch
            }
            unwatch(self)
            if (snapshot.phase is SessionPhase.Waiting) set { copy(phase = SessionPhase.Ready(null)) }
        }
    }

    /** Stops the watcher; [self] is the watcher itself when it stops on its own (it is not cancelled mid-step). */
    private fun unwatch(self: Job? = null) {
        val job = watching
        watching = null
        if (job != null && job !== self) job.cancel()
    }

    /** The sheet is going away. */
    fun dispose() {
        scope.cancel()
    }

    // ── paying ─────────────────────────────────────────────────────────────
    fun pay() {
        if (snapshot.phase !is SessionPhase.Ready || !canPay(snapshot)) return
        val card = snapshot.method == Method.Card
        if (if (card) cardFlow == null else element == null) return
        lastPay = null
        lastError = null
        confirmViaCard = card
        set { copy(phase = SessionPhase.Paying, paidWith = if (card) this.card?.label else option) }
        if (card) cardFlow?.confirmCard() else element?.confirm()
    }

    /** An express wallet button was tapped (Google Pay): its payment has started in the card flow. */
    fun walletStarted(wallet: String) {
        if (snapshot.phase !is SessionPhase.Ready) return
        lastPay = null
        lastError = null
        confirmViaCard = true
        val name = when (wallet) {
            "google_pay" -> "Google Pay"
            "apple_pay" -> "Apple Pay"
            else -> wallet
        }
        set { copy(phase = SessionPhase.Paying, paidWith = name) }
    }

    /** The engine view that started the payment in progress answers its confirmation. */
    private fun resolve(clientSecret: String?, errorMessage: String?) {
        if (confirmViaCard) cardFlow?.resolveConfirmation(clientSecret, errorMessage) else element?.resolveConfirmation(clientSecret, errorMessage)
    }

    /** The engine made a confirmation token: Vezmo prices and confirms the payment with it. */
    suspend fun onConfirmationToken(id: String) {
        val charge = snapshot.charge
        try {
            val res = if (subscription) {
                api.subscriptionPay(token, id, charge?.localOffer)
            } else {
                api.pay(token, id, charge?.localOffer, returnUrl)
            }
            lastPay = res
            res.method?.let { m -> set { copy(paidMethod = m) } }
            resolve(res.clientSecret, if (res.clientSecret != null) null else DECLINED.message)
        } catch (e: VezmoApiError) {
            val error = if (e.reason == "local_price_changed") {
                refreshOffer()
                VezmoError(VezmoError.Code.Unknown, PRICE_CHANGED)
            } else {
                e.toVezmoError()
            }
            lastError = error
            resolve(null, error.message)
            if (e.code == VezmoError.Code.Expired || e.code == VezmoError.Code.AlreadyPaid) close(e)
        }
    }

    /** The engine finished: completed (maybe after 3D Secure), canceled, or failed. */
    suspend fun onConfirmResult(status: ConfirmStatus, message: String) {
        if (snapshot.phase is SessionPhase.Closed) return
        when (status) {
            ConfirmStatus.Canceled -> set { copy(phase = SessionPhase.Ready(null)) }
            ConfirmStatus.Failed -> {
                val error = lastError ?: when (val verdict = deviceFailure()) {
                    null -> null
                    is CheckoutResult.Failed -> verdict.error
                    else -> {
                        set { copy(phase = SessionPhase.Done(verdict)) }
                        return
                    }
                }
                set {
                    copy(phase = SessionPhase.Ready(error ?: VezmoError(VezmoError.Code.Declined, message.ifEmpty { DECLINED.message })))
                }
            }
            ConfirmStatus.Completed -> {
                // A payment our server already recorded is done; one finished on the device (3D Secure, a redirect)
                // is recorded now, then read back.
                val fromPay = lastPay?.let(::resultFromPay)
                if (fromPay != null) {
                    set { copy(phase = SessionPhase.Done(fromPay)) }
                    return
                }
                if (subscription) {
                    val result = startedSubscription(intentIdOf(lastPay?.clientSecret))
                    set { copy(phase = SessionPhase.Done(result)) }
                    return
                }
                intentIdOf(lastPay?.clientSecret)?.let { id ->
                    // The webhook records it either way; the status below is the answer.
                    runCatching { api.confirm(token, id) }
                }
                val result = settledResult()
                set { copy(phase = SessionPhase.Done(result)) }
            }
        }
    }

    /**
     * A payment Vezmo handed to the device (3D Secure, a browser, a bank app) that failed or was lost there: Vezmo's
     * verdict. Paid after all; or why not, in Vezmo's words (the same on every phone, where the engine words a decline
     * differently), its confirm step recording the decline at once. Null when Vezmo has no answer.
     */
    private suspend fun deviceFailure(): CheckoutResult? {
        val id = intentIdOf(lastPay?.clientSecret)?.takeIf { lastPay?.state == "requires_action" } ?: return null
        if (!subscription) {
            // The session's state first: a step only lost (the payment still waiting on it) has no verdict to confirm.
            val status = try {
                api.status(token)
            } catch (_: VezmoApiError) {
                return null
            }
            resultFromStatus(status)?.let { if (it !is CheckoutResult.Failed) return it }
            if (status.state != "failed") return null
        }
        return try {
            if (subscription) {
                api.subscriptionConfirm(token, id)
            } else {
                api.confirm(token, id)
                null
            }
        } catch (e: VezmoApiError) {
            if (e.code == VezmoError.Code.Declined) CheckoutResult.Failed(e.toVezmoError()) else null
        }
    }

    /** The buyer's currency offer no longer holds: fetch the new one; the buyer sees the new amount before paying again. */
    private fun refreshOffer() = post { runCatching { fetchConfig() }.getOrNull()?.let { apply(it, snapshot.payLocal) } }

    private suspend fun fetchConfig(): CheckoutConfig = if (subscription) api.subscription(token) else api.checkout(token)

    /** A subscription started on the device (after 3D Secure): Vezmo records it and says how it stands. */
    private suspend fun startedSubscription(intentId: String?): CheckoutResult {
        if (intentId != null) {
            try {
                return api.subscriptionConfirm(token, intentId)
            } catch (_: VezmoApiError) {
                // Recorded from the processor's notice instead; the merchant's webhook tells them.
            }
        }
        val ref = snapshot.config?.subscription?.ref ?: ""
        return CheckoutResult.Processing(null, SubscriptionResult(ref, SubscriptionResult.Status.Incomplete))
    }

    /** The session's state, polled briefly while the server catches up with a payment just made. */
    private suspend fun settledResult(): CheckoutResult {
        repeat(5) {
            try {
                resultFromStatus(api.status(token))?.let { return it }
            } catch (_: VezmoApiError) {
                // retry
            }
            delay(1_500)
        }
        // Paid on the device and not yet visible: the webhook will tell the merchant's server.
        return CheckoutResult.Processing(snapshot.config?.paymentId ?: "")
    }

    // ── closing ────────────────────────────────────────────────────────────
    private fun close(e: Exception) {
        val err = (e as? VezmoApiError)?.toVezmoError() ?: VezmoError(VezmoError.Code.Network, "Check your connection and try again.")
        val error = if (err.code == VezmoError.Code.Expired) EXPIRED else err
        set { copy(phase = SessionPhase.Closed(CheckoutResult.Failed(error))) }
    }

    companion object {
        /** The Pay button can be pressed: a typed card on the Card row, or a method chosen in the element. */
        fun canPay(s: SessionSnapshot): Boolean =
            when (s.method) {
                Method.Card -> s.card != null
                Method.Element -> s.canPay
                else -> false
            }
    }
}

internal enum class ConfirmStatus { Completed, Canceled, Failed }
