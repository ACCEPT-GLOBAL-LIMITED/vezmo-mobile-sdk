import { VezmoApi, VezmoApiError } from './api';
import {
  chargeOf,
  configFromSubscription,
  DECLINED,
  EXPIRED,
  intentIdOf,
  payFromSubscription,
  resultFromPay,
  resultFromStatus,
  resultFromSubscriptionConfirm,
  sheetMethods,
  type Charge,
  type SheetMethods,
} from './flow';
import type { CheckoutConfig, CheckoutResult, PaidMethod, PayResponse, QrBilling, QrPayment, VezmoError } from './types';

/** What the element can be asked to do (the native view's commands). */
export interface ElementPort {
  confirm(): void;
  resolveConfirmation(clientSecret: string | null, errorMessage?: string): void;
  clearSelection(): void;
}

/** What the card flow can be asked to do: the card form, paying with its card, answering its confirmation. */
export interface CardFlowPort {
  presentCard(): void;
  confirmCard(): void;
  resolveConfirmation(clientSecret: string | null, errorMessage?: string): void;
}

/** The card from the native card form, for the sheet's own Card row. */
export interface CardChoice {
  /** "•••• 4242". */
  label: string;
  /** The network's logo (PNG data URI) when the platform gives one. */
  image: string | null;
}

export type SessionPhase =
  | { name: 'loading' }
  | { name: 'ready'; error: VezmoError | null }
  | { name: 'paying' }
  /** UPI or Pix started: the buyer finishes in their bank app; we watch the session. */
  | { name: 'waiting'; method: 'upi' | 'pix'; qr: QrPayment }
  | { name: 'done'; result: CheckoutResult }
  /** The session can't be paid (expired, already paid, the business can't take payments). */
  | { name: 'closed'; result: CheckoutResult };

export interface SessionSnapshot {
  phase: SessionPhase;
  config: CheckoutConfig | null;
  /** The buyer chose to pay in their own currency. */
  payLocal: boolean;
  charge: Charge | null;
  methods: SheetMethods | null;
  /** The element has a method selected and can be paid. */
  canPay: boolean;
  /** The selected method as the element names it ("•••• 4242", "Klarna"). */
  option: string | null;
  /** The method the buyer paid with, kept for the receipt (the element clears its selection after paying). */
  paidWith: string | null;
  /** The same as Vezmo's server read it from the payment (the card's network for its logo). */
  paidMethod: PaidMethod | null;
  /** The element couldn't load (shown in the sheet with Try again). */
  elementError: string | null;
  /** Bumped by Try again: rebuilds the native element. */
  elementAttempt: number;
  /**
   * The selected way to pay: the sheet's Card row (`card`), one of the element's rows (`element`: wallets, pay-later),
   * or our UPI or Pix row.
   */
  method: 'card' | 'element' | 'upi' | 'pix';
  /** The card typed in the card form, kept when the buyer looks at another method and comes back. */
  card: CardChoice | null;
}

const PRICE_CHANGED = 'The price in your currency just changed. Check the new amount and pay again.';

/**
 * One checkout, from the session's client token (or a subscription checkout's token) to a result. The money decisions
 * are the server's: this only moves the buyer's confirmation token to Vezmo and the answer back to the element.
 */
export class CheckoutSession {
  private snapshot: SessionSnapshot = {
    phase: { name: 'loading' },
    config: null,
    payLocal: false,
    charge: null,
    methods: null,
    canPay: false,
    option: null,
    paidWith: null,
    paidMethod: null,
    elementError: null,
    elementAttempt: 0,
    method: 'element',
    card: null,
  };
  private listeners = new Set<() => void>();
  private element: ElementPort | null = null;
  private cardFlow: CardFlowPort | null = null;
  /** Which native view started the payment in progress (its confirmation is answered there). */
  private confirmVia: 'element' | 'card' = 'element';
  private lastPay: PayResponse | null = null;
  private lastError: VezmoError | null = null;
  private watching: ReturnType<typeof setInterval> | null = null;
  /** A status check is on its way (the poll, Check status and the return from the bank app don't overlap). */
  private checking = false;
  private qrIntentId: string | null = null;
  /** The card form was opened by the buyer: the card it returns becomes the selection. */
  private awaitingCard = false;

  constructor(
    private readonly api: VezmoApi,
    private readonly token: string,
    private readonly returnUrl: string | null,
    /** `subscription`: the token is a subscription checkout's (cards and wallets; a first payment or a free trial). */
    private readonly kind: 'payment' | 'subscription' = 'payment',
  ) {}

