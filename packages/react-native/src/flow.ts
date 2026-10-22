import type {
  CheckoutConfig,
  CheckoutResult,
  MethodKey,
  PayResponse,
  SessionStatus,
  SubscriptionPayResponse,
  SubscriptionPlan,
  SubscriptionResult,
  SubscriptionView,
  VezmoError,
} from './types';

/** The processor payment-method types the sheet's element shows, by our method key. */
const ELEMENT_TYPES: Partial<Record<MethodKey, string>> = {
  card: 'card',
  klarna: 'klarna',
  affirm: 'affirm',
  afterpayClearpay: 'afterpay_clearpay',
  amazonPay: 'amazon_pay',
  cashapp: 'cashapp',
};

/** Methods the business priced only in US dollars: hidden when the buyer pays in their own currency (as on web). */
const USD_ONLY: MethodKey[] = ['klarna', 'affirm', 'afterpayClearpay', 'amazonPay', 'cashapp', 'ach'];

export interface Charge {
  amountMinor: number;
  currency: string;
  /** The sealed local-currency offer when the buyer pays in their own currency. */
  localOffer: string | null;
}

/**
 * What the buyer is charged: the session's price, or the offer in their own currency when they chose it. A free
 * trial charges nothing today, whichever currency the plan will renew in.
 */
export function chargeOf(config: CheckoutConfig, payLocal: boolean): Charge {
  const offer = config.localOffer;
  const trial = config.subscription?.mode === 'setup';
  if (payLocal && offer) {
    return { amountMinor: trial ? 0 : offer.amountMinor, currency: offer.currency.toUpperCase(), localOffer: offer.token };
  }
  return { amountMinor: trial ? 0 : config.amountMinor, currency: config.currency.toUpperCase(), localOffer: null };
}

// ── subscriptions ─────────────────────────────────────────────────────────

/**
 * The subscription checkout as the sheet's config: cards, wallets and Instant ACH (when the business offers it for this
 * plan), the plan beside the price.
 */
export function configFromSubscription(v: SubscriptionView): CheckoutConfig {
  const minor = (major: number) => Math.round(major * 100);
  return {
    publishableKey: v.publishableKey ?? '',
    connectedAccountId: v.connectedAccountId,
    livemode: v.livemode,
    amount: v.amount,
    amountMinor: minor(v.amount),
    currency: v.currency,
    title: v.planName,
    merchantName: v.merchant?.name ?? null,
    enabledMethods: { card: true, applePay: true, googlePay: true, instantAch: v.instantAchEnabled === true },
    bankSupported: false,
    customerName: v.customer?.name ?? null,
    customerEmail: v.customer?.email ?? null,
    theme: null,
    paymentId: '',
    localOffer: v.localOffer,
    appReturnUrl: null,
    subscription: {
      ref: v.subscriptionRef,
      mode: v.mode,
      planName: v.planName,
      recurringAmount: v.recurringAmount,
      currency: v.currency,
      interval: v.interval,
      intervalCount: v.intervalCount,
      trialDays: v.trialDays,
      nextChargeAt: v.nextChargeAt,
      manageUrl: v.manageUrl,
      consentText: v.consentText,
    },
    processingCountry: v.processingCountry ?? null,
  };
}

/**
 * Apple Pay's and Google Pay's merchant country: the app's own, else the country of the account that processes the
 * payment (from the session: the business's own on Connect, Vezmo's on merchant of record), else US (a server that
 * doesn't say).
 */
export function merchantCountry(app: string | null | undefined, session: string | null | undefined): string {
  for (const candidate of [app, session]) {
    const code = (candidate ?? '').trim().toUpperCase();
    if (/^[A-Z]{2}$/.test(code)) return code;
  }
  return 'US';
}

/** Paid, or money on its way. */
export function isPaid(result: CheckoutResult | null | undefined): result is Extract<CheckoutResult, { status: 'succeeded' | 'processing' }> {
  return result?.status === 'succeeded' || result?.status === 'processing';
}

