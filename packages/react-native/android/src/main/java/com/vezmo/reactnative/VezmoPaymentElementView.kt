package com.vezmo.reactnative

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Base64
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentelement.CreateIntentWithConfirmationTokenCallback
import com.stripe.android.paymentelement.EmbeddedPaymentElement
import com.stripe.android.paymentelement.rememberEmbeddedPaymentElement
import com.stripe.android.paymentsheet.CreateIntentResult
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.model.PaymentOption
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import org.json.JSONObject

/**
 * The Vezmo payment element on Android: the processor's native embedded element in Compose (wallets, pay-later and
 * other methods) and, for cards, its native card form behind a flow controller (the sheet draws the Card row itself,
 * with the card's own logo), configured from JSON the JS layer builds and confirmed through Vezmo's server.
 *
 * Payment flow: the buyer picks a method and taps Pay → [confirm] → the element creates a confirmation token and
 * calls our callback → we hand its id to JS (`onConfirmationToken`) → JS sends it to Vezmo's API, which prices and
 * confirms the payment → JS answers with the intent's client secret ([resolveConfirmation]) → the element finishes
 * any step left (3D Secure, Google Pay, a provider) and reports `onConfirmResult`.
 *
 * Hosted in the app's own window (the Vezmo sheet is an overlay, not a separate dialog), so Compose finds the
 * activity's lifecycle and saved state through the view tree.
 */