  private async fetchConfig(): Promise<CheckoutConfig> {
    return this.kind === 'subscription'
      ? configFromSubscription(await this.api.subscription(this.token))
      : this.api.checkout(this.token);
  }

  // ── store ──────────────────────────────────────────────────────────────
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  getSnapshot = () => this.snapshot;
  private set(next: Partial<SessionSnapshot>) {
    this.snapshot = { ...this.snapshot, ...next };
    this.listeners.forEach((l) => l());
  }

  attachElement(element: ElementPort | null) {
    this.element = element;
  }

  attachCardFlow(cardFlow: CardFlowPort | null) {
    this.cardFlow = cardFlow;
  }

  // ── loading and currency ───────────────────────────────────────────────
  async load(): Promise<void> {
    try {
      const config = await this.fetchConfig();
      // The buyer's own currency is offered first when there is an offer (as on the web checkout).
      this.apply(config, !!config.localOffer);
      this.set({ phase: { name: 'ready', error: null } });
    } catch (e) {
      this.close(e);
    }
  }

  /** The buyer switched between their own currency and the price's. */
  choosePayLocal(payLocal: boolean) {
    if (!this.snapshot.config || this.snapshot.phase.name === 'paying') return;
    this.apply(this.snapshot.config, payLocal);
  }

  /** The element's selected method changed (null: nothing selected). Choosing one leaves the card, UPI or Pix. */
  setOption(option: { label: string } | null) {
    const leave = !!option && this.snapshot.method !== 'element' && this.snapshot.phase.name === 'ready';
    this.set({ canPay: !!option, option: option?.label || null, ...(leave ? { method: 'element' as const } : {}) });
  }

  /** The buyer tapped the Card row: the card already typed, or the card form to type one. */
  chooseCard() {
    if (this.snapshot.phase.name !== 'ready') return;
    if (this.snapshot.card) {
      this.element?.clearSelection();
      this.set({ method: 'card', phase: { name: 'ready', error: null } });
    } else {
      this.changeCard();
    }
  }

  /** Change (or first type) the card. */
  changeCard() {
    if (this.snapshot.phase.name !== 'ready') return;
    this.awaitingCard = true;
    this.cardFlow?.presentCard();
  }

  /** The card form reported its card (or none). One the buyer just typed becomes the selection. */
  setCard(card: CardChoice | null) {
    const chosen = !!card && this.awaitingCard && this.snapshot.phase.name === 'ready';
    if (card) this.awaitingCard = false;
    if (chosen) this.element?.clearSelection();
    this.set({ card, ...(chosen ? { method: 'card' as const, phase: { name: 'ready', error: null } } : {}) });
  }

  /** The Pay button can be pressed: a typed card on the Card row, or a method chosen in the element. */
  static canPay(s: SessionSnapshot): boolean {
    return s.method === 'card' ? !!s.card : s.method === 'element' ? s.canPay : false;
  }

  setElementError(message: string | null) {
    // The platform's own reason, for the app's developer (the buyer sees "Couldn't load payment options").
    if (message) console.warn(`[Vezmo] Payment options could not load: ${message}`);
    this.set({ elementError: message, ...(message ? { canPay: false } : {}) });
  }

  retryElement() {
    this.set({ elementError: null, elementAttempt: this.snapshot.elementAttempt + 1 });
  }

  private apply(config: CheckoutConfig, payLocal: boolean) {
    const charge = chargeOf(config, payLocal && !!config.localOffer);
    const methods = sheetMethods(config, charge);
    // A panel that this currency doesn't offer falls back to the element.
    const method = this.snapshot.method === 'upi' && !methods.upi ? 'element'
      : this.snapshot.method === 'pix' && !methods.pix ? 'element' : this.snapshot.method;
    this.set({ config, payLocal: !!charge.localOffer, charge, methods, method });
  }

  /** UPI or Pix (the sheet's own rows) or back to the element; one method is selected at a time. */
  chooseMethod(method: 'card' | 'element' | 'upi' | 'pix') {
    if (this.snapshot.phase.name !== 'ready') return;
    if (method !== 'element') this.element?.clearSelection();
    this.set({ method, phase: { name: 'ready', error: null } });
  }

