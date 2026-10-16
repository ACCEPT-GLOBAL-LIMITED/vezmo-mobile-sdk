package com.vezmo.checkout

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val BASE = config(enabledMethods = setOf("card", "klarna"), localOffer = null)

/** The API, faked: each answer can be swapped, every call is recorded. */
private class FakeApi : CheckoutApi {
    val calls = mutableListOf<String>()
    var checkout: () -> CheckoutConfig = { BASE }
    var pay: () -> PayResponse = { error("no pay answer") }
    val statuses = ArrayDeque<SessionStatus>()
    var status = SessionStatus("succeeded", "pay_1", "VT1")
    var payArgs: List<String?>? = null
    var qrArgs: Pair<QrMethod, QrStart>? = null

    override suspend fun checkout(token: String, currency: String?): CheckoutConfig {
        calls += "checkout"
        return checkout()
    }

    override suspend fun pay(token: String, confirmationToken: String, localOffer: String?, returnUrl: String?): PayResponse {
        calls += "pay"
        payArgs = listOf(token, confirmationToken, localOffer, returnUrl)
        return pay()
    }

    override suspend fun intent(token: String, localOffer: String?): SessionIntent {
        calls += "intent:$token:$localOffer"
        return SessionIntent("pi_7", "pi_7_secret_q", "acct_1", false)
    }

    override suspend fun startQr(method: QrMethod, start: QrStart): QrPayment {
        qrArgs = method to start
        return QrPayment("requires_action", null, null, "upi://pay?pa=x", null)
    }

    override fun checkoutPageUrl(token: String) = "https://api.test/v1/secure-payments/$token"

    override suspend fun confirm(token: String, paymentIntentId: String) {
        calls += "confirm:$token:$paymentIntentId"
    }

    override suspend fun status(token: String): SessionStatus {
        calls += "status"
        return statuses.removeFirstOrNull() ?: status
    }

    override suspend fun subscription(token: String, currency: String?): CheckoutConfig = error("not a subscription")

    override suspend fun subscriptionPay(token: String, confirmationToken: String, localOffer: String?): PayResponse = error("not a subscription")

    override suspend fun subscriptionConfirm(token: String, intentId: String): CheckoutResult = error("not a subscription")
}

private class FakeElement : ElementPort {
    var confirms = 0
    var clears = 0
    val resolved = mutableListOf<Pair<String?, String?>>()

    override fun confirm() {
        confirms++
    }

    override fun resolveConfirmation(clientSecret: String?, errorMessage: String?) {
        resolved += clientSecret to errorMessage
    }

    override fun clearSelection() {
        clears++
    }
}

private class FakeCardFlow : CardFlowPort {
    var presents = 0
    var confirms = 0
    val resolved = mutableListOf<Pair<String?, String?>>()

    override fun presentCard() {
        presents++
    }

    override fun confirmCard() {
        confirms++
    }

