package com.vezmo.reactnative

import android.annotation.SuppressLint
import android.graphics.Color
import com.stripe.android.paymentsheet.PaymentSheet
import org.json.JSONObject

/** The shared pieces of the element and the card flow: their JSON, what is charged, and the look. */
internal const val DEFAULT_ERROR = "Your payment couldn’t be completed. Please try again."

internal fun parseJson(json: String): JSONObject? =
  if (json.isEmpty()) {
    null
  } else {
    try {
      JSONObject(json)
    } catch (_: Exception) {
      null
    }
  }

/** What is charged, for the element or the card flow, with the method types each one shows. */
internal fun intentConfiguration(
  intent: JSONObject,
  types: List<String>,
): PaymentSheet.IntentConfiguration {
  val currency = intent.optString("currency", "usd").lowercase()
  // A subscription saves the card for its renewals: a free trial only sets it up; a first payment keeps it.
  val saveForLater =
    if (intent.optString("setupFutureUsage") == "off_session") {
      PaymentSheet.IntentConfiguration.SetupFutureUse.OffSession
    } else {
      null
    }
  return PaymentSheet.IntentConfiguration(
    mode =
      if (intent.optString("mode") == "setup") {
        PaymentSheet.IntentConfiguration.Mode.Setup(
          currency = currency,
          setupFutureUse = PaymentSheet.IntentConfiguration.SetupFutureUse.OffSession,
        )
      } else {
        PaymentSheet.IntentConfiguration.Mode.Payment(
          amount = intent.optLong("amount"),
          currency = currency,
          setupFutureUse = saveForLater,
          // Our server makes every intent with automatic_async capture; the element must say the same.
          captureMethod = PaymentSheet.IntentConfiguration.CaptureMethod.AutomaticAsync,
        )
      },
    paymentMethodTypes = types,
  )
}

@SuppressLint("RestrictedApi")
internal fun appearance(params: JSONObject): PaymentSheet.Appearance {
  val colors = params.optJSONObject("colors") ?: JSONObject()
  fun color(key: String): Int? =
    colors.optString(key).takeIf { it.isNotEmpty() }?.let {
      try {
        Color.parseColor(it)
      } catch (_: IllegalArgumentException) {
        null
      }
    }

  // One palette, already chosen for light or dark by the JS layer: used for both.
  fun palette(base: PaymentSheet.Colors.Builder): PaymentSheet.Colors =
    base
      .apply {
        color("primary")?.let { primary(it) }
        color("background")?.let { surface(it) }
        color("componentBackground")?.let { component(it) }
        color("componentBorder")?.let { componentBorder(it) }
        color("componentDivider")?.let { componentDivider(it) }
        color("componentText")?.let { onComponent(it) }
        color("text")?.let { onSurface(it) }
        color("textSecondary")?.let { subtitle(it) }
        color("placeholderText")?.let { placeholderText(it) }
        color("icon")?.let { appBarIcon(it) }
        color("danger")?.let { error(it) }
      }.build()

  val shapes = PaymentSheet.Shapes.Builder()
  if (params.has("cornerRadius")) shapes.cornerRadiusDp(params.optDouble("cornerRadius").toFloat())
  if (params.has("borderWidth")) shapes.borderStrokeWidthDp(params.optDouble("borderWidth").toFloat())

  val builder =
    PaymentSheet.Appearance
      .Builder()
      .colorsLight(palette(PaymentSheet.Colors.Builder.light()))
      .colorsDark(palette(PaymentSheet.Colors.Builder.dark()))
      .shapes(shapes.build())
  // Methods as a flat list with a checkmark, inside the sheet's own rounded group (which draws the outer edges).
  val row = params.optJSONObject("row")
  if (row?.optString("style") == "checkmark") {
    val separator = row.optString("separatorColor").takeIf { it.isNotEmpty() }?.let {
      try {
        Color.parseColor(it)
      } catch (_: IllegalArgumentException) {
        null
      }
    }
    fun rowColors(base: PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder) =
      base
        .apply {
          color("primary")?.let { checkmarkColor(it) }
          separator?.let { separatorColor(it) }
        }.build()
    builder.embeddedAppearance(
      PaymentSheet.Appearance.Embedded(
        PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark
          .Builder()
          .topSeparatorEnabled(false)
          .bottomSeparatorEnabled(false)
          .horizontalInsetsDp(0f)
          .additionalVerticalInsetsDp(row.optDouble("insets", 6.0).toFloat())
          .colorsLight(rowColors(PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder.light()))
          .colorsDark(rowColors(PaymentSheet.Appearance.Embedded.RowStyle.FlatWithCheckmark.Colors.Builder.dark()))
          .build(),
      ),
    )
  }
  return builder.build()
}