  // ── UPI and Pix ────────────────────────────────────────────────────────
  /** Start UPI or Pix: the UPI app link or the Pix code comes back; then we watch the session until it is paid. */
  async payWithQr(method: 'upi' | 'pix', billing: QrBilling) {
    if (this.snapshot.phase.name !== 'ready') return;
    this.set({ phase: { name: 'paying' }, paidWith: method === 'upi' ? 'UPI' : 'Pix' });
    try {
      const intent = await this.api.intent(this.token, this.snapshot.charge?.localOffer ?? null);
      this.qrIntentId = intent.id;
      const qr = await this.api.startQr(method, {
        clientSecret: intent.clientSecret,
        connectedAccountId: intent.connectedAccountId,
        livemode: intent.livemode,
        returnUrl: this.api.checkoutPageUrl(this.token),
        billing,
      });
      this.set({ phase: { name: 'waiting', method, qr } });
      this.watch();
    } catch (e) {
      const err = e instanceof VezmoApiError ? e.toVezmoError() : { code: 'unknown' as const, message: DECLINED.message };
      if (err.code === 'expired' || err.code === 'already_paid') return this.close(e);
      // UPI and Pix are always in the buyer's currency: an offer that ran out is fetched again, not retried as is.
      if (e instanceof VezmoApiError && e.reason === 'local_price_changed') {
        this.refreshOffer();
        return this.set({ phase: { name: 'ready', error: { code: 'unknown', message: PRICE_CHANGED } } });
      }
      this.set({ phase: { name: 'ready', error: err } });
    }
  }

  /** The buyer gave up waiting: back to the sheet (a payment made meanwhile still settles). */
  stopWaiting() {
    this.unwatch();
    if (this.snapshot.phase.name === 'waiting') this.set({ phase: { name: 'ready', error: null } });
  }

  /** Check now (the buyer came back from their bank app). */
  async checkNow() {
    if (this.snapshot.phase.name !== 'waiting' || this.checking) return;
    this.checking = true;
    try {
      let status;
      try {
        status = await this.api.status(this.token);
      } catch {
        return;
      }
      const result = resultFromStatus(status);
      if (!result || this.snapshot.phase.name !== 'waiting') return;
      this.unwatch();
      if (result.status === 'failed') {
        this.set({ phase: { name: 'ready', error: { code: 'declined', message: 'The payment didn’t go through. Try again.' } } });
        return;
      }
      // Recorded now rather than when the webhook arrives (as the web checkout does).
      if (this.qrIntentId) await this.api.confirm(this.token, this.qrIntentId).catch(() => undefined);
      this.set({ phase: { name: 'done', result } });
    } finally {
      this.checking = false;
    }
  }

  private watch() {
    this.unwatch();
    const started = Date.now();
    this.watching = setInterval(() => {
      // A code lasts 10 minutes; stop watching a little after.
      if (Date.now() - started > 11 * 60_000) return this.stopWaiting();
      void this.checkNow();
    }, 2000);
  }

  private unwatch() {
    if (this.watching) clearInterval(this.watching);
    this.watching = null;
  }

  /** The sheet is going away. */
  dispose() {
    this.unwatch();
  }

  // ── paying ─────────────────────────────────────────────────────────────
  pay() {
    if (this.snapshot.phase.name !== 'ready' || !CheckoutSession.canPay(this.snapshot)) return;
    const card = this.snapshot.method === 'card';
    if (card ? !this.cardFlow : !this.element) return;
    this.lastPay = null;
    this.lastError = null;
    this.confirmVia = card ? 'card' : 'element';
    this.set({ phase: { name: 'paying' }, paidWith: card ? (this.snapshot.card?.label ?? null) : this.snapshot.option });
    if (card) this.cardFlow!.confirmCard();
    else this.element!.confirm();
  }

  /** An express wallet button was tapped (Apple Pay, Google Pay): its payment has started in the card flow. */
  walletStarted(wallet: string) {
    if (this.snapshot.phase.name !== 'ready') return;
    this.lastPay = null;
    this.lastError = null;
    this.confirmVia = 'card';
    const name = wallet === 'apple_pay' ? 'Apple Pay' : wallet === 'google_pay' ? 'Google Pay' : wallet;
    this.set({ phase: { name: 'paying' }, paidWith: name });
  }

  /** The native view that started the payment in progress. */
  private confirmer(): { resolveConfirmation(clientSecret: string | null, errorMessage?: string): void } | null {
    return this.confirmVia === 'card' ? this.cardFlow : this.element;
  }

