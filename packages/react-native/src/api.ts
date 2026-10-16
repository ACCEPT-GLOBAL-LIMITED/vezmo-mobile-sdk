import { SDK_VERSION } from './version';
import type { CheckoutConfig, PayResponse, QrBilling, QrPayment, SessionStatus, VezmoError } from './types';

export const DEFAULT_API_BASE = 'https://api.vezmo.com/api/v1';

/** A failed call, already in Vezmo's words (the API never names its processor to a buyer). */
export class VezmoApiError extends Error {
  readonly code: VezmoError['code'];
  readonly httpStatus: number | null;
  /** The API's own error code when it sent one (e.g. `local_price_changed`). */
  readonly reason: string | null;
  constructor(
    code: VezmoError['code'],
    message: string,
    httpStatus: number | null,
    reason: string | null = null,
  ) {
    super(message);
    this.name = 'VezmoApiError';
    this.code = code;
    this.httpStatus = httpStatus;
    this.reason = reason;
  }
  toVezmoError(): VezmoError {
    return { code: this.code, message: this.message };
  }
}

type Fetch = typeof fetch;

/**
 * The buyer-side calls of an API checkout session. Everything is scoped by the session's client token; no merchant
 * key ever reaches the app.
 */
export class VezmoApi {
  constructor(
    private readonly apiBase: string = DEFAULT_API_BASE,
    private readonly integration: 'ios' | 'android',
    private readonly fetchImpl: Fetch = fetch,
  ) {}

  private async call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.apiBase}${path}`, {
        method,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          'Vezmo-SDK': `react-native/${SDK_VERSION}`,
        },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      });
    } catch {
      throw new VezmoApiError('network', 'Check your connection and try again.', null);
    }
    let json: any = null;
    try {
      json = await res.json();
    } catch {
      /* an empty or non-JSON body */
    }
    if (res.ok && json?.success !== false) return (json?.data ?? json) as T;
    const message: string =
      (typeof json?.message === 'string' && json.message) ||
      json?.errors?.[0]?.message ||
      'Something went wrong. Please try again.';
    const reason = typeof json?.errors?.[0]?.code === 'string' ? json.errors[0].code : null;
    throw new VezmoApiError(codeFor(res.status, json), message, res.status, reason);
  }

  checkout(token: string, opts: { currency?: string } = {}) {
    const q = `redirectMethods=1${opts.currency ? `&currency=${encodeURIComponent(opts.currency)}` : ''}`;
    return this.call<CheckoutConfig>('POST', `/secure-payments/${encodeURIComponent(token)}/checkout?${q}`);
  }

  pay(
    token: string,
    body: { confirmationToken: string; localOffer?: string | null; returnUrl?: string | null },
  ) {
    return this.call<PayResponse>('POST', `/secure-payments/${encodeURIComponent(token)}/pay`, {
      confirmationToken: body.confirmationToken,
      integration: this.integration,
      ...(body.localOffer ? { localOffer: body.localOffer } : {}),
      ...(body.returnUrl ? { returnUrl: body.returnUrl } : {}),
    });
  }

  /** The session's intent, made or reused (UPI and Pix start from it). */
  intent(token: string, localOffer: string | null) {
    return this.call<{ id: string; clientSecret: string; connectedAccountId: string | null; livemode: boolean }>(
      'POST',
      `/secure-payments/${encodeURIComponent(token)}/intent?redirectMethods=1`,
      localOffer ? { localOffer } : {},
    );
  }

  /** Start UPI or Pix on the intent: the UPI app link, or the Pix code to paste in a bank app. */
  startQr(
    method: 'upi' | 'pix',
    body: {
      clientSecret: string;
      connectedAccountId: string | null;
      livemode: boolean;
      returnUrl: string;
      billing: QrBilling;
    },
  ) {
    return this.call<QrPayment>('POST', `/checkout/${method}`, {
      clientSecret: body.clientSecret,
      ...(body.connectedAccountId ? { connectedAccountId: body.connectedAccountId } : {}),
      livemode: body.livemode,
      returnUrl: body.returnUrl,
      billing: body.billing,
    });
  }

  /** The session's own checkout page (https): where UPI and Pix send a buyer who comes back by browser. */
  checkoutPageUrl(token: string) {
    return `${this.apiBase}/secure-payments/${encodeURIComponent(token)}`;
  }

  /** Records a payment that finished on the device (after 3D Secure or a redirect). */
  confirm(token: string, paymentIntentId: string) {
    return this.call<{ payment?: { id: string; transactionId: string | null }; pending?: boolean }>(
      'POST',
      `/secure-payments/${encodeURIComponent(token)}/confirm`,
      { paymentIntentId, integration: this.integration },
    );
  }

  status(token: string) {
    return this.call<SessionStatus>('GET', `/secure-payments/${encodeURIComponent(token)}/status`);
  }
}

function codeFor(status: number, json: any): VezmoError['code'] {
  if (status === 410) return 'expired';
  if (status === 409) {
    return json?.errors?.[0]?.code === 'local_price_changed' ? 'unknown' : 'already_paid';
  }
  if (status === 400 || status === 402) return 'declined';
  if (status === 401 || status === 403 || status === 404) return 'config';
  return 'unknown';
}