    override fun resolveConfirmation(clientSecret: String?, errorMessage: String?) {
        resolved += clientSecret to errorMessage
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Setup(scope: TestScope) {
    val api = FakeApi()
    val element = FakeElement()
    val cardFlow = FakeCardFlow()
    val session = CheckoutSession(api, "tok", "acme://vezmo/return", UnconfinedTestDispatcher(scope.testScheduler)).also {
        it.attachElement(element)
        it.attachCardFlow(cardFlow)
    }
    val snap get() = session.state.value
}

class CheckoutSessionTest {
    private fun test(block: suspend TestScope.(Setup) -> Unit) = runTest {
        val t = Setup(this)
        t.session.load()
        try {
            block(t)
        } finally {
            t.session.dispose()
        }
    }

    @Test
    fun `loads the session and offers card plus the enabled methods`() = test { t ->
        assertEquals(SessionPhase.Ready(null), t.snap.phase)
        assertEquals(Charge(4900, "USD", null), t.snap.charge)
        assertEquals(listOf("card", "klarna"), t.snap.methods?.elementTypes)
    }

    @Test
    fun `a payment the server finished - hands the engine the secret, the result is the server's`() = test { t ->
        val method = PaidMethod("card", "visa", "4242", null)
        t.api.pay = { PayResponse("succeeded", "pay_1", "VT1", "pi_1_secret_a", method) }
        t.session.setOption("Klarna")
        t.session.pay()
        assertEquals(1, t.element.confirms)
        t.session.onConfirmationToken("ctoken_1")
        // The receipt's Visa mark and last four come from the server's reading of the payment.
        assertEquals(method, t.snap.paidMethod)
        assertEquals(listOf("tok", "ctoken_1", null, "acme://vezmo/return"), t.api.payArgs)
        assertEquals(listOf<Pair<String?, String?>>("pi_1_secret_a" to null), t.element.resolved)
        t.session.onConfirmResult(ConfirmStatus.Completed, "")
        assertEquals(SessionPhase.Done(CheckoutResult.Succeeded("pay_1", "VT1")), t.snap.phase)
        assertFalse(t.api.calls.any { it.startsWith("confirm") })
    }

    @Test
    fun `3D Secure finished on the device is recorded with confirm, then read back`() = test { t ->
        t.api.pay = { PayResponse("requires_action", "pay_1", null, "pi_9_secret_b", null) }
        t.session.setOption("Klarna")
        t.session.pay()
        t.session.onConfirmationToken("ctoken_1")
        t.session.onConfirmResult(ConfirmStatus.Completed, "")
        assertTrue("confirm:tok:pi_9" in t.api.calls)
        assertEquals(SessionPhase.Done(CheckoutResult.Succeeded("pay_1", "VT1")), t.snap.phase)
    }

    @Test
    fun `the engine losing a redirect step it finished - the server's answer wins`() = test { t ->
        t.api.pay = { PayResponse("requires_action", "pay_1", null, "pi_9_secret_b", null) }
        t.session.setOption("Cash App Pay")
        t.session.pay()
        t.session.onConfirmationToken("ctoken_1")
        t.session.onConfirmResult(ConfirmStatus.Failed, "engine text")
        assertEquals(SessionPhase.Done(CheckoutResult.Succeeded("pay_1", "VT1")), t.snap.phase)

        // Not paid after all: the buyer can try again.
        t.api.status = SessionStatus("open", "pay_1", null)
        val u = Setup(this)
        u.api.pay = { PayResponse("requires_action", "pay_1", null, "pi_9_secret_b", null) }
        u.api.status = SessionStatus("open", "pay_1", null)
        u.session.load()
        u.session.setOption("Cash App Pay")
        u.session.pay()
        u.session.onConfirmationToken("ctoken_1")
        u.session.onConfirmResult(ConfirmStatus.Failed, "")
        assertEquals(SessionPhase.Ready(DECLINED), u.snap.phase)
        u.session.dispose()
    }

    @Test
    fun `a decline goes back to the engine in our words and leaves the sheet ready to retry`() = test { t ->
        t.api.pay = { throw VezmoApiError(VezmoError.Code.Declined, "Your bank declined this card.", 400) }
        t.session.setOption("Klarna")
        t.session.pay()
        t.session.onConfirmationToken("ctoken_1")
        assertEquals(listOf<Pair<String?, String?>>(null to "Your bank declined this card."), t.element.resolved)
        t.session.onConfirmResult(ConfirmStatus.Failed, "processor text")
        assertEquals(SessionPhase.Ready(VezmoError(VezmoError.Code.Declined, "Your bank declined this card.")), t.snap.phase)
    }

    @Test
    fun `a changed local price refreshes the offer and asks the buyer to pay again`() = test { t ->
        t.api.pay = { throw VezmoApiError(VezmoError.Code.Unknown, "Price changed", 409, "local_price_changed") }
        t.session.setOption("Klarna")
        t.session.pay()
        t.session.onConfirmationToken("ctoken_1")
        assertTrue(t.element.resolved.single().second!!.contains("price in your currency"))
        assertEquals(2, t.api.calls.count { it == "checkout" })
    }

    @Test
    fun `canceling the engine returns to the sheet, an expired session closes it`() = test { t ->
        t.session.setOption("Klarna")
        t.session.pay()
        t.session.onConfirmResult(ConfirmStatus.Canceled, "")
        assertEquals(SessionPhase.Ready(null), t.snap.phase)

        t.api.pay = { throw VezmoApiError(VezmoError.Code.Expired, "Secure-payments token has expired", 410) }
        t.session.pay()
        t.session.onConfirmationToken("ctoken_2")
        val closed = t.snap.phase as SessionPhase.Closed
        assertEquals(VezmoError.Code.Expired, (closed.result as CheckoutResult.Failed).error.code)
    }

    @Test
    fun `the buyer's own currency - the charge and methods follow the choice`() = test { t ->
        val u = Setup(this)
        u.api.checkout = { BASE.copy(localOffer = INR_OFFER) }
        u.session.load()
        assertEquals(Charge(420000, "INR", "offer_sealed"), u.snap.charge)
        assertEquals(listOf("card"), u.snap.methods?.elementTypes)
        u.session.choosePayLocal(false)
        assertEquals("USD", u.snap.charge?.currency)
        assertEquals(listOf("card", "klarna"), u.snap.methods?.elementTypes)
        u.session.dispose()
    }

    @Test
    fun `one method at a time - UPI unselects the element, picking an element method leaves UPI`() = test { t ->
        t.session.setOption("Klarna")
        assertTrue(t.snap.canPay)
        assertEquals("Klarna", t.snap.option)
        assertEquals(Method.Element, t.snap.method)
        t.session.chooseMethod(Method.Upi)
        assertEquals(1, t.element.clears)
        t.session.setOption(null) // the element reports the cleared selection
        assertFalse(t.snap.canPay)
        assertNull(t.snap.option)
        assertEquals(Method.Upi, t.snap.method)
        t.session.setOption("Cash App")
        assertEquals(Method.Element, t.snap.method)
        // The receipt keeps what was paid with, though the element clears its selection after paying.
        t.session.pay()
        t.session.setOption(null)
        assertEquals("Cash App", t.snap.paidWith)
    }

    @Test
    fun `the Card row - opens the card form, the typed card becomes the selection, and pays through it`() = test { t ->
        t.session.chooseCard()
        assertEquals(1, t.cardFlow.presents)
        assertFalse(CheckoutSession.canPay(t.snap))
        val card = CardChoice("•••• 4242", null)
        t.session.setCard(card)
        assertEquals(Method.Card, t.snap.method)
        assertEquals(card, t.snap.card)
        assertEquals(1, t.element.clears)
        // Another method in the element leaves the Card row but keeps the card for later.
        t.session.setOption("Klarna")
        assertEquals(Method.Element, t.snap.method)
        assertEquals(card, t.snap.card)
        // A re-sent card (the amount changed) does not take the selection back.
        t.session.setCard(card)
        assertEquals(Method.Element, t.snap.method)
        t.session.chooseCard()
        assertEquals(Method.Card, t.snap.method)
        t.session.pay()
        assertEquals(1, t.cardFlow.confirms)
        assertEquals(0, t.element.confirms)
        assertEquals("•••• 4242", t.snap.paidWith)
    }

    @Test
    fun `the card flow answers its own confirmation (card form or wallet), the element its own`() = test { t ->
        t.api.pay = { PayResponse("requires_action", "pay_1", null, "pi_1_secret_w", null) }
        t.session.walletStarted("google_pay")
        assertEquals(SessionPhase.Paying, t.snap.phase)
        assertEquals("Google Pay", t.snap.paidWith)
        t.session.onConfirmationToken("ctoken_w")
        assertEquals(listOf<Pair<String?, String?>>("pi_1_secret_w" to null), t.cardFlow.resolved)
        assertTrue(t.element.resolved.isEmpty())
    }

    @Test
    fun `UPI - starts on the session intent, waits for the bank app, records it when paid`() = test { t ->
        val u = Setup(this)
        u.api.checkout = { BASE.copy(enabledMethods = setOf("card", "upi"), localOffer = INR_OFFER) }
        u.session.load()
        assertTrue(u.snap.methods!!.upi)
        u.session.chooseMethod(Method.Upi)
        u.api.statuses += SessionStatus("requires_action", "pay_1", null)

        val billing = QrBilling(name = "Asha Rao", line1 = "12 MG Road", country = "IN")
        u.session.payWithQr(QrMethod.Upi, billing)
        assertTrue("intent:tok:offer_sealed" in u.api.calls)
        assertEquals(
            QrMethod.Upi to QrStart("pi_7_secret_q", "acct_1", false, "https://api.test/v1/secure-payments/tok", billing),
            u.api.qrArgs,
        )
        val waiting = u.snap.phase as SessionPhase.Waiting
        assertEquals(QrMethod.Upi, waiting.method)
        assertEquals("upi://pay?pa=x", waiting.qr.link)

        u.session.checkNow() // still in the bank app
        assertTrue(u.snap.phase is SessionPhase.Waiting)
        u.session.checkNow() // paid
        assertTrue("confirm:tok:pi_7" in u.api.calls)
        assertEquals(SessionPhase.Done(CheckoutResult.Succeeded("pay_1", "VT1")), u.snap.phase)
        u.session.dispose()
    }

    @Test
    fun `the UPI watcher checks by itself and stops once paid`() = test { t ->
        val u = Setup(this)
        u.api.checkout = { BASE.copy(enabledMethods = setOf("card", "upi"), localOffer = INR_OFFER) }
        u.session.load()
        u.session.chooseMethod(Method.Upi)
        u.api.statuses += SessionStatus("requires_action", "pay_1", null)
        u.session.payWithQr(QrMethod.Upi, QrBilling(name = "Asha Rao", line1 = "12 MG Road"))
        testScheduler.advanceTimeBy(4_100) // two ticks: still waiting, then paid
        assertEquals(SessionPhase.Done(CheckoutResult.Succeeded("pay_1", "VT1")), u.snap.phase)
        val checks = u.api.calls.count { it == "status" }
        testScheduler.advanceTimeBy(60_000)
        assertEquals(checks, u.api.calls.count { it == "status" })
        u.session.dispose()
    }
}
