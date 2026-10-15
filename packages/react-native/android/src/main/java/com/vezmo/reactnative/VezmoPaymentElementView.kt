package com.vezmo.reactnative

import android.annotation.SuppressLint
import android.graphics.Color
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
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentelement.EmbeddedPaymentElement
import com.stripe.android.paymentelement.rememberEmbeddedPaymentElement
import com.stripe.android.paymentsheet.CreateIntentResult
import com.stripe.android.paymentsheet.PaymentSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import org.json.JSONObject

/**
 * The Vezmo payment element on Android: the processor's native embedded element in Compose, configured from JSON
 * the JS layer builds, confirmed through Vezmo's server.
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
  private sealed interface Command {
    data class Configure(
      val configuration: EmbeddedPaymentElement.Configuration,
      val intent: PaymentSheet.IntentConfiguration,
    ) : Command

    data class Update(
      val configuration: EmbeddedPaymentElement.Configuration,
      val intent: PaymentSheet.IntentConfiguration,
    ) : Command

    data object Confirm : Command
  }

  private val commands = Channel<Command>(Channel.UNLIMITED)
  private var configJson = ""
  private var intentJson = ""
  private var configDirty = false
  private var intentDirty = false
  private var elementConfiguration: EmbeddedPaymentElement.Configuration? = null
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
    val intentConfiguration = intentConfiguration(intent)
    if (configDirty || elementConfiguration == null) {
      PaymentConfiguration.init(
        reactContext.applicationContext,
        publishableKey,
        config.optString("stripeAccount").ifEmpty { null },
      )
      val configuration = configuration(config)
      elementConfiguration = configuration
      commands.trySend(Command.Configure(configuration, intentConfiguration))
    } else {
      commands.trySend(Command.Update(elementConfiguration!!, intentConfiguration))
    }
    configDirty = false
    intentDirty = false
  }

  fun confirm() {
    commands.trySend(Command.Confirm)
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

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (composeView != null) return
    val activity = reactContext.currentActivity as? ComponentActivity
    composeView =
      ComposeView(reactContext).also { view ->
        // The view tree normally carries these from the activity; set them when it doesn't (an unusual host).
        if (activity != null) {
          if (findViewTreeLifecycleOwner() == null) view.setViewTreeLifecycleOwner(activity)
          if (findViewTreeViewModelStoreOwner() == null) view.setViewTreeViewModelStoreOwner(activity)
          if (findViewTreeSavedStateRegistryOwner() == null) view.setViewTreeSavedStateRegistryOwner(activity)
        }
        view.setContent { Content(activity) }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
      }
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
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides activity) {
      val embedded = rememberEmbeddedPaymentElement(builder)
      val density = LocalDensity.current

      LaunchedEffect(embedded) {
        commands.consumeAsFlow().collect { command ->
          when (command) {
            is Command.Configure ->
              report(
                embedded.configure(intentConfiguration = command.intent, configuration = command.configuration),
                loaded = true,
              )
            is Command.Update ->
              report(
                embedded.configure(intentConfiguration = command.intent, configuration = command.configuration),
                loaded = false,
              )
            is Command.Confirm -> embedded.confirm()
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

  private fun intentConfiguration(intent: JSONObject): PaymentSheet.IntentConfiguration {
    val types = intent.optJSONArray("paymentMethodTypes")
    return PaymentSheet.IntentConfiguration(
      mode =
        PaymentSheet.IntentConfiguration.Mode.Payment(
          amount = intent.optLong("amount"),
          currency = intent.optString("currency", "usd").lowercase(),
          // Our server makes every intent with automatic_async capture; the element must say the same.
          captureMethod = PaymentSheet.IntentConfiguration.CaptureMethod.AutomaticAsync,
        ),
      paymentMethodTypes = types?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    )
  }

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

    return PaymentSheet.Appearance
      .Builder()
      .colorsLight(palette(PaymentSheet.Colors.Builder.light()))
      .colorsDark(palette(PaymentSheet.Colors.Builder.dark()))
      .shapes(shapes.build())
      .build()
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
