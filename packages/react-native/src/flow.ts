import type {
  CheckoutConfig,
  CheckoutResult,
  MethodKey,
  PayResponse,
  SessionStatus,
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

/** What the buyer is charged: the session's price, or the offer in their own currency when they chose it. */
export function chargeOf(config: CheckoutConfig, payLocal: boolean): Charge {
  const offer = config.localOffer;
  if (payLocal && offer) {
    return { amountMinor: offer.amountMinor, currency: offer.currency.toUpperCase(), localOffer: offer.token };
  }
  return { amountMinor: config.amountMinor, currency: config.currency.toUpperCase(), localOffer: null };
}

export interface SheetMethods {
  /** For the element (cards and wallets, pay-later, Cash App), in this order. */
  elementTypes: string[];
  applePay: boolean;
  googlePay: boolean;
  /** Our own panels: UPI (INR) and Pix (BRL), only when paying in that currency. */
  upi: boolean;
  pix: boolean;
}

/**
 * The ways to pay the sheet offers, from the business's enabled methods: the same rules as the web checkout. Bank
 * debit (ACH) is not offered in the mobile sheet yet.
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
  };
}

/** The merchant's result for a /pay answer that needs nothing more from the buyer. */
export function resultFromPay(res: PayResponse): CheckoutResult | null {
  if (res.state === 'succeeded') {
    return { status: 'succeeded', paymentId: res.paymentId, transactionId: res.transactionId };
  }
  if (res.state === 'processing') return { status: 'processing', paymentId: res.paymentId };
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

/** `pi_123_secret_abc` → `pi_123`: the intent the device finished, for /confirm. Null for anything else. */
export function intentIdOf(clientSecret: string | null | undefined): string | null {
  const m = clientSecret ? /^(pi_[A-Za-z0-9]+)_secret_[A-Za-z0-9_-]+$/.exec(clientSecret) : null;
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
