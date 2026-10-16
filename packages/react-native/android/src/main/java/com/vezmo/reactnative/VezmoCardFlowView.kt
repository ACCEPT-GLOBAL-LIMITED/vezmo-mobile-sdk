package com.vezmo.reactnative

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.stripe.android.paymentelement.WalletButtonsPreview
import com.stripe.android.paymentelement.WalletButtonsViewClickHandler
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
 * Cards and wallets on Android: the processor's flow controller (the native card form, Google Pay) in Compose,
 * confirmed through Vezmo's server like the element. It draws only the express wallet buttons; the sheet draws the
 * Card row itself (with the card network's logo) and opens the card form with [presentCard].
 *
 * While the wallet buttons are on screen the flow controller leaves wallets out of its own sheet, so the Card row opens
 * straight onto the card form.
 */
@SuppressLint("ViewConstructor")
@OptIn(WalletButtonsPreview::class)
class VezmoCardFlowView(
  private val reactContext: ThemedReactContext,
) : FrameLayout(reactContext) {
  private sealed interface Command {
    data class Configure(
      val intent: PaymentSheet.IntentConfiguration,
      val configuration: PaymentSheet.Configuration,
      val loaded: Boolean,
    ) : Command

    /** The session takes no cards: nothing to set up. */
    data object Off : Command

    data object PresentCard : Command

    data object ConfirmCard : Command
  }

  private val commands = Channel<Command>(Channel.UNLIMITED)
  private var configJson = ""
  private var intentJson = ""
  private var configDirty = false
  private var intentDirty = false
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

  /** After a batch of prop changes: set the card flow up again (new keys, look or amount). */
  fun commit() {
    if (!configDirty && !intentDirty) return
    val config = parseJson(configJson) ?: return
    val intent = parseJson(intentJson) ?: return
    val rebuild = configDirty || sheetConfiguration == null
    configDirty = false
    intentDirty = false
    if (!intent.optBoolean("card")) {
      commands.trySend(Command.Off)
      return
    }
    val publishableKey = config.optString("publishableKey")
    if (publishableKey.isEmpty()) {
      emit("topLoadFailed", Arguments.createMap().apply { putString("message", "Missing checkout keys.") })
      return
    }
    if (rebuild) {
      PaymentConfiguration.init(
        reactContext.applicationContext,
        publishableKey,
        config.optString("stripeAccount").ifEmpty { null },
      )
      sheetConfiguration = sheetConfiguration(config)
    }
    commands.trySend(Command.Configure(intentConfiguration(intent, listOf("card")), sheetConfiguration!!, loaded = rebuild))
  }

  fun presentCard() {
    commands.trySend(Command.PresentCard)
  }

  fun confirmCard() {
    commands.trySend(Command.ConfirmCard)
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

  // React Native lays out its own views only: measure and lay out the Compose host whenever it asks.
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
        if (activity != null) {
          view.setViewTreeLifecycleOwner(activity)
          view.setViewTreeViewModelStoreOwner(activity)
          view.setViewTreeSavedStateRegistryOwner(activity)
        }
        // Kept across detach and re-attach (the card form in front of it); released in [dispose].
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
        PaymentSheet.FlowController
          .Builder(
            resultCallback = { result -> emitResult(result) },
            paymentOptionResultCallback = { result -> emitCardOption(result.paymentOption) },
          ).createIntentCallback(
            CreateIntentWithConfirmationTokenCallback { confirmationToken -> awaitClientSecret(confirmationToken.id) },
          )
      }
    val clickHandler =
      remember {
        WalletButtonsViewClickHandler { wallet ->
          emit("topWalletTap", Arguments.createMap().apply { putString("wallet", wallet) })
          false // not handled here: the wallet goes on to pay
        }
      }
    CompositionLocalProvider(
      LocalActivityResultRegistryOwner provides activity,
      LocalViewModelStoreOwner provides activity,
      LocalLifecycleOwner provides activity,
    ) {
      val flow = builder.build()
      val density = LocalDensity.current

      LaunchedEffect(flow) {
        commands.consumeAsFlow().collect { command ->
          when (command) {
            is Command.Configure -> configure(flow, command)
            is Command.Off -> emit("topLoaded", Arguments.createMap().apply { putBoolean("ok", true) })
            is Command.PresentCard -> flow.presentPaymentOptions()
            is Command.ConfirmCard -> flow.confirm()
          }
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
        flow.WalletButtons(clickHandler)
      }
    }
  }

  private suspend fun configure(
    flow: PaymentSheet.FlowController,
    command: Command.Configure,
  ) {
    val done = CompletableDeferred<Throwable?>()
    flow.configureWithIntentConfiguration(command.intent, command.configuration) { success, error ->
      done.complete(if (success) null else error ?: Exception(DEFAULT_ERROR))
    }
    val error = done.await()
    if (error != null) {
      emit("topLoadFailed", Arguments.createMap().apply { putString("message", error.localizedMessage ?: DEFAULT_ERROR) })
      return
    }
    emitCardOption(flow.getPaymentOption())
    if (command.loaded) emit("topLoaded", Arguments.createMap().apply { putBoolean("ok", true) })
  }

  /** Cards only, and Google Pay when the session offers it; the same business, look and billing details as the element. */
  @SuppressLint("RestrictedApi")
  private fun sheetConfiguration(config: JSONObject): PaymentSheet.Configuration {
    val builder =
      PaymentSheet.Configuration
        .Builder(config.optString("merchantDisplayName"))
        // Vezmo is white label: no processor wallet of its own.
        .link(PaymentSheet.LinkConfiguration(display = PaymentSheet.LinkConfiguration.Display.Never))
        .paymentMethodLayout(PaymentSheet.PaymentMethodLayout.Vertical)
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

  /** The card from the card form, for the sheet's own Card row: its label and the network's logo. */
  private fun emitCardOption(option: PaymentOption?) {
    val card = option?.takeIf { it.paymentMethodType == "card" }
    emit(
      "topCardOptionChange",
      Arguments.createMap().apply {
        putString("label", card?.label ?: "")
        putString("image", card?.let { pngDataUri(it.icon()) } ?: "")
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

  private fun emitResult(result: PaymentSheetResult) {
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

  private fun emit(
    name: String,
    payload: WritableMap,
  ) {
    val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
    dispatcher.dispatchEvent(VezmoElementEvent(UIManagerHelper.getSurfaceId(reactContext), id, name, payload))
  }
}
