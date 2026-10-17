package com.vezmo.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import java.util.Locale
import kotlinx.coroutines.launch

/** Room for the gesture bar under the sheet (more when the phone has a taller navigation bar or the keyboard is up). */
private val BOTTOM_INSET = 28.dp

private val EASE_OUT_CUBIC = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/**
 * The Vezmo payment sheet: the business and the total, the buyer's currency, one list of ways to pay (the sheet's Card
 * row, the engine's rows and our own UPI or Pix), our Pay button and our result screens. Drawn over a dimmed backdrop
 * in the sheet's own see-through activity.
 */
@Composable
internal fun CheckoutSheet(
    session: CheckoutSession,
    engine: Engine,
    request: CheckoutRequest,
    dark: Boolean,
    onClose: (CheckoutResult) -> Unit,
    /** False in the sheet's screenshot tests: the engine is left alone (no keys) and the Card row shows at once. */
    useEngine: Boolean = true,
) {
    val snap by session.state.collectAsState()
    val configuration = request.configuration
    val appearance = configuration.appearance
    val p = remember(dark, appearance) { paletteFor(dark, appearance) }
    val config = snap.config
    val charge = snap.charge
    val methods = snap.methods
    val phase = snap.phase
    val merchant = request.merchantDisplayName ?: config?.merchantName ?: ""
    val context = LocalContext.current

    // The engine is rebuilt only for new keys, wallets or look, never for a new amount.
    val settings = remember(config?.publishableKey, config?.connectedAccountId, methods?.googlePay, charge?.currency, p, merchant, snap.elementAttempt) {
        if (config == null || charge == null || methods == null) {
            null
        } else {
            EngineSettings(
                publishableKey = config.publishableKey,
                connectedAccountId = config.connectedAccountId,
                merchantDisplayName = merchant,
                googlePay = if (methods.googlePay) {
                    EngineSettings.GooglePay(configuration.googlePay?.merchantCountryCode ?: "US", charge.currency, !config.livemode)
                } else {
                    null
                },
                billingName = config.customerName?.ifEmpty { null },
                billingEmail = config.customerEmail?.ifEmpty { null },
                appearance = engineAppearance(p, appearance),
                attempt = snap.elementAttempt,
            )
        }
    }
    val intent = remember(charge, methods, config) { if (charge != null && methods != null) engineIntent(charge, methods, config) else null }
    LaunchedEffect(settings, intent) {
        if (useEngine && settings != null && intent != null) engine.configure(context, settings, intent)
    }
    // The engine takes a moment to load: placeholder rows hold its place until it reports in.
    val elementLoading = useEngine && (engine.cardLoaded != settings || engine.elementLoaded != settings) && snap.elementError == null

    val amount = charge?.let { formatAmount(it.amountMinor, it.currency) } ?: ""
    val paying = phase == SessionPhase.Paying
    val finished = phase is SessionPhase.Done || phase is SessionPhase.Closed
    val qrForm = remember { QrFormState(config?.customerName ?: "") }

    BackHandler {
        when (phase) {
            SessionPhase.Paying -> Unit // a payment in flight finishes first
            is SessionPhase.Done -> onClose(phase.result)
            is SessionPhase.Closed -> onClose(phase.result)
            else -> onClose(CheckoutResult.Canceled)
        }
    }

    // The sheet rises over a fading scrim.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320, easing = EASE_OUT_CUBIC)) }
    val density = LocalDensity.current

    ProvideLook(p, appearance, dark) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val maxSheet = minOf(maxHeight * 0.92f, maxHeight - statusTop)
            val bottom = max(BOTTOM_INSET, WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding())
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = enter.value }
                    .background(p.scrim),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheet)
                    .graphicsLayer { translationY = with(density) { 80.dp.toPx() } * (1f - enter.value) }
                    .boxShadow(Color(11, 12, 20).copy(alpha = 0.18f), (-10).dp, 40.dp, 0.dp, 28.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(p.surface),
            ) {
                // A faint wash of the brand behind the business and the total.
                if (!finished) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .alpha(0.6f)
                            .background(Brush.verticalGradient(listOf(p.accentSoft, p.accentSoft.copy(alpha = 0f)))),
                    )
                }
                Column(Modifier.padding(bottom = bottom)) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(36.dp, 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(p.fieldLine),
                    )
                    when {
                        phase == SessionPhase.Loading -> Skeleton()
                        finished -> ResultView(
                            result = (phase as? SessionPhase.Done)?.result ?: (phase as SessionPhase.Closed).result,
                            amount = amount,
                            merchant = merchant,
                            plan = config?.subscription,
                            paidWith = snap.paidWith,
                            paidMethod = snap.paidMethod,
                            onDone = onClose,
                        )
                        config != null && charge != null && methods != null -> SheetBody(
                            session = session,
                            engine = engine,
                            snap = snap,
                            config = config,
                            methods = methods,
                            merchant = merchant,
                            amount = amount,
                            elementLoading = elementLoading,
                            qrForm = qrForm,
                        )
                    }
                }
                // The result has its own way out.
                if (!finished) {
                    // A 30dp button in a 54dp touch target (as on iPhone).
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 21.dp)
                            .size(54.dp)
                            .clickable(
                                enabled = !paying,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { onClose(CheckoutResult.Canceled) }
                            .semantics { contentDescription = "Close" }
                            .padding(12.dp)
                            .alpha(if (paying) 0.4f else 1f)
                            .clip(CircleShape)
                            .background(p.fill),
                        contentAlignment = Alignment.Center,
                    ) { Cross(p.ink2, 11.dp) }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SheetBody(
    session: CheckoutSession,
    engine: Engine,
    snap: SessionSnapshot,
    config: CheckoutConfig,
    methods: SheetMethods,
    merchant: String,
    amount: String,
    elementLoading: Boolean,
    qrForm: QrFormState,
) {
    val look = LocalLook.current
    val p = look.p
    val phase = snap.phase
    val paying = phase == SessionPhase.Paying
    // A subscription checkout: what is due today, the plan after it, and a button that says what it starts.
    val plan = config.subscription
    val own = when {
        methods.upi -> QrMethod.Upi
        methods.pix -> QrMethod.Pix
        else -> null
    }
    // The Card row is the sheet's own; the engine draws the other methods and this phone's wallet when offered.
    val cardOn = "card" in methods.elementTypes
    val elementRows = methods.elementTypes.any { it != "card" }
    // Placeholders for the rows about to load (the Card row and the element's).
    val expectedRows = maxOf(1, methods.elementTypes.size)
    val scroll = rememberScrollState()
    val qrMethod = when (snap.method) {
        Method.Upi -> QrMethod.Upi
        Method.Pix -> QrMethod.Pix
        else -> null
    }
    // What went wrong shows under the form or the list, maybe below the fold: bring it into view.
    val scope = rememberCoroutineScope()
    val failed = (phase as? SessionPhase.Ready)?.error
    LaunchedEffect(failed) { if (failed != null) scroll.revealEnd() }

    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(scroll),
    ) {
        Header(config, merchant, amount)

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
            if (phase is SessionPhase.Waiting) {
                QrWaiting(session, phase.method, phase.qr, amount)
                return@Column
            }
            val offer = config.localOffer
            if (offer != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Pay in")
                    Segmented(
                        items = listOf(
                            Triple(true, formatAmount(offer.amountMinor, offer.currency), offer.currency.uppercase()),
                            Triple(false, formatAmount(config.amountMinor, config.currency), config.currency.uppercase()),
                        ),
                        value = snap.payLocal,
                        onChange = { session.choosePayLocal(it) },
                        disabled = paying,
                        tags = listOf("vezmo-pay-in-local", "vezmo-pay-in-price"),
                    )
                    if (snap.payLocal) {
                        Txt(
                            "1 ${config.currency.uppercase()} = ${String.format(Locale.US, "%.4f", offer.rate)} ${offer.currency.uppercase()}",
                            12.5f,
                            p.ink3,
                            lineHeight = 17f,
                        )
                    }
                }
            }

            // Express: the official Google Pay button, when this phone and the business have one.
            val walletsShown = engine.walletsOn && engine.walletsHeight > 1
            if (engine.walletsOn) {
                Column(
                    Modifier.padding(top = if (offer != null && walletsShown) 24.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(if (walletsShown) 8.dp else 0.dp),
                ) {
                    engine.WalletButtons()
                    if (walletsShown) {
                        Row(
                            Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val hair = hairline()
                            Box(Modifier.weight(1f).height(hair).background(p.fieldLine))
                            Txt("or pay another way", 13f, p.ink3, weight = FontWeight.Medium)
                            Box(Modifier.weight(1f).height(hair).background(p.fieldLine))
                        }
                    }
                }
            }

            Column(
                Modifier.padding(top = if (offer != null || walletsShown) 24.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Label("Payment method", Modifier.weight(1f))
                    // The card networks, as their real marks (the Card row itself shows a plain card).
                    Row(
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Visa, Mastercard, American Express and Discover accepted"
                        },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BrandTile(Mark.Visa)
                        BrandTile(Mark.Mastercard)
                        BrandTile(Mark.Amex)
                        BrandTile(Mark.Discover)
                    }
                }
                Group(Modifier.semantics { contentDescription = "Payment method" }) {
                    val ok = !elementLoading && snap.elementError == null
                    if (elementLoading) RowsSkeleton(expectedRows)
                    if (cardOn && ok) {
                        CardRow(
                            card = snap.card,
                            selected = snap.method == Method.Card,
                            disabled = paying,
                            onClick = { session.chooseCard() },
                            onChange = { session.changeCard() },
                        )
                    }
                    if (elementRows && ok) {
                        Box(if (cardOn) Modifier.topLine(p.line, hairline()) else Modifier) { engine.ElementRows() }
                    }
                    if (snap.elementError != null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Txt("Couldn’t load payment options", 15f, p.ink, weight = FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.testTag("vezmo-element-error"))
                            Txt("Check your connection and try again.", 13f, p.ink3, align = TextAlign.Center)
                        }
                    }
                    if (own != null) {
                        MethodRow(
                            icon = { BrandTile(if (own == QrMethod.Upi) Mark.Upi else Mark.Pix, large = true) },
                            title = own.title,
                            sub = if (own == QrMethod.Upi) "Pay from your UPI app" else "Instant transfer from your bank app",
                            selected = snap.method == (if (own == QrMethod.Upi) Method.Upi else Method.Pix),
                            disabled = paying,
                            first = false,
                            onClick = { session.chooseMethod(if (own == QrMethod.Upi) Method.Upi else Method.Pix) },
                        )
                    }
                }
            }

            if (qrMethod != null) {
                QrForm(
                    state = qrForm,
                    method = qrMethod,
                    busy = paying,
                    error = (phase as? SessionPhase.Ready)?.error?.message,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else if (phase is SessionPhase.Ready && phase.error != null) {
                Box(Modifier.padding(top = 24.dp)) { Banner(phase.error.message) }
            }
        }
    }

    // The pinned bottom: the main button and "Secured by", always in reach however long the content above.
    val hair = hairline()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (scroll.maxValue > 0) Modifier.topLine(p.line, hair) else Modifier)
            .background(p.surface)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            phase is SessionPhase.Waiting -> Unit
            qrMethod != null -> PrimaryButton(
                label = if (qrMethod == QrMethod.Upi) "Pay $amount with UPI" else "Get Pix code for $amount",
                lock = true,
                busy = paying,
                onClick = {
                    val billing = qrForm.submit(qrMethod, config.localOffer?.country)
                    if (billing == null) scope.launch { scroll.revealEnd() } else session.post { session.payWithQr(qrMethod, billing) }
                },
            )
            snap.elementError != null -> PrimaryButton("Try again", onClick = { session.retryElement() }, tag = "vezmo-retry")
            else -> PrimaryButton(
                // A subscription's button says what it starts.
                label = when {
                    plan == null -> "Pay $amount"
                    plan.trial -> "Start free trial"
                    else -> "Subscribe · $amount"
                },
                lock = true,
                disabled = !CheckoutSession.canPay(snap),
                busy = paying,
                onClick = { session.pay() },
                tag = "vezmo-pay",
            )
        }
        // The recurring authorization, small, right above the button that gives it.
        if (plan != null && phase !is SessionPhase.Waiting) {
            Txt(plan.consentText, 12f, p.ink3, lineHeight = 16f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        SecuredBy()
    }
}

