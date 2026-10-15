import {
  chargeOf,
  formatAmount,
  intentIdOf,
  resultFromPay,
  resultFromStatus,
  sheetMethods,
} from '../flow';
import type { CheckoutConfig } from '../types';

const config = (over: Partial<CheckoutConfig> = {}): CheckoutConfig => ({
  publishableKey: 'pk_test_x',
  connectedAccountId: 'acct_1',
  livemode: false,
  amount: 49,
  amountMinor: 4900,
  currency: 'USD',
  title: 'Order',
  merchantName: 'Acme',
  enabledMethods: { card: true, applePay: true, googlePay: true, klarna: true, cashapp: true, upi: true },
  bankSupported: false,
  customerName: null,
  customerEmail: null,
  theme: null,
  paymentId: 'pay_1',
  appReturnUrl: null,
  localOffer: {
    currency: 'INR',
    amount: 4200,
    amountMinor: 420000,
    price: 49,
    rate: 85.7,
    markupPercent: 2,
    usdPerUnit: 0.0117,
    expiresAt: '2026-10-16T00:00:00Z',
    country: 'IN',
    methods: ['upi'],
    token: 'offer_sealed',
  },
  ...over,
});

describe('chargeOf', () => {
  it("is the price, or the buyer's own currency when they chose it", () => {
    expect(chargeOf(config(), false)).toEqual({ amountMinor: 4900, currency: 'USD', localOffer: null });
    expect(chargeOf(config(), true)).toEqual({
      amountMinor: 420000,
      currency: 'INR',
      localOffer: 'offer_sealed',
    });
    expect(chargeOf(config({ localOffer: null }), true).localOffer).toBeNull();
  });
});

describe('sheetMethods', () => {
  it('in US dollars: card, the enabled pay-later methods and Cash App, wallets; no UPI', () => {
    const m = sheetMethods(config(), chargeOf(config(), false));
    expect(m).toEqual({ elementTypes: ['card', 'klarna', 'cashapp'], applePay: true, googlePay: true, upi: false, pix: false });
  });

  it("in the buyer's rupees: card only in the element, plus our UPI panel", () => {
    const m = sheetMethods(config(), chargeOf(config(), true));
    expect(m.elementTypes).toEqual(['card']);
    expect(m.upi).toBe(true);
  });

  it('UPI needs the business to offer it and the offer to carry it', () => {
    const off = config({ enabledMethods: { card: true } });
    expect(sheetMethods(off, chargeOf(off, true)).upi).toBe(false);
    const noOfferMethod = config({ localOffer: { ...config().localOffer!, methods: [] } });
    expect(sheetMethods(noOfferMethod, chargeOf(noOfferMethod, true)).upi).toBe(false);
  });
});

describe('results', () => {
  it('maps the server answers to what the merchant gets', () => {
    expect(resultFromPay({ state: 'succeeded', paymentId: 'p', transactionId: 'VT1', clientSecret: null })).toEqual({
      status: 'succeeded',
      paymentId: 'p',
      transactionId: 'VT1',
    });
    expect(resultFromPay({ state: 'processing', paymentId: 'p', clientSecret: null })).toEqual({
      status: 'processing',
      paymentId: 'p',
    });
    expect(resultFromPay({ state: 'requires_action', paymentId: 'p', clientSecret: 'pi_1_secret_x' })).toBeNull();
    expect(resultFromStatus({ state: 'open', paymentId: 'p', transactionId: null, livemode: false })).toBeNull();
    expect(resultFromStatus({ state: 'failed', paymentId: 'p', transactionId: null, livemode: false })).toMatchObject({
      status: 'failed',
      error: { code: 'declined' },
    });
  });

  it('reads the intent id only from a real client secret', () => {
    expect(intentIdOf('pi_3Abc_secret_xyZ_1')).toBe('pi_3Abc');
    expect(intentIdOf('pi_3Abc')).toBeNull();
    expect(intentIdOf(null)).toBeNull();
  });

  it('formats amounts in their currency', () => {
    expect(formatAmount(4900, 'USD', 'en-US')).toBe('$49.00');
    expect(formatAmount(420000, 'INR', 'en-IN')).toBe('₹4,200.00');
  });
});
