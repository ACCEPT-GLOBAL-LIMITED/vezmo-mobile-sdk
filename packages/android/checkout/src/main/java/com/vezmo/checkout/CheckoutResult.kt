package com.vezmo.checkout

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** The outcome of [VezmoCheckout.present], delivered to the callback you gave [VezmoCheckout]. */
sealed interface CheckoutResult : Parcelable {
    /** Paid. [paymentId] and [transactionId] match the Vezmo dashboard and the webhook. */
    @Parcelize
    data class Succeeded(
        val paymentId: String,
        val transactionId: String?,
    ) : CheckoutResult

    /** Paid, money on its way (a bank debit, UPI or Pix still settling): wait for the webhook. */
    @Parcelize
    data class Processing(
        val paymentId: String,
    ) : CheckoutResult

    /** The buyer closed the sheet. Not an error. */
    @Parcelize
    data object Canceled : CheckoutResult

    @Parcelize
    data class Failed(
        val error: VezmoError,
    ) : CheckoutResult
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