/** Scrolls to the end, once the content that just changed has been laid out. */
private suspend fun ScrollState.revealEnd() {
    // ponytail: two frames for the new content's layout; await placement instead if it ever lands later.
    repeat(2) { withFrameNanos { } }
    animateScrollTo(maxValue)
}

/** The business, what is being paid for, and the total. */
@Composable
private fun Header(config: CheckoutConfig, merchant: String, amount: String) {
    val p = LocalLook.current.p
    val hair = hairline()
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.padding(end = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .boxShadow(Color(16, 24, 64).copy(alpha = 0.06f), 1.dp, 2.dp, 0.dp, 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.logoTile)
                    .border(hair, p.fieldLine, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Txt((merchant.trim().firstOrNull()?.toString() ?: "•").uppercase(), 17f, p.accent, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(merchant, 15f, p.ink, weight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!config.livemode) {
                        Box(
                            Modifier
                                .heightIn(min = 20.dp)
                                .clip(CircleShape)
                                .background(p.warn)
                                .padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt("TEST MODE", 10f, Color.White, weight = FontWeight.Bold, letterSpacing = 0.6f) }
                    }
                }
                if (config.title.isNotEmpty()) Txt(config.title, 13f, p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(if (config.subscription != null) "Due today" else "Total")
            Txt(amount, 34f, p.ink, weight = FontWeight.Bold, lineHeight = 40f, letterSpacing = -0.9f, tabular = true)
        }
        config.subscription?.let { PlanSummary(it) }
    }
}

/** The plan under the total: "7-day free trial · Today", then "$9.99 / month · From Oct 23, 2026". */
@Composable
private fun PlanSummary(plan: SubscriptionPlan) {
    val look = LocalLook.current
    val p = look.p
    val next = dateText(plan.nextChargeAt)
    val price = planPriceText(plan)
    val trialText = plan.trialDays?.let { "$it-day free trial" } ?: "Free trial"
    val whenText = if (next.isEmpty()) "" else if (plan.trial) "From $next" else "Renews $next"
    val label = listOfNotNull(if (plan.trial) "$trialText, starting today" else null, price, whenText.ifEmpty { null }).joinToString(". ")
    val hair = hairline()
    // One element for screen readers (and the tests): both lines in one label.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(look.radius))
            .background(p.surface2)
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics { contentDescription = label },
    ) {
        @Composable
        fun line(title: String, sub: String, first: Boolean) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (first) Modifier else Modifier.topLine(p.line, hair))
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt(title, 15f, p.ink, weight = FontWeight.SemiBold, tabular = true, modifier = Modifier.weight(1f))
                if (sub.isNotEmpty()) Txt(sub, 13f, p.ink3, tabular = true)
            }
        }
        if (plan.trial) line(trialText, "Today", first = true)
        line(price, whenText, first = !plan.trial)
    }
}

