import { merchantCountry, recurringMinorIn, type Charge, type SheetMethods } from './flow';
import { elementAppearance, type SheetPalette, type VezmoAppearance } from './theme';
import type { CheckoutConfig } from './types';

export interface WalletOptions {
  /**
   * Apple Pay: your Apple Merchant ID (created in Apple Developer → Identifiers → Merchant IDs). Leave out
   * `merchantCountryCode`: the session says which country processes the payment (your business's own on Connect,
   * Vezmo's on merchant of record).
   */
  applePay?: { merchantId: string; merchantCountryCode?: string };
  /** Google Pay: the merchant country comes from the session too; `testEnv` for sandbox sessions is set automatically. */
  googlePay?: { merchantCountryCode?: string };
}

/**
 * Settings as JSON for the native views: keys and account from the session, wallets (for the card flow) only when the
 * business offers them, our palette, the app's return link. A change rebuilds the native view.
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
  /** Apple Pay and Google Pay: the card flow's (its express buttons), never the element's. */
  withWallets?: boolean;
}): string {
  const { config, methods, wallets } = args;
  return JSON.stringify({
    publishableKey: config.publishableKey,
    stripeAccount: config.connectedAccountId,
    merchantDisplayName: args.merchantDisplayName,
    returnURL: args.returnUrl,
    style: args.scheme,
    applePay:
      args.withWallets && methods.applePay && wallets?.applePay?.merchantId
        ? {
            merchantId: wallets.applePay.merchantId,
            merchantCountryCode: merchantCountry(wallets.applePay.merchantCountryCode, config.processingCountry),
            recurring: applePayRecurring(config, args.charge, args.merchantDisplayName),
          }
        : null,
    googlePay:
      args.withWallets && methods.googlePay
      ? {
          merchantCountryCode: merchantCountry(wallets?.googlePay?.merchantCountryCode, config.processingCountry),
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

/**
 * Apple Pay's recurring payment (iOS 16+): the plan in Apple's sheet and the buyer's Wallet, with the free trial and
 * the page to manage it. Null for a one-time payment, or without a manage page (Apple requires one).
 */
function applePayRecurring(config: CheckoutConfig, charge: Charge, merchant: string) {
  const plan = config.subscription;
  if (!plan?.manageUrl) return null;
  const trial = plan.mode === 'setup';
  const units: Record<string, string> = { DAY: 'day', WEEK: 'week', MONTH: 'month', YEAR: 'year' };
  return {
    description: plan.planName,
    label: plan.planName,
    amount: (recurringMinorIn(config, charge) / 100).toFixed(2),
    intervalUnit: units[plan.interval.toUpperCase()] ?? 'month',
    intervalCount: plan.intervalCount,
    // A trial's plan starts when the trial ends; otherwise it starts today, with this first payment.
    startDate: trial ? plan.nextChargeAt : new Date().toISOString(),
    trialEndDate: trial ? plan.nextChargeAt : null,
    managementUrl: plan.manageUrl,
    billingAgreement: plan.consentText,
    merchant,
    dueToday: (charge.amountMinor / 100).toFixed(2),
  };
}

/**
 * What is charged: a change updates it in place (the buyer switched currency). Cards go to the native card form
 * (`card`), the sheet's own Card row; every other method to the element's rows. A subscription saves the card for
 * its renewals: `setup` (a free trial, nothing charged today) or a payment kept for later use.
 */
export function elementIntentJson(charge: Charge, methods: SheetMethods, config?: CheckoutConfig | null): string {
  const plan = config?.subscription;
  return JSON.stringify({
    amount: charge.amountMinor,
    currency: charge.currency.toLowerCase(),
    paymentMethodTypes: methods.elementTypes.filter((t) => t !== 'card'),
    card: methods.elementTypes.includes('card'),
    mode: plan?.mode === 'setup' ? 'setup' : 'payment',
    setupFutureUsage: plan ? 'off_session' : null,
  });
}