function subscriptionOf(ref: string, status: string): SubscriptionResult {
  const s = status.toLowerCase();
  return { ref, status: s === 'active' || s === 'trialing' ? s : 'incomplete' };
}

/** A subscription /pay answer in the shape the session handles for every payment. */
export function payFromSubscription(r: SubscriptionPayResponse): PayResponse {
  const base = { method: r.method };
  if (r.state === 'succeeded') {
    return {
      ...base,
      state: 'succeeded',
      paymentId: r.paymentId,
      transactionId: r.transactionId,
      clientSecret: r.clientSecret,
      subscription: subscriptionOf(r.subscriptionRef, r.subscriptionStatus),
    };
  }
  if (r.state === 'processing') {
    return {
      ...base,
      state: 'processing',
      paymentId: r.paymentId,
      clientSecret: r.clientSecret,
      subscription: subscriptionOf(r.subscriptionRef, 'incomplete'),
    };
  }
  return { ...base, state: 'requires_action', paymentId: null, clientSecret: r.clientSecret };
}

/** The result after a subscription start finished on the device (3D Secure), from /confirm. */
export function resultFromSubscriptionConfirm(r: {
  status: string;
  subscriptionRef: string;
  paymentId?: string;
  transactionId?: string | null;
}): CheckoutResult {
  const subscription = subscriptionOf(r.subscriptionRef, r.status);
  if (r.status === 'processing') return { status: 'processing', paymentId: r.paymentId ?? null, subscription };
  return { status: 'succeeded', paymentId: r.paymentId ?? null, transactionId: r.transactionId ?? null, subscription };
}

/**
 * The plan's price in the currency the buyer pays in, in minor units: the plan's own, or about what it is in their
 * currency at today's offer (each renewal is converted at the rate of its billing day).
 */
export function recurringMinorIn(config: CheckoutConfig, charge: Charge): number {
  const plan = config.subscription;
  if (!plan) return 0;
  const own = Math.round(plan.recurringAmount * 100);
  const offer = config.localOffer;
  if (!charge.localOffer || !offer) return own;
  // The offer was priced on what is due today (the plan's price on a free trial).
  const basis = plan.mode === 'setup' ? own : config.amountMinor;
  return basis > 0 ? Math.round((own * offer.amountMinor) / basis) : own;
}

const UNIT: Record<string, [string, string]> = {
  HOUR: ['hour', 'hours'],
  DAY: ['day', 'days'],
  WEEK: ['week', 'weeks'],
  MONTH: ['month', 'months'],
  YEAR: ['year', 'years'],
};

/** "month", "3 months": one period of the plan. */
export function periodText(interval: string, count: number): string {
  const [one, many] = UNIT[interval.toUpperCase()] ?? [interval.toLowerCase(), `${interval.toLowerCase()}s`];
  return count > 1 ? `${count} ${many}` : one;
}

/** "$9.99 / month", "$30.00 every 3 months": the plan's price, as the buyer reads it. */
export function planPriceText(plan: SubscriptionPlan, locale?: string): string {
  const price = formatAmount(Math.round(plan.recurringAmount * 100), plan.currency.toUpperCase(), locale);
  return plan.intervalCount > 1 ? `${price} every ${periodText(plan.interval, plan.intervalCount)}` : `${price} / ${periodText(plan.interval, 1)}`;
}

/** "23 Oct 2026" (the buyer's own date format). */
export function dateText(iso: string | null | undefined, locale?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  try {
    return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric' }).format(d);
  } catch {
    return d.toISOString().slice(0, 10);
  }
}

export interface SheetMethods {
  /** For the element (cards and wallets, pay-later, Cash App), in this order. */
  elementTypes: string[];
  applePay: boolean;
  googlePay: boolean;
  /** Our own panels: UPI (INR) and Pix (BRL), only when paying in that currency. */
  upi: boolean;
  pix: boolean;
  /** Our own Instant ACH row (a US bank account through the engine's bank step): US dollars only. */
  instantAch: boolean;
}

