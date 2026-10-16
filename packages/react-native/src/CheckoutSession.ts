import { VezmoApi, VezmoApiError } from './api';
import {
  chargeOf,
  DECLINED,
  EXPIRED,
  intentIdOf,
  resultFromPay,
  resultFromStatus,
  sheetMethods,
  type Charge,
  type SheetMethods,
} from './flow';
import type { CheckoutConfig, CheckoutResult, PayResponse, VezmoError } from './types';

/** What the element can be asked to do (the native view's commands). */
export interface ElementPort {
  confirm(): void;
  resolveConfirmation(clientSecret: string | null, errorMessage?: string): void;
}

export type SessionPhase =
  | { name: 'loading' }
  | { name: 'ready'; error: VezmoError | null }
  | { name: 'paying' }
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
}

const PRICE_CHANGED = 'The price in your currency just changed. Check the new amount and pay again.';

/**
 * One checkout, from the session's client token to a result. The money decisions are the server's: this only
 * moves the buyer's confirmation token to Vezmo and the answer back to the element.
 */
export class CheckoutSession {
  private snapshot: SessionSnapshot = {
    phase: { name: 'loading' },
    config: null,
    payLocal: false,
    charge: null,
    methods: null,
    canPay: false,
  };
  private listeners = new Set<() => void>();
  private element: ElementPort | null = null;
  private lastPay: PayResponse | null = null;
  private lastError: VezmoError | null = null;

  constructor(
    private readonly api: VezmoApi,
    private readonly token: string,
    private readonly returnUrl: string | null,
  ) {}

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

  // ── loading and currency ───────────────────────────────────────────────
  async load(): Promise<void> {
    try {
      const config = await this.api.checkout(this.token);
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

  setCanPay(canPay: boolean) {
    if (canPay !== this.snapshot.canPay) this.set({ canPay });
  }

  private apply(config: CheckoutConfig, payLocal: boolean) {
    const charge = chargeOf(config, payLocal && !!config.localOffer);
    this.set({ config, payLocal: !!charge.localOffer, charge, methods: sheetMethods(config, charge) });
  }

  // ── paying ─────────────────────────────────────────────────────────────
  pay() {
    if (this.snapshot.phase.name !== 'ready' || !this.element) return;
    this.lastPay = null;
    this.lastError = null;
    this.set({ phase: { name: 'paying' } });
    this.element.confirm();
  }

  /** The element made a confirmation token: Vezmo prices and confirms the payment with it. */
  async onConfirmationToken(id: string) {
    const charge = this.snapshot.charge;
    try {
      const res = await this.api.pay(this.token, {
        confirmationToken: id,
        localOffer: charge?.localOffer ?? null,
        returnUrl: this.returnUrl,
      });
      this.lastPay = res;
      this.element?.resolveConfirmation(res.clientSecret ?? null, res.clientSecret ? undefined : DECLINED.message);
    } catch (e) {
      const err = e instanceof VezmoApiError ? e : null;
      if (err?.reason === 'local_price_changed') {
        this.lastError = { code: 'unknown', message: PRICE_CHANGED };
        // Fetch the new offer; the buyer sees the new amount before paying again.
        this.api
          .checkout(this.token)
          .then((config) => this.apply(config, this.snapshot.payLocal))
          .catch(() => undefined);
      } else {
        this.lastError = err?.toVezmoError() ?? { code: 'unknown', message: DECLINED.message };
      }
      this.element?.resolveConfirmation(null, this.lastError.message);
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
      this.set({ phase: { name: 'ready', error: this.lastError ?? { code: 'declined', message: message || DECLINED.message } } });
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
    try {
      if (intentId) await this.api.confirm(this.token, intentId);
    } catch {
      /* the webhook records it either way; the status below is the answer */
    }
    this.set({ phase: { name: 'done', result: await this.settledResult() } });
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
