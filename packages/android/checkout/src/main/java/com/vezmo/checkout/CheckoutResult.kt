package com.vezmo.checkout

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** The outcome of [VezmoCheckout.present], delivered to the callback you gave [VezmoCheckout]. */
sealed interface CheckoutResult : Parcelable {
    /**
     * Paid. [paymentId] and [transactionId] match the Vezmo dashboard and the webhook. [paymentId] is null only when a
     * subscription's free trial started: nothing was charged today.
     */
    @Parcelize
    data class Succeeded(
        val paymentId: String?,
        val transactionId: String?,
        /** For a subscription checkout: the subscription, now active (first payment made) or trialing. */
        val subscription: SubscriptionResult? = null,
    ) : CheckoutResult

    /** Paid, money on its way (a bank debit, UPI or Pix still settling): wait for the webhook. */
    @Parcelize
    data class Processing(
        val paymentId: String?,
        val subscription: SubscriptionResult? = null,
    ) : CheckoutResult

    /** The buyer closed the sheet. Not an error. */
    @Parcelize
    data object Canceled : CheckoutResult

    @Parcelize
    data class Failed(
        val error: VezmoError,
    ) : CheckoutResult
}

/** The subscription a subscription checkout started. */
@Parcelize
data class SubscriptionResult(
    /** The subscription's reference, as in the dashboard and the webhooks. */
    val ref: String,
    val status: Status,
) : Parcelable {
    enum class Status {
        /** The first payment went through. */
        Active,

        /** A free trial started; the card is saved for the first charge. */
        Trialing,

        /** Still settling: the webhook tells your server when it is active. */
        Incomplete,
    }
}

/** Something went wrong. [message] is safe to show the buyer, in Vezmo's words. */
@Parcelize
data class VezmoError(
    val code: Code,
    val message: String,
) : Parcelable {
    enum class Code {
        /** The bank said no; the buyer can try another card or way to pay. */
        Declined,

        /** The session ran out: create a new one. */
        Expired,

        /** The session was already paid. Treat the order as paid. */
        AlreadyPaid,

        /** This way to pay can't be used for this session. */
        NotAvailable,

        /** No connection. Let the buyer try again. */
        Network,

        /** A setup mistake in the app (for example [VezmoCheckout.present] before [VezmoCheckout.prepare]). */
        Config,

        /** Anything else. Let the buyer try again. */
        Unknown,
    }
}