/**
 * The ways to pay the sheet offers, from the business's enabled methods: the same rules as the web checkout. Bank
 * debit (ACH) is not offered in the mobile sheet; Instant ACH is, in US dollars (never in the buyer's own currency).
 */
export function sheetMethods(config: CheckoutConfig, charge: Charge): SheetMethods {
  const on = config.enabledMethods ?? {};
  const local = !!charge.localOffer;
  const offerMethods = config.localOffer?.methods ?? [];
  const elementTypes = (Object.keys(ELEMENT_TYPES) as MethodKey[])
    .filter((k) => k === 'card' || on[k] === true)
    .filter((k) => !(local && USD_ONLY.includes(k)))
    .map((k) => ELEMENT_TYPES[k]!);
  return {
    elementTypes,
    applePay: on.applePay === true,
    googlePay: on.googlePay === true,
    upi: local && charge.currency === 'INR' && on.upi === true && offerMethods.includes('upi'),
    pix: local && charge.currency === 'BRL' && on.pix === true && offerMethods.includes('pix'),
    instantAch: !local && charge.currency === 'USD' && on.instantAch === true,
  };
}

/**
 * The linked bank account as the Instant ACH row shows it: "•••• 6789". Null until the engine's bank step has linked one
 * (its label then carries the account's last four, maybe wrapped in direction marks); the engine's own words are never
 * shown.
 */
export function bankLabel(engineLabel: string | null | undefined): string | null {
  const runs = (engineLabel ?? '').match(/[0-9]{4,}/g);
  const last = runs?.[runs.length - 1];
  return last ? `•••• ${last.slice(-4)}` : null;
}

/** The merchant's result for a /pay answer that needs nothing more from the buyer. */
export function resultFromPay(res: PayResponse): CheckoutResult | null {
  const sub = res.subscription ? { subscription: res.subscription } : {};
  if (res.state === 'succeeded') {
    return { status: 'succeeded', paymentId: res.paymentId, transactionId: res.transactionId, ...sub };
  }
  if (res.state === 'processing') return { status: 'processing', paymentId: res.paymentId, ...sub };
  return null; // requires_action: the element finishes it first
}

/** The merchant's result from the session status (after 3D Secure, a redirect, UPI or Pix). */
export function resultFromStatus(s: SessionStatus): CheckoutResult | null {
  switch (s.state) {
    case 'succeeded':
      return { status: 'succeeded', paymentId: s.paymentId, transactionId: s.transactionId };
    case 'processing':
      return { status: 'processing', paymentId: s.paymentId };
    case 'failed':
      return { status: 'failed', error: DECLINED };
    case 'canceled':
      return { status: 'failed', error: { code: 'expired', message: EXPIRED.message } };
    default:
      return null; // still open or waiting on the buyer
  }
}

export const DECLINED: VezmoError = {
  code: 'declined',
  message: 'Your payment didn’t go through. Try another card or way to pay.',
};
export const EXPIRED: VezmoError = {
  code: 'expired',
  message: 'This checkout has expired. Please start again.',
};

/** `pi_123_secret_abc` → `pi_123` (or a free trial's `seti_…`): the intent the device finished, for /confirm. */
export function intentIdOf(clientSecret: string | null | undefined): string | null {
  const m = clientSecret ? /^((?:pi|seti)_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$/.exec(clientSecret) : null;
  return m ? m[1]! : null;
}

/** Amount for display, e.g. 4900 USD → "$49.00", 250000 INR → "₹2,500.00". */
export function formatAmount(amountMinor: number, currency: string, locale?: string): string {
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amountMinor / 100);
  } catch {
    return `${(amountMinor / 100).toFixed(2)} ${currency}`;
  }
}
