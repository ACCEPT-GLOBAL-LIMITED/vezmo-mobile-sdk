import type { Charge, SheetMethods } from './flow';
import { elementAppearance, type SheetPalette, type VezmoAppearance } from './theme';
import type { CheckoutConfig } from './types';

export interface WalletOptions {
  /** Apple Pay: your Apple Merchant ID (set up under Developers → Mobile apps). */
  applePay?: { merchantId: string; merchantCountryCode?: string };
  /** Google Pay: `testEnv` for sandbox sessions is set automatically. */
  googlePay?: { merchantCountryCode?: string };
}

/**
 * The element's settings as JSON for the native view: keys and account from the session, wallets only when the
 * business offers them, our palette, the app's return link. A change rebuilds the native element.
 */
export function elementConfigJson(args: {
  config: CheckoutConfig;
  methods: SheetMethods;
  merchantDisplayName: string;
  returnUrl: string | null;
  scheme: 'light' | 'dark';
  palette: SheetPalette;
  appearance?: VezmoAppearance;
  wallets?: WalletOptions;
  charge: Charge;
  /** Bumped to rebuild the element (Try again after a failed load). */
  attempt?: number;
}): string {
  const { config, methods, wallets } = args;
  return JSON.stringify({
    publishableKey: config.publishableKey,
    stripeAccount: config.connectedAccountId,
    merchantDisplayName: args.merchantDisplayName,
    returnURL: args.returnUrl,
    style: args.scheme,
    applePay:
      methods.applePay && wallets?.applePay?.merchantId
        ? {
            merchantId: wallets.applePay.merchantId,
            merchantCountryCode: wallets.applePay.merchantCountryCode ?? 'US',
          }
        : null,
    googlePay: methods.googlePay
      ? {
          merchantCountryCode: wallets?.googlePay?.merchantCountryCode ?? 'US',
          currencyCode: args.charge.currency,
          testEnv: !config.livemode,
        }
      : null,
    billing:
      config.customerName || config.customerEmail
        ? { name: config.customerName ?? '', email: config.customerEmail ?? '' }
        : null,
    appearance: elementAppearance(args.palette, args.appearance),
    attempt: args.attempt ?? 0,
  });
}

/** What is charged, for the element: a change updates it in place (the buyer switched currency). */
export function elementIntentJson(charge: Charge, methods: SheetMethods): string {
  return JSON.stringify({
    amount: charge.amountMinor,
    currency: charge.currency.toLowerCase(),
    paymentMethodTypes: methods.elementTypes,
  });
}
