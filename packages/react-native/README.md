# @vezmopay/react-native

The Vezmo payment sheet for React Native and Expo apps. Cards with 3D Secure, Apple Pay, Google Pay, Klarna, Affirm,
Afterpay, Amazon Pay, Cash App, UPI and Pix, in the buyer's own currency, drawn natively on iPhone and Android.

Your server keeps your Vezmo API key. The app only ever receives a session's client token.

Full guide: [Vezmo docs → Mobile → React Native SDK](https://user.vezmo.com/docs/v1/mobile/react-native)

## Requirements

- React Native 0.79 or later with the **New Architecture**. Expo SDK 52 or later with a development build (not Expo Go).
- iOS 15.1 or later. Android 7.0 (API 24) or later.
- Your app added in the Vezmo dashboard under **Developers → Mobile apps**, with its return link (for example
  `acme://vezmo`). The buyer comes back through it after 3D Secure, a bank app or a pay-later provider.

## Install

```sh
npx expo install @vezmopay/react-native
# or, in a bare React Native app
npm install @vezmopay/react-native && cd ios && pod install
```

In Expo, add the config plugin. It turns on Apple Pay with your Apple Merchant ID and adds the camera text used to scan
a card:

```json
{
  "expo": {
    "scheme": "acme",
    "plugins": [["@vezmopay/react-native", { "merchantIdentifier": "merchant.com.acme" }]]
  }
}
```

In a bare app, add the Apple Pay capability with your Merchant ID in Xcode and `NSCameraUsageDescription` to
`Info.plist`.

## Set up

Wrap your app (or the screens that take payments) once. The sheet is drawn inside the provider, so put it high in
the tree.

```tsx
import { VezmoProvider } from '@vezmopay/react-native';

export default function App() {
  return (
    <VezmoProvider
      returnUrl="acme://vezmo" // registered under Developers → Mobile apps
      applePay={{ merchantId: 'merchant.com.acme' }} // Apple Pay shows only with your Merchant ID
    >
      <Root />
    </VezmoProvider>
  );
}
```

| Prop | What it does |
| --- | --- |
| `returnUrl` | Your registered return link. Needed for 3D Secure in some banks, bank apps and pay-later methods. |
| `applePay` | `{ merchantId, merchantCountryCode? }`. Without it the sheet shows no Apple Pay. |
| `googlePay` | `{ merchantCountryCode? }`. Google Pay shows on phones that have it; sandbox sessions use Google's test environment. |
| `style` | `'automatic'` (follow the phone, default), `'light'` or `'dark'`. |
| `appearance` | Colors, corner radius and font, below. |

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

In the app:

```tsx
import { useVezmoCheckout } from '@vezmopay/react-native';

function PayButton({ orderId }: { orderId: string }) {
  const { prepareCheckout, presentCheckout } = useVezmoCheckout();

  const pay = async () => {
    const { clientToken } = await myApi.createVezmoSession(orderId);

    // Loads the session; call it as soon as you have the token.
    const prep = await prepareCheckout({ clientToken });
    if (prep.error) return showError(prep.error.message);

    // Opens the sheet and resolves when the buyer is done. Never throws.
    const result = await presentCheckout();
    switch (result.status) {
      case 'succeeded':
        return showPaid(result.paymentId);
      case 'processing':
        return showPaymentReceived(); // a bank debit, UPI or Pix still settling
      case 'canceled':
        return; // the buyer closed the sheet
      case 'failed':
        return showError(result.error.message);
    }
  };

  return <Button title="Pay" onPress={pay} />;
}
```

Fulfil the order from the `payment.success` webhook, not from the result alone. The result tells your app what to
show.

### Results and errors

| `result.status` | Meaning |
| --- | --- |
| `succeeded` | Paid. `paymentId` and `transactionId` match the dashboard and the webhook. A subscription also has `subscription` (`ref`, `status`); `paymentId` is null when a free trial started. |
| `processing` | Paid, money on its way (a bank debit, UPI or Pix still settling). Wait for the webhook. |
| `canceled` | The buyer closed the sheet. Not an error. |
| `failed` | `error.code` is one of the codes below; `error.message` is written for the buyer. |

| `error.code` | What to do |
| --- | --- |
| `declined` | The bank said no. The buyer can try another card or method. |
| `expired` | The session ran out. Create a new one. |
| `already_paid` | The session was already paid. Treat the order as paid. |
| `not_available` | This way to pay can't be used for this session. |
| `network` | No connection. Let the buyer try again. |
| `config` | A setup mistake in the app (for example `presentCheckout()` before `prepareCheckout()`). |
| `unknown` | Anything else. Let the buyer try again. |

## Subscriptions

Start a subscription in the app: the first payment, or a free trial with the card saved for the renewals. Your server
creates the subscription with your API key and sends its `checkoutToken` to the app:

```http
POST /v1/merchant/subscription
{ "planId": "plan_…", "customerEmail": "ana@example.com", "trialDays": 7 }
→ { "subscriptionRef": "SUB-7Q2K9WX", "status": "TRIALING", "checkoutToken": "…" }
```

In the app, pass it as `subscriptionToken`; everything else is the same:

```tsx
const prep = await prepareCheckout({ subscriptionToken: checkoutToken });
if (prep.error) return showError(prep.error.message);
const result = await presentCheckout();
if (result.status === 'succeeded') {
  // result.subscription: { ref: 'SUB-7Q2K9WX', status: 'trialing' | 'active' }
  // result.paymentId is the first payment, or null for a free trial (nothing charged today)
}
```

The sheet shows what is due today, the plan after it ("7-day free trial, then $9.99 / month from 23 Oct 2026"), the
authorization the buyer gives, and **Start free trial** or **Subscribe**. Cards, Apple Pay and Google Pay; on iPhone,
Apple Pay shows the plan as a recurring payment and lists it in the buyer's Wallet with their page to manage it.
Renewals are charged by Vezmo; your server hears about each one from the subscription webhooks.

Apple and Google require their own in-app purchase for digital goods and services used inside the app (subscriptions
to app features or content). Use Vezmo subscriptions for real-world goods and services: deliveries, memberships,
classes, rentals, services.

## What the buyer sees

- Your business, what they're paying for and the total, with a choice of their own currency where Vezmo offers one.
- Apple Pay or Google Pay as one tap at the top, when the phone has it and you've set it up.
- The other ways to pay as one list: **Card** (with the card's own logo once typed), pay-later methods, Cash App, and
  UPI or Pix for buyers in India or Brazil.
- One Pay button, 3D Secure when the bank asks for it, and a receipt: who was paid, with what, and a reference.

## Appearance

The sheet follows Vezmo's checkout design in light and dark. You can bring it closer to your app:

```tsx
<VezmoProvider
  returnUrl="acme://vezmo"
  appearance={{
    colors: { accent: '#0A84FF', surface: { light: '#FFFFFF', dark: '#1C1C1E' } },
    cornerRadius: 12,
    fontFamily: 'Inter', // a font already loaded in your app
  }}
>
```

The main color keys: `accent`, `onAccent`, `surface`, `surface2`, `field`, `fieldLine`, `line`, `ink`, `ink2`, `ink3`, `good`,
`bad`. Each takes one color or `{ light, dark }`. Setting your own `accent` replaces the Vezmo gradient on the Pay
button.

## Apple Pay

1. In your Apple Developer account, create a **Merchant ID** and turn on **Apple Pay Payment Processing** for your
   app's App ID with it.
2. Write to Vezmo support with your Merchant ID. We send you a certificate signing request; create the **Apple Pay
   Payment Processing Certificate** with it and send us the certificate.
3. Pass the Merchant ID to `VezmoProvider` (`applePay.merchantId`) and, in Expo, to the plugin.

## Google Pay

Google Pay shows on Android phones where the buyer has it; sandbox sessions use Google's test environment, with
nothing to set up. Before your app takes live Google Pay payments, ask Google for production access in the **Google
Pay & Wallet Console**: add your app, choose the **gateway** integration when asked how you take payments, and send the
screenshots of your checkout that Google asks for.

## Testing

Create the session with your sandbox API key. Pay with the test card `4242 4242 4242 4242`, any future expiry and any
CVC; 3D Secure shows a test page where you can approve or fail the payment. `4000 0000 0000 0002` is declined. In the
sandbox, Apple Pay and Google Pay use the cards in the phone's wallet without charging them.

The [example app](https://github.com/ACCEPT-GLOBAL-LIMITED/vezmo-mobile-sdk/tree/main/packages/react-native/example)
pays against Vezmo's sandbox with no setup.

### In your Jest tests

The package ships a mock with no native views and no network. `presentCheckout()` resolves with a successful payment
unless you pick another result:

```ts
jest.mock('@vezmopay/react-native', () => require('@vezmopay/react-native/jest/mock'));

import * as vezmo from '@vezmopay/react-native';

beforeEach(() => (vezmo as any).mockReset());

it('shows the canceled state', async () => {
  (vezmo as any).mockNextResult({ status: 'canceled' });
  // render your screen and press Pay…
});
```

`mockPrepareError({ code, message })` makes `prepareCheckout()` fail. Both hook functions are `jest.fn()`s.

## Troubleshooting

- **"VezmoPaymentElementView was not found in the UIManager"**: the app runs the old architecture, or is Expo Go. Turn
  on the New Architecture and use a development build.
- **No Apple Pay**: no `applePay.merchantId` on the provider, no card in the phone's Wallet, or the Apple Pay
  certificate isn't set up yet.
- **The buyer doesn't come back to the app after 3D Secure or a bank app**: the session's `app.returnUrl` must be a
  link registered under Developers → Mobile apps, and your app must open that scheme.

## License

MIT
