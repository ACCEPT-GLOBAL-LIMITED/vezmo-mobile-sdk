package com.vezmo.flutter.checkout

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.ComponentActivity
import com.vezmo.checkout.CheckoutResult
import com.vezmo.checkout.GooglePayOptions
import com.vezmo.checkout.SubscriptionResult
import com.vezmo.checkout.VezmoAppearance
import com.vezmo.checkout.VezmoCheckout
import com.vezmo.checkout.VezmoColor
import com.vezmo.checkout.VezmoColors
import com.vezmo.checkout.VezmoConfiguration
import com.vezmo.checkout.VezmoError
import com.vezmo.checkout.VezmoStyle
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dart's `VezmoCheckout` on Android: one native [VezmoCheckout] for the Flutter activity, opening the Vezmo sheet
 * (its own activity) and sending the result back over the `vezmo_checkout` channel.
 */
class VezmoCheckoutPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private val scope = MainScope()
    private var context: Context? = null
    private var checkout: VezmoCheckout? = null

    /** Why there is no [checkout] on this activity, in words for the app's developer. */
    private var noCheckout = NO_ACTIVITY
    private var pending: MethodChannel.Result? = null

    /** The last prepare that succeeded and isn't presented yet: prepared again when the activity is recreated. */
    private var prepared: Prepare? = null

    /** That prepare, made again on a new activity: present waits for it. */
    private var restoring: Deferred<VezmoError?>? = null

    private class Prepare(
        val configuration: VezmoConfiguration,
        val clientToken: String?,
        val subscriptionToken: String?,
        val merchantDisplayName: String?,
    )

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "vezmo_checkout")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    // VezmoCheckout registers for the sheet's result, so it is made as soon as the activity attaches (before the
    // activity starts). It needs a ComponentActivity: the app's MainActivity extends FlutterFragmentActivity. The
    // settings come from Dart with each prepare.
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val activity = binding.activity
        context = activity
        checkout = null
        if (activity !is ComponentActivity) {
            noCheckout = NOT_FRAGMENT_ACTIVITY
            return
        }
        val vezmo = try {
            VezmoCheckout(activity, VezmoConfiguration(), ::finish)
        } catch (_: IllegalStateException) {
            // The engine was attached to an activity that had already started (an engine added to a running screen).
            noCheckout = ATTACHED_LATE
            return
        }
        checkout = vezmo
        // A new activity (after a configuration change) has a new checkout, which knows nothing of the session
        // prepared on the one before: it is prepared again here, and present() waits for it.
        val again = prepared ?: return
        restoring?.cancel()
        restoring = scope.async { prepare(vezmo, again) }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onDetachedFromActivity() {
        checkout = null
        context = null
        noCheckout = NO_ACTIVITY
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "prepare" -> prepare(call, result)
            "present" -> present(result)
            else -> result.notImplemented()
        }
    }

    private fun prepare(call: MethodCall, result: MethodChannel.Result) {
        // A new prepare replaces the one before, and any prepare of it on a recreated activity.
        prepared = null
        restoring?.cancel()
        restoring = null
        val vezmo = checkout ?: return result.success(VezmoError(VezmoError.Code.Config, noCheckout).toMap())
        val request = Prepare(
            configuration = configuration(call.argument<Map<String, Any?>>("configuration").orEmpty()),
            clientToken = call.argument("clientToken"),
            subscriptionToken = call.argument("subscriptionToken"),
            merchantDisplayName = call.argument("merchantDisplayName"),
        )
        scope.launch {
            val error = prepare(vezmo, request)
            if (error == null) {
                prepared = request
                // The activity was recreated while this ran: prepared again on the new one.
                val now = checkout
                if (now != null && now !== vezmo) restoring = scope.async { prepare(now, request) }
            }
            result.success(error?.toMap())
        }
    }

    private suspend fun prepare(vezmo: VezmoCheckout, request: Prepare): VezmoError? {
        vezmo.configuration = request.configuration
        return vezmo.prepare(request.clientToken, request.subscriptionToken, request.merchantDisplayName)
    }

    private fun present(result: MethodChannel.Result) {
        if (pending != null) return result.success(failed("The checkout is already open."))
        val vezmo = checkout ?: return result.success(failed(noCheckout))
        pending = result
        prepared = null
        // The result comes back in finish(), from the sheet (or at once, when nothing was prepared).
        val again = restoring ?: return vezmo.present()
        restoring = null
        // The activity was recreated after prepare(): the session is prepared on the new one first. If that fails
        // (the session expired meanwhile, no connection), the app gets why, as prepare() would have told it.
        scope.launch {
            val error = again.await()
            when {
                error != null -> finish(CheckoutResult.Failed(error))
                // Recreated once more meanwhile: this checkout's activity is gone.
                checkout !== vezmo -> finish(CheckoutResult.Failed(VezmoError(VezmoError.Code.Config, PREPARE_AGAIN)))
                else -> vezmo.present()
            }
        }
    }

    private fun finish(result: CheckoutResult) {
        pending?.success(result.toMap())
        pending = null
    }

    /** The configuration Dart sends (`VezmoConfiguration._toMap`). */
    private fun configuration(map: Map<String, Any?>): VezmoConfiguration {
        val appearance = map["appearance"] as? Map<*, *>
        val colors = appearance?.get("colors") as? Map<*, *> ?: emptyMap<String, Any>()
        fun color(key: String) = (colors[key] as? Map<*, *>)?.let {
            VezmoColor((it["light"] as Number).toInt(), (it["dark"] as Number).toInt())
        }
        return VezmoConfiguration(
            returnUrl = map["returnUrl"] as? String,
            googlePay = (map["googlePay"] as? Map<*, *>)?.let { GooglePayOptions(it["merchantCountryCode"] as? String) },
            style = when (map["style"]) {
                "light" -> VezmoStyle.Light
                "dark" -> VezmoStyle.Dark
                else -> VezmoStyle.Automatic
            },
            appearance = VezmoAppearance(
                colors = VezmoColors(
                    accent = color("accent"),
                    onAccent = color("onAccent"),
                    surface = color("surface"),
                    surface2 = color("surface2"),
                    raised = color("raised"),
                    fill = color("fill"),
                    disabled = color("disabled"),
                    onDisabled = color("onDisabled"),
                    field = color("field"),
                    fieldLine = color("fieldLine"),
                    line = color("line"),
                    ink = color("ink"),
                    ink2 = color("ink2"),
                    ink3 = color("ink3"),
                    accentSoft = color("accentSoft"),
                    good = color("good"),
                    bad = color("bad"),
                    badSoft = color("badSoft"),
                    warn = color("warn"),
                    warnInk = color("warnInk"),
                    warnSoft = color("warnSoft"),
                    logoTile = color("logoTile"),
                    scrim = color("scrim"),
                ),
                cornerRadius = (appearance?.get("cornerRadius") as? Number)?.toFloat(),
                fontResId = (appearance?.get("fontFamily") as? String)?.let(::fontRes),
            ),
            apiBase = map["apiBase"] as? String,
            sdk = map["sdk"] as? String,
        )
    }

    /** A font resource of the app by name (`res/font/inter.ttf` → "inter"); null when there is none. */
    @SuppressLint("DiscouragedApi")
    private fun fontRes(name: String): Int? {
        val context = context ?: return null
        return context.resources.getIdentifier(name, "font", context.packageName).takeIf { it != 0 }
    }

    internal companion object {
        const val NOT_FRAGMENT_ACTIVITY =
            "Vezmo checkout needs a FlutterFragmentActivity: make your MainActivity extend " +
                "io.flutter.embedding.android.FlutterFragmentActivity."

        /** The engine was attached to an activity already started: too late to register for the sheet's result. */
        const val ATTACHED_LATE =
            "Vezmo checkout must be set up while your activity is created: the Flutter engine was attached to it " +
                "after it started. Attach the engine in the activity's onCreate (as FlutterFragmentActivity does)."

        const val PREPARE_AGAIN = "The screen was recreated before the checkout opened. Call prepare() again."

        const val NO_ACTIVITY =
            "Vezmo checkout needs your app's activity: call it from a screen of your app, while it is showing."

        fun failed(message: String) = CheckoutResult.Failed(VezmoError(VezmoError.Code.Config, message)).toMap()

        /** The same shape as Dart's `CheckoutResult.fromJson` (and the React Native package's results). */
        fun CheckoutResult.toMap(): Map<String, Any?> = when (this) {
            is CheckoutResult.Succeeded -> mapOf(
                "status" to "succeeded",
                "paymentId" to paymentId,
                "transactionId" to transactionId,
                "subscription" to subscription?.toMap(),
            )
            is CheckoutResult.Processing -> mapOf(
                "status" to "processing",
                "paymentId" to paymentId,
                "subscription" to subscription?.toMap(),
            )
            CheckoutResult.Canceled -> mapOf("status" to "canceled")
            is CheckoutResult.Failed -> mapOf("status" to "failed", "error" to error.toMap())
        }

        fun SubscriptionResult.toMap() = mapOf("ref" to ref, "status" to status.name.lowercase())

        // AlreadyPaid → "already_paid", as Dart's VezmoErrorCode.wireName.
        fun VezmoError.toMap() = mapOf(
            "code" to code.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
            "message" to message,
        )
    }
}
