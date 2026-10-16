# Vezmo Checkout for Android

The Vezmo payment sheet for native Android apps (Kotlin, Jetpack Compose or Views). Cards with 3D Secure, Google Pay,
Klarna, Affirm, Afterpay, Amazon Pay, Cash App, UPI and Pix, in the buyer's own currency.

Your server keeps your Vezmo API key. The app only ever receives a session's client token.

## Requirements

- Android 7.0 (API 24) or later. Compile with SDK 36, Kotlin 2.1 or later, Java 17.
- Your activity is a `ComponentActivity` (`AppCompatActivity` and `FragmentActivity` are).
- Your app added in the Vezmo dashboard under **Developers → Mobile apps**, with its return link (for example
  `acme://vezmo`). The buyer comes back through it after a bank app or a pay-later provider.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.vezmo:vezmo-checkout:0.1.0")
}
```

The library is on Maven Central; `mavenCentral()` must be in your repositories.

Add your return link to the activity that opens the sheet, so the buyer comes back to it:

```xml
<activity android:name=".CheckoutActivity" android:launchMode="singleTask" android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="acme" android:host="vezmo" />
  </intent-filter>
</activity>
```

## Take a payment

Your server creates the session with your API key (the same API checkout session as on the web) and sends its
`clientToken` to the app:

```http
POST /v1/merchant/secure-payments
{
  "title": "Order #1042",
  "amount": 49.00,
  "currency": "USD",
  "client": { "name": "Ana Silva", "email": "ana@example.com", "country": "US", "postalCode": "10001" },
  "app": { "returnUrl": "acme://vezmo" }
}
```

In an activity, create `VezmoCheckout` in `onCreate` (it registers for the sheet's result, so it must exist before
the activity starts):

```kotlin
class CheckoutActivity : ComponentActivity() {
    private lateinit var vezmo: VezmoCheckout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vezmo = VezmoCheckout(this, VezmoConfiguration(returnUrl = "acme://vezmo")) { result ->
            when (result) {
                is CheckoutResult.Succeeded -> showPaid(result.paymentId)
                is CheckoutResult.Processing -> showPaymentReceived() // a bank debit, UPI or Pix still settling
                CheckoutResult.Canceled -> Unit // the buyer closed the sheet
                is CheckoutResult.Failed -> showError(result.error.message)
            }
        }
    }

    private fun pay(orderId: String) = lifecycleScope.launch {
        val clientToken = myApi.createVezmoSession(orderId)
        // Loads the session; call it as soon as you have the token.
        vezmo.prepare(clientToken)?.let { error -> return@launch showError(error.message) }
        // Opens the sheet. The result arrives in the callback above.
        vezmo.present()
    }
}
```

In Compose:

```kotlin
@Composable
fun PayButton(orderId: String) {
    val scope = rememberCoroutineScope()
    val vezmo = rememberVezmoCheckout(VezmoConfiguration(returnUrl = "acme://vezmo")) { result ->
        // the same results as above
    }
    Button(onClick = {
        scope.launch {
            val error = vezmo.prepare(myApi.createVezmoSession(orderId))
            if (error == null) vezmo.present() else showError(error.message)
        }
    }) { Text("Pay") }
}
```

Neither `prepare()` nor `present()` throws. Fulfil the order from the `payment.success` webhook, not from the result
alone. The result tells your app what to show.

### Results and errors

| Result | Meaning |
| --- | --- |
| `Succeeded` | Paid. `paymentId` and `transactionId` match the dashboard and the webhook. |
| `Processing` | Paid, money on its way (a bank debit, UPI or Pix still settling). Wait for the webhook. |
| `Canceled` | The buyer closed the sheet (or pressed Back). Not an error. |
| `Failed` | `error.code` is one of the codes below; `error.message` is written for the buyer. |

| `VezmoError.Code` | What to do |
| --- | --- |
| `Declined` | The bank said no. The buyer can try another card or method. |
| `Expired` | The session ran out. Create a new one. |
| `AlreadyPaid` | The session was already paid. Treat the order as paid. |
| `NotAvailable` | This way to pay can't be used for this session. |
| `Network` | No connection. Let the buyer try again. |
| `Config` | A setup mistake in the app (for example `present()` before `prepare()`). |
| `Unknown` | Anything else. Let the buyer try again. |

`prepare()` returns the same `VezmoError` when the session can't be paid (expired, already paid, a bad token).

## Configuration

| `VezmoConfiguration` | What it does |
| --- | --- |
| `returnUrl` | Your registered return link. Needed for bank apps and pay-later methods. |
| `googlePay` | `GooglePayOptions(merchantCountryCode = "US")`. Google Pay shows when your business offers it and the phone has it; sandbox sessions use Google's test environment. |
| `style` | `VezmoStyle.Automatic` (follow the phone, default), `Light` or `Dark`. |
| `appearance` | Colors, corner radius and font, below. |

## What the buyer sees

- Your business, what they're paying for and the total, with a choice of their own currency where Vezmo offers one.
- Google Pay as one tap at the top, when the phone has it.
- The other ways to pay as one list: **Card** (with the card's own logo once typed), pay-later methods, Cash App, and
  UPI or Pix for buyers in India or Brazil.
- One Pay button, 3D Secure when the bank asks for it, and a receipt: who was paid, with what, and a reference.

## Appearance

The sheet follows Vezmo's checkout design in light and dark. You can bring it closer to your app:

```kotlin
VezmoConfiguration(
    returnUrl = "acme://vezmo",
    appearance = VezmoAppearance(
        colors = VezmoColors(
            accent = VezmoColor(0xFF0A84FF.toInt()),
            surface = VezmoColor(light = 0xFFFFFFFF.toInt(), dark = 0xFF1C1C1E.toInt()),
        ),
        cornerRadius = 12f,
        fontResId = R.font.inter, // a font or font family in your app
    ),
)
```

The main color keys: `accent`, `onAccent`, `surface`, `surface2`, `field`, `fieldLine`, `line`, `ink`, `ink2`, `ink3`,
`good`, `bad`. Each takes one color or a light and a dark one. Setting your own `accent` replaces the Vezmo gradient on
the Pay button.

## Google Pay

Google Pay needs no code: the library turns on the Google Pay API in your app's manifest, and the button shows when
your business offers Google Pay and the phone has it. In the sandbox it uses Google's test environment and the cards
in the phone's Google Wallet, without charging them.

## Testing

Create the session with your sandbox API key. Pay with the test card `4242 4242 4242 4242`, any future expiry and any
CVC; 3D Secure shows a test page where you can approve or fail the payment. `4000 0000 0000 0002` is declined.

The [example app](https://github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk/tree/main/packages/android/example)
pays against Vezmo's sandbox with no setup.

## Troubleshooting

- **"LifecycleOwner … is attempting to register while current state is RESUMED"**: `VezmoCheckout(activity, …)` was
  created after `onCreate`. Create it in `onCreate`, or use `rememberVezmoCheckout` in Compose.
- **The `Config` error "Call prepare() before present()"**: `present()` needs a successful `prepare()` first, each
  time.
- **No Google Pay**: the business doesn't offer it yet, or the phone has no Google Wallet card.
- **The buyer doesn't come back to the app after a bank app**: the session's `app.returnUrl` must be a link
  registered under Developers → Mobile apps, and your app must open that link (the intent filter above).

## Development

```sh
cd packages/android
gradle :checkout:testDebugUnitTest :checkout:lintRelease   # unit tests and lint (Gradle 8.14, JDK 17)
gradle :example:installRelease                             # the example app on a connected phone
```

CI (`.github/workflows/android.yml`) runs the tests, lint and a local Maven publish, then pays end to end on an
Android emulator with Maestro (`e2e/maestro`), including 3D Secure and dark-mode screenshots.

To release, set `VERSION_NAME` in `gradle.properties` and push a tag `android-v<version>`.
`release-android.yml` signs and uploads to Maven Central (needs the `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` secrets); release the validated upload in the
Central Portal.

## License

MIT