@Composable
private fun ResultView(
    result: CheckoutResult,
    amount: String,
    merchant: String,
    plan: SubscriptionPlan?,
    paidWith: String?,
    paidMethod: PaidMethod?,
    onDone: (CheckoutResult) -> Unit,
) {
    val look = LocalLook.current
    val p = look.p
    val failed = result as? CheckoutResult.Failed
    val sub = (result as? CheckoutResult.Succeeded)?.subscription ?: (result as? CheckoutResult.Processing)?.subscription
    val trialing = sub?.status == SubscriptionResult.Status.Trialing
    val next = dateText(plan?.nextChargeAt)
    data class Copy(val title: String, val body: String, val kind: MarkKind, val action: String)
    val copy = when {
        result is CheckoutResult.Succeeded && sub != null && trialing ->
            Copy("Your free trial has started", if (next.isEmpty()) "" else "Nothing is charged until $next.", MarkKind.Good, "Done")
        result is CheckoutResult.Succeeded && sub != null -> Copy("You’re subscribed", "", MarkKind.Good, "Done")
        result is CheckoutResult.Succeeded -> Copy("Payment successful", "", MarkKind.Good, "Done")
        result is CheckoutResult.Processing ->
            Copy(if (sub != null) "Subscription received" else "Payment received", "Your payment is on its way. You’ll get a confirmation when it clears.", MarkKind.Wait, "Done")
        failed == null -> Copy("Payment canceled", "", MarkKind.Neutral, "Close")
        failed.error.code == VezmoError.Code.Expired -> Copy("Checkout expired", "Return to ${merchant.ifEmpty { "the app" }} to start again.", MarkKind.Neutral, "Close")
        failed.error.code == VezmoError.Code.AlreadyPaid -> Copy("Already paid", failed.error.message, MarkKind.Good, "Done")
        else -> Copy("Payment not completed", failed.error.message, MarkKind.Bad, "Close")
    }
    val paymentId = when (result) {
        is CheckoutResult.Succeeded -> result.paymentId
        is CheckoutResult.Processing -> result.paymentId
        else -> null
    } ?: ""
    // The short reference the buyer can quote: the subscription's, else the transaction's, else the payment's own id.
    val reference = sub?.ref?.ifEmpty { null } ?: (result as? CheckoutResult.Succeeded)?.transactionId?.ifEmpty { null } ?: paymentId
    // Paid with: the network's mark and last four from Vezmo's server, else the engine's own label (or UPI / Pix).
    val mark = Mark.forBrand(paidMethod?.brand) ?: when (paidWith) {
        "UPI" -> Mark.Upi
        "Pix" -> Mark.Pix
        else -> null
    }
    val last4 = paidMethod?.last4?.ifEmpty { null }
    val paidText = when {
        last4 != null -> "•••• $last4"
        mark == Mark.Upi || mark == Mark.Pix -> ""
        else -> paidWith ?: ""
    }
    val rows = if (sub != null) {
        listOf(
            Triple("Plan", plan?.let { "${it.planName} · ${planPriceText(it)}" } ?: "", null),
            Triple(if (trialing) "First charge" else "Next charge", next, null),
            Triple(if (trialing) "Card" else "Paid with", paidText, mark),
            Triple("Reference", reference, null),
        )
    } else {
        listOf(
            Triple("Paid to", merchant, null),
            Triple("Paid with", paidText, mark),
            Triple("Reference", reference, null),
        )
    }.filter { (_, v, m) -> v.isNotEmpty() || m != null }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ResultMark(copy.kind)
        Txt(
            copy.title,
            22f,
            p.ink,
            weight = FontWeight.Bold,
            align = TextAlign.Center,
            letterSpacing = -0.3f,
            modifier = Modifier
                .padding(top = 14.dp)
                .testTag("vezmo-result-title")
                .semantics { heading() },
        )
        if (result is CheckoutResult.Succeeded && amount.isNotEmpty() && !trialing) {
            Txt(amount, 34f, p.ink, weight = FontWeight.Bold, lineHeight = 40f, letterSpacing = -0.9f, tabular = true)
        }
        if (copy.body.isNotEmpty()) Txt(copy.body, 15f, p.ink2, lineHeight = 21f, align = TextAlign.Center)
        if (paymentId.isNotEmpty() || sub != null) {
            val hair = hairline()
            Column(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(look.radius + 2.dp))
                    .background(p.surface2)
                    .padding(horizontal = 16.dp),
            ) {
                rows.forEachIndexed { i, (key, value, logo) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i > 0) Modifier.topLine(p.line, hair) else Modifier)
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Txt(key, 14f, p.ink3)
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (logo != null) BrandTile(logo)
                            if (value.isNotEmpty()) {
                                Txt(value, 14f, p.ink, weight = FontWeight.Medium, align = TextAlign.End, tabular = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(top = 24.dp)) {
            PrimaryButton(copy.action, onClick = { onDone(result) }, ink = true, tag = "vezmo-done")
        }
    }
}
