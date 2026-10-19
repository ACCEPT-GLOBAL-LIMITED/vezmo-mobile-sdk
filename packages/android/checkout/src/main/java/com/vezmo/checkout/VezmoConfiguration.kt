package com.vezmo.checkout

import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.annotation.FontRes
import kotlinx.parcelize.Parcelize

/** How the Vezmo payment sheet is set up in your app. */
@Parcelize
data class VezmoConfiguration(
    /** Not used on Android; kept so the settings read the same on every platform. */
    val returnUrl: String? = null,
    /**
     * Google Pay shows when the business offers it and the phone has it; sandbox sessions use Google's test
     * environment automatically. Its merchant country comes from the session; set this only to override it.
     */
    val googlePay: GooglePayOptions? = null,
    /** Light, dark, or follow the phone (default). */
    val style: VezmoStyle = VezmoStyle.Automatic,
    /** Colors, corner radius and font. */
    val appearance: VezmoAppearance = VezmoAppearance(),
    /** Vezmo's API. Leave as is; for Vezmo's own testing only. */
    val apiBase: String? = null,
    /**
     * For Vezmo's own wrappers (the Flutter plugin), not for apps: the wrapper's `<name>/<version>` (for example
     * `flutter/0.1.0`), sent in place of `android/<version>` so a payment records which SDK took it.
     */
    val sdk: String? = null,
) : Parcelable

@Parcelize
data class GooglePayOptions(
    /**
     * The country of the account that processes the payment, as two letters. Leave it null: Vezmo's session says
     * (your business's own country on Connect, Vezmo's on merchant of record).
     */
    val merchantCountryCode: String? = null,
) : Parcelable

enum class VezmoStyle { Automatic, Light, Dark }

/**
 * Bring the sheet closer to your app. Every color can differ between light and dark; setting your own [VezmoColors.accent]
 * replaces the Vezmo gradient on the Pay button.
 */
@Parcelize
data class VezmoAppearance(
    val colors: VezmoColors = VezmoColors(),
    /** Corner radius of fields and payment method rows, in dp. Default 14. */
    val cornerRadius: Float? = null,
    /** A font in your app (`R.font.…`, one font or a font family), used for the sheet's and the card form's text. */
    @FontRes val fontResId: Int? = null,
) : Parcelable

/** One color, or one for light and one for dark. */
@Parcelize
data class VezmoColor(
    @ColorInt val light: Int,
    @ColorInt val dark: Int = light,
) : Parcelable

/** The sheet's colors you may change. Leave one null to keep Vezmo's. */
@Parcelize
data class VezmoColors(
    /** The Pay button, selection marks and links. */
    val accent: VezmoColor? = null,
    /** Text on the accent. */
    val onAccent: VezmoColor? = null,
    /** The sheet. */
    val surface: VezmoColor? = null,
    /** Quiet fills: the currency choice track, the code box, the receipt. */
    val surface2: VezmoColor? = null,
    /** The chosen side of the currency choice. */
    val raised: VezmoColor? = null,
    /** Behind the close button. */
    val fill: VezmoColor? = null,
    /** The Pay button before a way to pay is chosen, and its text. */
    val disabled: VezmoColor? = null,
    val onDisabled: VezmoColor? = null,
    /** Fields and the list of ways to pay. */
    val field: VezmoColor? = null,
    val fieldLine: VezmoColor? = null,
    /** Separators. */
    val line: VezmoColor? = null,
    /** Text, from strongest to quietest. */
    val ink: VezmoColor? = null,
    val ink2: VezmoColor? = null,
    val ink3: VezmoColor? = null,
    /** The accent at low strength: the waiting status, step numbers. */
    val accentSoft: VezmoColor? = null,
    val good: VezmoColor? = null,
    val bad: VezmoColor? = null,
    val badSoft: VezmoColor? = null,
    /** The test-mode badge. */
    val warn: VezmoColor? = null,
    /** Time running out on a UPI or Pix code. */
    val warnInk: VezmoColor? = null,
    val warnSoft: VezmoColor? = null,
    /** Behind the business's initial. */
    val logoTile: VezmoColor? = null,
    /** The dimmed backdrop behind the sheet. */
    val scrim: VezmoColor? = null,
) : Parcelable
