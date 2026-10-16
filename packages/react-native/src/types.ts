/** Where the session runs: from the checkout config, never guessed from a key. */
export type VezmoEnvironment = 'live' | 'sandbox';

/** The outcome `presentCheckout()` resolves with. It never throws. */
export type CheckoutResult =
  | { status: 'succeeded'; paymentId: string; transactionId: string | null }
  /** Paid, money on its way (a bank debit, UPI or Pix still settling): wait for the webhook. */
  | { status: 'processing'; paymentId: string }
  /** The buyer closed the sheet. Not an error. */
  | { status: 'canceled' }
  | { status: 'failed'; error: VezmoError };

export interface VezmoError {
  /**
   * `declined` (the bank said no; the buyer can try another card), `expired` (the session ran out: create a new
   * one), `already_paid`, `not_available` (this way to pay can't be used here), `network`, `config` (a setup
   * mistake in the app), `unknown`.
   */
  code:
    | 'declined'
    | 'expired'
    | 'already_paid'
    | 'not_available'
    | 'network'
    | 'config'
    | 'unknown';
  /** Safe to show the buyer, in Vezmo's words. */
  message: string;
}

/** The buyer's own currency, offered beside the price. */
export interface LocalOffer {
  currency: string;
  amount: number;
  amountMinor: number;
  price: number;
  rate: number;
  markupPercent: number;
  usdPerUnit: number;
  expiresAt: string;
  country: string | null;
  choices?: string[];
  /** Methods offered when paying in this currency, e.g. ['upi'] for INR, ['pix'] for BRL. */
  methods?: string[];
  /** Sealed; sent back to pay in this currency. */
  token: string;
}

/** What GET …/checkout returns that the sheet uses (Vezmo API checkout config). */
export interface CheckoutConfig {
  publishableKey: string;
  /** Null for a merchant-of-record business: the platform account. */
  connectedAccountId: string | null;
  livemode: boolean;
  amount: number;
  amountMinor: number;
  currency: string;
  title: string;
  merchantName: string | null;
  enabledMethods: Partial<Record<MethodKey, boolean>>;
  bankSupported: boolean;
  customerName: string | null;
  customerEmail: string | null;
  theme: 'light' | 'dark' | 'auto' | null;
  paymentId: string;
  localOffer: LocalOffer | null;
  appReturnUrl: string | null;
}

export type MethodKey =
  | 'card'
  | 'ach'
  | 'cashapp'
  | 'applePay'
  | 'googlePay'
  | 'klarna'
  | 'affirm'
  | 'afterpayClearpay'
  | 'amazonPay'
  | 'upi'
  | 'pix';

/** POST …/pay — what the server did with the buyer's confirmation token. */
export type PayResponse =
  | { state: 'succeeded'; paymentId: string; transactionId: string | null; clientSecret: string | null }
  | { state: 'processing'; paymentId: string; clientSecret: string | null }
  | { state: 'requires_action'; paymentId: string; clientSecret: string };

export type SessionState =
  | 'open'
  | 'requires_action'
  | 'processing'
  | 'succeeded'
  | 'failed'
  | 'canceled';

export interface SessionStatus {
  state: SessionState;
  paymentId: string;
  transactionId: string | null;
  livemode: boolean;
}

/** Who pays with UPI or Pix (Pix also needs the CPF or CNPJ). */
export interface QrBilling {
  name: string;
  line1: string;
  city?: string;
  postalCode?: string;
  country?: string;
  email?: string;
  taxId?: string;
}

/** A started UPI or Pix payment. */
export interface QrPayment {
  status: string;
  qr: { svg: string | null; png: string | null; expiresAt: number | null } | null;
  /** UPI: opens the buyer's UPI app on this phone. */
  link: string | null;
  /** Pix: the code to paste in a bank app. */
  code: string | null;
}