@SuppressLint("ViewConstructor")
class VezmoPaymentElementView(
  private val reactContext: ThemedReactContext,
) : FrameLayout(reactContext) {
  /** What the element and the card form are set up with. [card] is null when the session takes no cards. */
  private data class Setup(
    val configuration: EmbeddedPaymentElement.Configuration,
    val intent: PaymentSheet.IntentConfiguration,
    /** The element has rows to draw (other methods or Google Pay); without any it is left unconfigured. */
    val rows: Boolean,
    val card: Pair<PaymentSheet.IntentConfiguration, PaymentSheet.Configuration>?,
  )

  private sealed interface Command {
    data class Configure(
      val setup: Setup,
    ) : Command

    data class Update(
      val setup: Setup,
    ) : Command

    data object Confirm : Command

    data object Clear : Command

    data object PresentCard : Command

    data object ConfirmCard : Command
  }

  private val commands = Channel<Command>(Channel.UNLIMITED)
  private var configJson = ""
  private var intentJson = ""
  private var configDirty = false
  private var intentDirty = false
  private var elementConfiguration: EmbeddedPaymentElement.Configuration? = null
  private var sheetConfiguration: PaymentSheet.Configuration? = null
  private var pendingSecret: CompletableDeferred<CreateIntentResult>? = null
  private var composeView: ComposeView? = null
  private var lastHeightDp = -1f

  fun setConfigJson(value: String?) {
    if (value != null && value != configJson) {
      configJson = value
      configDirty = true
    }
  }

  fun setIntentJson(value: String?) {
    if (value != null && value != intentJson) {
      intentJson = value
      intentDirty = true
    }
  }

  /** After a batch of prop changes: rebuild for new keys or settings, update in place for a new amount. */
  fun commit() {
    if (!configDirty && !intentDirty) return
    val config = parse(configJson) ?: return
    val intent = parse(intentJson) ?: return
    val publishableKey = config.optString("publishableKey")
    if (publishableKey.isEmpty()) {
      emit("topLoadFailed", Arguments.createMap().apply { putString("message", "Missing checkout keys.") })
      return
    }
    val types = intent.optJSONArray("paymentMethodTypes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val rebuild = configDirty || elementConfiguration == null
    if (rebuild) {
      PaymentConfiguration.init(
        reactContext.applicationContext,
        publishableKey,
        config.optString("stripeAccount").ifEmpty { null },
      )
      elementConfiguration = configuration(config)
      sheetConfiguration = sheetConfiguration(config)
    }
    val setup =
      Setup(
        configuration = elementConfiguration!!,
        intent = intentConfiguration(intent, types),
        rows = types.isNotEmpty() || config.optJSONObject("googlePay") != null,
        card = if (intent.optBoolean("card")) intentConfiguration(intent, listOf("card")) to sheetConfiguration!! else null,
      )
    commands.trySend(if (rebuild) Command.Configure(setup) else Command.Update(setup))
    configDirty = false
    intentDirty = false
  }

  fun confirm() {
    commands.trySend(Command.Confirm)
  }

  fun presentCard() {
    commands.trySend(Command.PresentCard)
  }

  fun confirmCard() {
    commands.trySend(Command.ConfirmCard)
  }

  fun clearSelection() {
    commands.trySend(Command.Clear)
  }

  fun resolveConfirmation(
    clientSecret: String,
    errorMessage: String,
  ) {
    val deferred = pendingSecret ?: return
    pendingSecret = null
    deferred.complete(
      if (clientSecret.isNotEmpty()) {
        CreateIntentResult.Success(clientSecret = clientSecret)
      } else {
        CreateIntentResult.Failure(
          cause = Exception(errorMessage),
          displayMessage = errorMessage.ifEmpty { DEFAULT_ERROR },
        )
      },
    )
  }

  // React Native lays out its own views only: a child added here (the Compose host) is never measured unless asked.
  // Measure and lay it out whenever it requests a layout, as native views inside React Native must.
  private val measureAndLayout =
    Runnable {
      measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
      )
      layout(left, top, right, bottom)
    }

  override fun requestLayout() {
    super.requestLayout()
    post(measureAndLayout)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (composeView != null) return
    val activity = reactContext.currentActivity as? ComponentActivity
    composeView =
      ComposeView(reactContext).also { view ->
        // The activity's owners, set on the view and provided to the composition: React Native may detach and
        // re-attach this view (a payment step in front of it), and the element must find them every time.
        if (activity != null) {
          view.setViewTreeLifecycleOwner(activity)
          view.setViewTreeViewModelStoreOwner(activity)
          view.setViewTreeSavedStateRegistryOwner(activity)
        }
        // Kept across detach and re-attach (the element's state survives a payment step); released in [dispose].
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { Content(activity) }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
      }
  }

  /** React Native dropped this view (the sheet closed). */
  fun dispose() {
    composeView?.disposeComposition()
    composeView = null
    pendingSecret?.complete(CreateIntentResult.Failure(cause = Exception("closed"), displayMessage = DEFAULT_ERROR))
    pendingSecret = null
  }

  @Composable
  private fun Content(activity: ComponentActivity?) {
    if (activity == null) {
      LaunchedEffect(Unit) {
        emit("topLoadFailed", Arguments.createMap().apply { putString("message", "The payment form needs an activity.") })
      }
      return
    }
    val builder =
      remember {
        EmbeddedPaymentElement.Builder(
          createIntentCallback = { confirmationToken -> awaitClientSecret(confirmationToken.id) },
          resultCallback = { result -> emitResult(result) },
        )
      }
    // Cards: the native card form and confirmation through the same Vezmo server step, without a row of its own.
    val cardBuilder =
      remember {
        PaymentSheet.FlowController
          .Builder(
            resultCallback = { result -> emitSheetResult(result) },
            paymentOptionResultCallback = { result -> emitCardOption(result.paymentOption) },
          ).createIntentCallback(
            CreateIntentWithConfirmationTokenCallback { confirmationToken -> awaitClientSecret(confirmationToken.id) },
          )
      }
    CompositionLocalProvider(
      LocalActivityResultRegistryOwner provides activity,
      LocalViewModelStoreOwner provides activity,
      LocalLifecycleOwner provides activity,
    ) {
      val embedded = rememberEmbeddedPaymentElement(builder)
      val cardFlow = cardBuilder.build()
      val density = LocalDensity.current

      LaunchedEffect(embedded, cardFlow) {
        commands.consumeAsFlow().collect { command ->
          when (command) {
            is Command.Configure -> configure(embedded, cardFlow, command.setup, loaded = true)
            is Command.Update -> configure(embedded, cardFlow, command.setup, loaded = false)
            is Command.Confirm -> embedded.confirm()
            is Command.Clear -> embedded.clearPaymentOption()
            is Command.PresentCard -> cardFlow.presentPaymentOptions()
            is Command.ConfirmCard -> cardFlow.confirm()
          }
        }
      }
      LaunchedEffect(embedded) {
        embedded.paymentOption.collect { option ->
          emit(
            "topPaymentOptionChange",
            Arguments.createMap().apply {
              putString("paymentMethodType", option?.paymentMethodType ?: "")
              putString("label", option?.label ?: "")
            },
          )
        }
      }
      Box(
        Modifier
          .fillMaxWidth()
          .wrapContentHeight(align = Alignment.Top, unbounded = true)
          .onSizeChanged { size ->
            val heightDp = with(density) { size.height.toDp().value }
            if (heightDp != lastHeightDp) {
              lastHeightDp = heightDp
              emit("topHeightChange", Arguments.createMap().apply { putDouble("height", heightDp.toDouble()) })
            }
          },
      ) {
        embedded.Content()
      }
    }
  }

  /** Sets up the element (when it has rows) and the card form (when cards are taken); reports loaded once. */
  private suspend fun configure(
    embedded: EmbeddedPaymentElement,
    cardFlow: PaymentSheet.FlowController,
    setup: Setup,
    loaded: Boolean,
  ) {
    if (setup.rows) {
      val result = embedded.configure(intentConfiguration = setup.intent, configuration = setup.configuration)
      if (result is EmbeddedPaymentElement.ConfigureResult.Failed) return report(result, loaded)
    }
    val card = setup.card
    if (card == null) {
      if (loaded) emit("topLoaded", Arguments.createMap().apply { putBoolean("ok", true) })
      return
    }
    val done = CompletableDeferred<Throwable?>()
    cardFlow.configureWithIntentConfiguration(card.first, card.second) { success, error ->
      done.complete(if (success) null else error ?: Exception(DEFAULT_ERROR))
    }
    val error = done.await()
    if (error != null) {
      emit("topLoadFailed", Arguments.createMap().apply { putString("message", error.localizedMessage ?: DEFAULT_ERROR) })
      return
    }
    emitCardOption(cardFlow.getPaymentOption())
    if (loaded) emit("topLoaded", Arguments.createMap().apply { putBoolean("ok", true) })
  }

  /** The card from the card form, for the sheet's own Card row: its label and the network's logo. */
  private fun emitCardOption(option: PaymentOption?) {
    emit(
      "topCardOptionChange",
      Arguments.createMap().apply {
        putString("label", option?.label ?: "")
        putString("image", option?.let { pngDataUri(it.icon()) } ?: "")
      },
    )
  }

  private fun pngDataUri(drawable: Drawable): String? =
    try {
      val density = resources.displayMetrics.density
      val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else (32 * density).toInt()
      val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else (20 * density).toInt()
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      drawable.setBounds(0, 0, width, height)
      drawable.draw(Canvas(bitmap))
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
      "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) {
      null
    }

  private fun emitSheetResult(result: PaymentSheetResult) {
    val map = Arguments.createMap()
    when (result) {
      is PaymentSheetResult.Completed -> {
        map.putString("status", "completed")
        map.putString("message", "")
      }
      is PaymentSheetResult.Canceled -> {
        map.putString("status", "canceled")
        map.putString("message", "")
      }
      is PaymentSheetResult.Failed -> {
        map.putString("status", "failed")
        map.putString("message", result.error.localizedMessage ?: DEFAULT_ERROR)
      }
    }
    emit("topConfirmResult", map)
  }

  private fun report(
    result: EmbeddedPaymentElement.ConfigureResult,
    loaded: Boolean,
  ) {
    when (result) {
      is EmbeddedPaymentElement.ConfigureResult.Succeeded ->
        if (loaded) emit("topLoaded", Arguments.createMap().apply { putBoolean("ok", true) })
      is EmbeddedPaymentElement.ConfigureResult.Failed ->
        emit(
          "topLoadFailed",
          Arguments.createMap().apply { putString("message", result.error.localizedMessage ?: DEFAULT_ERROR) },
        )
    }
  }

  private suspend fun awaitClientSecret(tokenId: String): CreateIntentResult {
    val deferred = CompletableDeferred<CreateIntentResult>()
    post {
      // A confirm still waiting is superseded by this one.
      pendingSecret?.complete(CreateIntentResult.Failure(cause = Exception("superseded"), displayMessage = DEFAULT_ERROR))
      pendingSecret = deferred
      emit("topConfirmationToken", Arguments.createMap().apply { putString("id", tokenId) })
    }
    return deferred.await()
  }

  private fun emitResult(result: EmbeddedPaymentElement.Result) {
    val map = Arguments.createMap()
    when (result) {
      is EmbeddedPaymentElement.Result.Completed -> {
        map.putString("status", "completed")
        map.putString("message", "")
      }
      is EmbeddedPaymentElement.Result.Canceled -> {
        map.putString("status", "canceled")
        map.putString("message", "")
      }
      is EmbeddedPaymentElement.Result.Failed -> {
        map.putString("status", "failed")
        map.putString("message", result.error.localizedMessage ?: DEFAULT_ERROR)
      }
    }
    emit("topConfirmResult", map)
  }

  private fun emit(
    name: String,
    payload: WritableMap,
  ) {
    val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
    dispatcher.dispatchEvent(VezmoElementEvent(UIManagerHelper.getSurfaceId(reactContext), id, name, payload))
  }

  private fun intentConfiguration(
    intent: JSONObject,
    types: List<String>,
  ): PaymentSheet.IntentConfiguration =
    PaymentSheet.IntentConfiguration(
      mode =
        PaymentSheet.IntentConfiguration.Mode.Payment(
          amount = intent.optLong("amount"),
          currency = intent.optString("currency", "usd").lowercase(),
          // Our server makes every intent with automatic_async capture; the element must say the same.
          captureMethod = PaymentSheet.IntentConfiguration.CaptureMethod.AutomaticAsync,
        ),
      paymentMethodTypes = types,
    )

  @SuppressLint("RestrictedApi")
  private fun configuration(config: JSONObject): EmbeddedPaymentElement.Configuration {
    val builder =
      EmbeddedPaymentElement.Configuration
        .Builder(config.optString("merchantDisplayName"))
        // Our own Pay button confirms; the card form only collects details.
        .formSheetAction(EmbeddedPaymentElement.FormSheetAction.Continue)
        // Vezmo is white label: no processor wallet of its own in the sheet.
        .link(PaymentSheet.LinkConfiguration(display = PaymentSheet.LinkConfiguration.Display.Never))
        .embeddedViewDisplaysMandateText(true)
    config.optJSONObject("googlePay")?.let { gp ->
      builder.googlePay(
        PaymentSheet.GooglePayConfiguration(
          environment =
            if (gp.optBoolean("testEnv")) {
              PaymentSheet.GooglePayConfiguration.Environment.Test
            } else {
              PaymentSheet.GooglePayConfiguration.Environment.Production
            },
          countryCode = gp.optString("merchantCountryCode", "US"),
          currencyCode = gp.optString("currencyCode").ifEmpty { null },
        ),
      )
    }
    config.optJSONObject("billing")?.let { b ->
      builder.defaultBillingDetails(
        PaymentSheet.BillingDetails(
          name = b.optString("name").ifEmpty { null },
          email = b.optString("email").ifEmpty { null },
        ),
      )
    }
    config.optJSONObject("appearance")?.let { builder.appearance(appearance(it)) }
    return builder.build()
  }

  /** The card form's settings: the same business, look and billing details as the element; cards only, no wallets. */
  @SuppressLint("RestrictedApi")
  private fun sheetConfiguration(config: JSONObject): PaymentSheet.Configuration {
    val builder =
      PaymentSheet.Configuration
        .Builder(config.optString("merchantDisplayName"))
        .link(PaymentSheet.LinkConfiguration(display = PaymentSheet.LinkConfiguration.Display.Never))
        .paymentMethodLayout(PaymentSheet.PaymentMethodLayout.Vertical)
    config.optJSONObject("billing")?.let { b ->
      builder.defaultBillingDetails(
        PaymentSheet.BillingDetails(
          name = b.optString("name").ifEmpty { null },
          email = b.optString("email").ifEmpty { null },
        ),
      )
    }
    config.optJSONObject("appearance")?.let { builder.appearance(appearance(it)) }
    return builder.build()
  }

  @SuppressLint("RestrictedApi")
  private fun appearance(params: JSONObject): PaymentSheet.Appearance {
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

  private fun parse(json: String): JSONObject? =
    if (json.isEmpty()) {
      null
    } else {
      try {
        JSONObject(json)
      } catch (_: Exception) {
        null
      }
    }

  companion object {
    private const val DEFAULT_ERROR = "Your payment couldn’t be completed. Please try again."
  }
}
