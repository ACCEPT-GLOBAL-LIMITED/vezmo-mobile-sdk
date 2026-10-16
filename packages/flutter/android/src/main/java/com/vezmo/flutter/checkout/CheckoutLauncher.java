package com.vezmo.flutter.checkout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import com.vezmo.checkout.CheckoutContract;
import com.vezmo.checkout.CheckoutRequest;
import com.vezmo.checkout.CheckoutResult;
import com.vezmo.checkout.VezmoCheckout;
import com.vezmo.checkout.VezmoConfiguration;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * The sheet's result launcher for one activity, and a {@link VezmoCheckout} on it for each configuration Dart sends.
 *
 * <p>ponytail: the SDK's public {@code VezmoCheckout(activity, configuration, onResult)} fixes the configuration when
 * the activity starts, before any Dart has run. This uses the SDK's launcher-taking constructor instead (Kotlin
 * {@code internal}, which is public to Java), so the configuration from Dart applies at any time. Swap it for a
 * public Android SDK API when there is one.
 */
final class CheckoutLauncher {
    private final ActivityResultLauncher<CheckoutRequest> launcher;

    CheckoutLauncher(ActivityResultRegistry registry, Function1<CheckoutResult, Unit> onResult) {
        // A fixed key: after the activity is recreated, the sheet's result still finds its way back.
        launcher = registry.register("com.vezmo.flutter.checkout", new CheckoutContract(), onResult::invoke);
    }

    VezmoCheckout checkout(VezmoConfiguration configuration, Function1<CheckoutResult, Unit> onResult) {
        return new VezmoCheckout(configuration, launcher, onResult);
    }

    void unregister() {
        launcher.unregister();
    }
}
