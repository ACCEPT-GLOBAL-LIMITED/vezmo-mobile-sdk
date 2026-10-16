package com.vezmo.checkout

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** The engine's payment-method types the sheet shows, by our method key, in this order. */
private val ELEMENT_TYPES = linkedMapOf(
    "card" to "card",
    "klarna" to "klarna",
    "affirm" to "affirm",
    "afterpayClearpay" to "afterpay_clearpay",
    "amazonPay" to "amazon_pay",
    "cashapp" to "cashapp",
)

/** Methods the business priced only in US dollars: hidden when the buyer pays in their own currency (as on web). */
private val USD_ONLY = setOf("klarna", "affirm", "afterpayClearpay", "amazonPay", "cashapp", "ach")

internal data class Charge(
    val amountMinor: Long,
    val currency: String,
    /** The sealed local-currency offer when the buyer pays in their own currency. */
    val localOffer: String?,
)

/** What the buyer is charged: the session's price, or the offer in their own currency when they chose it. */
internal fun chargeOf(config: CheckoutConfig, payLocal: Boolean): Charge {
    val offer = config.localOffer
    if (payLocal && offer != null) return Charge(offer.amountMinor, offer.currency.uppercase(), offer.token)
    return Charge(config.amountMinor, config.currency.uppercase(), null)
}

internal data class SheetMethods(
    /** For the engine (cards, pay-later, Cash App), in this order. */
    val elementTypes: List<String>,
    val googlePay: Boolean,
    /** Our own panels: UPI (INR) and Pix (BRL), only when paying in that currency. */
    val upi: Boolean,
    val pix: Boolean,
)

/**
 * The ways to pay the sheet offers, from the business's enabled methods: the same rules as the web checkout. Bank
 * debit (ACH) is not offered in the mobile sheet yet.
 */
internal fun sheetMethods(config: CheckoutConfig, charge: Charge): SheetMethods {
    val on = config.enabledMethods
    val local = charge.localOffer != null
    val offerMethods = config.localOffer?.methods ?: emptyList()
    val elementTypes = ELEMENT_TYPES
        .filterKeys { it == "card" || it in on }
        .filterKeys { !(local && it in USD_ONLY) }
        .values
        .toList()
    return SheetMethods(
        elementTypes = elementTypes,
        googlePay = "googlePay" in on,
        upi = local && charge.currency == "INR" && "upi" in on && "upi" in offerMethods,
        pix = local && charge.currency == "BRL" && "pix" in on && "pix" in offerMethods,
    )
}

/** The merchant's result for a /pay answer that needs nothing more from the buyer. */
internal fun resultFromPay(res: PayResponse): CheckoutResult? =
    when (res.state) {
        "succeeded" -> CheckoutResult.Succeeded(res.paymentId, res.transactionId)
        "processing" -> CheckoutResult.Processing(res.paymentId)
        else -> null // requires_action: the engine finishes it first
    }

/** The merchant's result from the session status (after 3D Secure, a redirect, UPI or Pix). */
internal fun resultFromStatus(s: SessionStatus): CheckoutResult? =
    when (s.state) {
        "succeeded" -> CheckoutResult.Succeeded(s.paymentId, s.transactionId)
        "processing" -> CheckoutResult.Processing(s.paymentId)
        "failed" -> CheckoutResult.Failed(DECLINED)
        "canceled" -> CheckoutResult.Failed(VezmoError(VezmoError.Code.Expired, EXPIRED.message))
        else -> null // still open or waiting on the buyer
    }

internal val DECLINED = VezmoError(VezmoError.Code.Declined, "Your payment didn’t go through. Try another card or way to pay.")
internal val EXPIRED = VezmoError(VezmoError.Code.Expired, "This checkout has expired. Please start again.")

private val INTENT_SECRET = Regex("^(pi_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$")

/** `pi_123_secret_abc` → `pi_123`: the intent the device finished, for /confirm. Null for anything else. */
internal fun intentIdOf(clientSecret: String?): String? = clientSecret?.let { INTENT_SECRET.matchEntire(it)?.groupValues?.get(1) }

/** Amount for display, e.g. 4900 USD → "$49.00", 420000 INR → "₹4,200.00". */
internal fun formatAmount(amountMinor: Long, currency: String, locale: Locale = Locale.getDefault()): String =
    try {
        NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency.uppercase()) }.format(amountMinor / 100.0)
    } catch (_: IllegalArgumentException) {
        String.format(Locale.US, "%.2f %s", amountMinor / 100.0, currency)
    }