  /** The element made a confirmation token: Vezmo prices and confirms the payment with it. */
  async onConfirmationToken(id: string) {
    const charge = this.snapshot.charge;
    try {
      const body = { confirmationToken: id, localOffer: charge?.localOffer ?? null, returnUrl: this.returnUrl };
      const res =
        this.kind === 'subscription'
          ? payFromSubscription(await this.api.subscriptionPay(this.token, body))
          : await this.api.pay(this.token, body);
      this.lastPay = res;
      if (res.method) this.set({ paidMethod: res.method });
      this.confirmer()?.resolveConfirmation(res.clientSecret ?? null, res.clientSecret ? undefined : DECLINED.message);
    } catch (e) {
      const err = e instanceof VezmoApiError ? e : null;
      if (err?.reason === 'local_price_changed') {
        this.lastError = { code: 'unknown', message: PRICE_CHANGED };
        this.refreshOffer();
      } else {
        this.lastError = err?.toVezmoError() ?? { code: 'unknown', message: DECLINED.message };
      }
      this.confirmer()?.resolveConfirmation(null, this.lastError.message);
      if (err && (err.code === 'expired' || err.code === 'already_paid')) this.close(err);
    }
  }

  /** The element finished: completed (maybe after 3D Secure), canceled, or failed. */
  async onConfirmResult(status: 'completed' | 'canceled' | 'failed', message: string) {
    if (this.snapshot.phase.name === 'closed') return;
    if (status === 'canceled') {
      this.set({ phase: { name: 'ready', error: null } });
      return;
    }
    if (status === 'failed') {
      const error = this.lastError ?? (await this.deviceFailure()) ?? { code: 'declined' as const, message: message || DECLINED.message };
      this.set({ phase: { name: 'ready', error } });
      return;
    }
    // Completed. A payment our server already recorded is done; one finished on the device (3D Secure, a redirect)
    // is recorded now, then read back.
    const fromPay = this.lastPay ? resultFromPay(this.lastPay) : null;
    if (fromPay) {
      this.set({ phase: { name: 'done', result: fromPay } });
      return;
    }
    const intentId = intentIdOf(this.lastPay?.clientSecret);
    if (this.kind === 'subscription') {
      this.set({ phase: { name: 'done', result: await this.startedSubscription(intentId) } });
      return;
    }
    try {
      if (intentId) await this.api.confirm(this.token, intentId);
    } catch {
      /* the webhook records it either way; the status below is the answer */
    }
    this.set({ phase: { name: 'done', result: await this.settledResult() } });
  }

  /** The buyer's currency offer no longer holds: fetch the new one; the buyer sees the new amount before paying again. */
  private refreshOffer() {
    this.fetchConfig()
      .then((config) => this.apply(config, this.snapshot.payLocal))
      .catch(() => undefined);
  }

  /**
   * A payment Vezmo handed to the device (3D Secure, a redirect) that then failed there: Vezmo's own verdict and words
   * (the platforms word a decline differently), and the decline recorded now. Null when Vezmo has no such answer.
   */
  private async deviceFailure(): Promise<VezmoError | null> {
    const intentId = this.lastPay?.state === 'requires_action' ? intentIdOf(this.lastPay.clientSecret) : null;
    if (!intentId) return null;
    try {
      if (this.kind === 'subscription') await this.api.subscriptionConfirm(this.token, intentId);
      else await this.api.confirm(this.token, intentId);
    } catch (e) {
      if (e instanceof VezmoApiError && e.code === 'declined') return e.toVezmoError();
    }
    return null;
  }

  /** A subscription started on the device (after 3D Secure): Vezmo records it and says how it stands. */
  private async startedSubscription(intentId: string | null): Promise<CheckoutResult> {
    const ref = this.snapshot.config?.subscription?.ref ?? '';
    try {
      if (intentId) return resultFromSubscriptionConfirm(await this.api.subscriptionConfirm(this.token, intentId));
    } catch {
      /* recorded from the processor's notice instead; the merchant's webhook tells them */
    }
    return { status: 'processing', paymentId: null, subscription: { ref, status: 'incomplete' } };
  }

  /** The session's state, polled briefly while the server catches up with a payment just made. */
  private async settledResult(): Promise<CheckoutResult> {
    for (let i = 0; i < 5; i++) {
      try {
        const result = resultFromStatus(await this.api.status(this.token));
        if (result) return result;
      } catch {
        /* retry */
      }
      await new Promise((r) => setTimeout(r, 1500));
    }
    // Paid on the device and not yet visible: the webhook will tell the merchant's server.
    return { status: 'processing', paymentId: this.snapshot.config?.paymentId ?? '' };
  }

  // ── closing ────────────────────────────────────────────────────────────
  private close(e: unknown) {
    const err = e instanceof VezmoApiError ? e.toVezmoError() : { code: 'network' as const, message: 'Check your connection and try again.' };
    const error: VezmoError = err.code === 'expired' ? EXPIRED : err;
    this.set({ phase: { name: 'closed', result: { status: 'failed', error } } });
  }
}
