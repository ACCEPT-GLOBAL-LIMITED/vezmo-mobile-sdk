import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { VezmoApiError } from '../api';
import { CheckoutSession } from '../CheckoutSession';
import type { CheckoutConfig } from '../types';

const config: CheckoutConfig = {
  publishableKey: 'pk_test_x',
  connectedAccountId: 'acct_1',
  livemode: false,
  amount: 49,
  amountMinor: 4900,
  currency: 'USD',
  title: 'Order',
  merchantName: 'Acme',
  enabledMethods: { card: true, klarna: true },
  bankSupported: false,
  customerName: null,
  customerEmail: null,
  theme: null,
  paymentId: 'pay_1',
  localOffer: null,
  appReturnUrl: 'acme://vezmo',
};

function setup() {
  const api = {
    checkout: jest.fn<any>().mockResolvedValue(config),
    pay: jest.fn<any>(),
    confirm: jest.fn<any>().mockResolvedValue({ payment: { id: 'pay_1', transactionId: 'VT1' } }),
    status: jest.fn<any>().mockResolvedValue({ state: 'succeeded', paymentId: 'pay_1', transactionId: 'VT1', livemode: false }),
    intent: jest.fn<any>().mockResolvedValue({ id: 'pi_7', clientSecret: 'pi_7_secret_q', connectedAccountId: 'acct_1', livemode: false }),
    startQr: jest.fn<any>().mockResolvedValue({ status: 'requires_action', qr: null, link: 'upi://pay?pa=x', code: null }),
    checkoutPageUrl: jest.fn((t: string) => `https://api.test/v1/secure-payments/${t}`),
  };
  const element = { confirm: jest.fn(), resolveConfirmation: jest.fn(), clearSelection: jest.fn() };
  const cardFlow = { presentCard: jest.fn(), confirmCard: jest.fn(), resolveConfirmation: jest.fn() };
  const session = new CheckoutSession(api as any, 'tok', 'acme://vezmo/return');
  session.attachElement(element);
  session.attachCardFlow(cardFlow);
  return { api, element, cardFlow, session };
}

describe('CheckoutSession', () => {
  let t: ReturnType<typeof setup>;
  beforeEach(async () => {
    t = setup();
    await t.session.load();
  });

  it('loads the session and offers card plus the enabled methods', () => {
    const s = t.session.getSnapshot();
    expect(s.phase).toEqual({ name: 'ready', error: null });
    expect(s.charge).toEqual({ amountMinor: 4900, currency: 'USD', localOffer: null });
    expect(s.methods?.elementTypes).toEqual(['card', 'klarna']);
  });

  it('a card paid by the server: hands the element the secret, the result is the server’s', async () => {
    const method = { type: 'card', brand: 'visa', last4: '4242', wallet: null };
    t.api.pay.mockResolvedValue({ state: 'succeeded', paymentId: 'pay_1', transactionId: 'VT1', clientSecret: 'pi_1_secret_a', method });
    t.session.setOption({ label: '•••• 4242' });
    t.session.pay();
    expect(t.element.confirm).toHaveBeenCalled();
    await t.session.onConfirmationToken('ctoken_1');
    // The receipt's Visa mark and last four come from the server's reading of the payment.
    expect(t.session.getSnapshot().paidMethod).toEqual(method);
    expect(t.api.pay).toHaveBeenCalledWith('tok', { confirmationToken: 'ctoken_1', localOffer: null, returnUrl: 'acme://vezmo/return' });
    expect(t.element.resolveConfirmation).toHaveBeenCalledWith('pi_1_secret_a', undefined);
    await t.session.onConfirmResult('completed', '');
    expect(t.session.getSnapshot().phase).toEqual({
      name: 'done',
      result: { status: 'succeeded', paymentId: 'pay_1', transactionId: 'VT1' },
    });
    expect(t.api.confirm).not.toHaveBeenCalled();
  });

  it('3D Secure finished on the device is recorded with /confirm, then read back', async () => {
    t.api.pay.mockResolvedValue({ state: 'requires_action', paymentId: 'pay_1', clientSecret: 'pi_9_secret_b' });
    t.session.pay();
    await t.session.onConfirmationToken('ctoken_1');
    await t.session.onConfirmResult('completed', '');
    expect(t.api.confirm).toHaveBeenCalledWith('tok', 'pi_9');
    expect(t.session.getSnapshot().phase).toMatchObject({ name: 'done', result: { status: 'succeeded', transactionId: 'VT1' } });
  });

  it('a decline goes back to the element in our words and leaves the sheet ready to retry', async () => {
    t.api.pay.mockRejectedValue(new VezmoApiError('declined', 'Your bank declined this card.', 400));
    t.session.pay();
    await t.session.onConfirmationToken('ctoken_1');
    expect(t.element.resolveConfirmation).toHaveBeenCalledWith(null, 'Your bank declined this card.');
    await t.session.onConfirmResult('failed', 'processor text');
    expect(t.session.getSnapshot().phase).toEqual({
      name: 'ready',
      error: { code: 'declined', message: 'Your bank declined this card.' },
    });
  });

  it('a changed local price refreshes the offer and asks the buyer to pay again', async () => {
    t.api.pay.mockRejectedValue(new VezmoApiError('unknown', 'Price changed', 409, 'local_price_changed'));
    t.session.pay();
    await t.session.onConfirmationToken('ctoken_1');
    expect(t.element.resolveConfirmation).toHaveBeenCalledWith(null, expect.stringMatching(/price in your currency/));
    expect(t.api.checkout).toHaveBeenCalledTimes(2);
  });

  it('canceling the element returns to the sheet; an expired session closes it', async () => {
    t.session.pay();
    await t.session.onConfirmResult('canceled', '');
    expect(t.session.getSnapshot().phase).toEqual({ name: 'ready', error: null });

    t.api.pay.mockRejectedValue(new VezmoApiError('expired', 'Secure-payments token has expired', 410));
    t.session.pay();
    await t.session.onConfirmationToken('ctoken_2');
    expect(t.session.getSnapshot().phase).toMatchObject({ name: 'closed', result: { status: 'failed', error: { code: 'expired' } } });
  });

  it("the buyer's own currency: the charge and methods follow the choice", async () => {
    const local = {
      ...config,
      localOffer: {
        currency: 'INR', amount: 4200, amountMinor: 420000, price: 49, rate: 85.7, markupPercent: 2,
        usdPerUnit: 0.0117, expiresAt: '2026-10-16T00:00:00Z', country: 'IN', methods: ['upi'], token: 'offer',
      },
    };
    const u = setup();
    u.api.checkout.mockResolvedValue(local);
    await u.session.load();
    expect(u.session.getSnapshot().charge).toEqual({ amountMinor: 420000, currency: 'INR', localOffer: 'offer' });
    expect(u.session.getSnapshot().methods?.elementTypes).toEqual(['card']);
    u.session.choosePayLocal(false);
    expect(u.session.getSnapshot().charge?.currency).toBe('USD');
    expect(u.session.getSnapshot().methods?.elementTypes).toEqual(['card', 'klarna']);
  });

  it('one method at a time: UPI unselects the element, picking an element method leaves UPI', () => {
    t.session.setOption({ label: 'Visa •••• 4242' });
    expect(t.session.getSnapshot()).toMatchObject({ canPay: true, option: 'Visa •••• 4242', method: 'element' });
    t.session.chooseMethod('upi');
    expect(t.element.clearSelection).toHaveBeenCalled();
    t.session.setOption(null); // the element reports the cleared selection
    expect(t.session.getSnapshot()).toMatchObject({ canPay: false, option: null, method: 'upi' });
    t.session.setOption({ label: 'Klarna' });
    expect(t.session.getSnapshot()).toMatchObject({ canPay: true, option: 'Klarna', method: 'element' });
    // The receipt keeps what was paid with, though the element clears its selection after paying.
    t.session.pay();
    t.session.setOption(null);
    expect(t.session.getSnapshot().paidWith).toBe('Klarna');
  });

  it('the Card row: opens the card form, the typed card becomes the selection, and pays through the card form', () => {
    t.session.chooseCard();
    expect(t.cardFlow.presentCard).toHaveBeenCalled();
    expect(CheckoutSession.canPay(t.session.getSnapshot())).toBe(false);
    const card = { label: '•••• 4242', image: 'data:image/png;base64,AA==' };
    t.session.setCard(card);
    expect(t.session.getSnapshot()).toMatchObject({ method: 'card', card });
    expect(t.element.clearSelection).toHaveBeenCalled();
    // Another method in the element leaves the card row but keeps the card for later.
    t.session.setOption({ label: 'Klarna' });
    expect(t.session.getSnapshot()).toMatchObject({ method: 'element', card });
    // A re-sent card (the amount changed) does not take the selection back.
    t.session.setCard(card);
    expect(t.session.getSnapshot().method).toBe('element');
    t.session.chooseCard();
    expect(t.session.getSnapshot().method).toBe('card');
    t.session.pay();
    expect(t.cardFlow.confirmCard).toHaveBeenCalled();
    expect(t.element.confirm).not.toHaveBeenCalled();
    expect(t.session.getSnapshot().paidWith).toBe('•••• 4242');
  });

  it('the card flow answers its own confirmation (card form or wallet), the element its own', async () => {
    t.api.pay.mockResolvedValue({ state: 'requires_action', paymentId: 'pay_1', clientSecret: 'pi_1_secret_w' });
    t.session.walletStarted('apple_pay');
    expect(t.session.getSnapshot()).toMatchObject({ phase: { name: 'paying' }, paidWith: 'Apple Pay' });
    await t.session.onConfirmationToken('ctoken_w');
    expect(t.cardFlow.resolveConfirmation).toHaveBeenCalledWith('pi_1_secret_w', undefined);
    expect(t.element.resolveConfirmation).not.toHaveBeenCalled();
  });

  it('UPI: starts on the session intent, waits for the bank app, records it when paid', async () => {
    const local = {
      ...config,
      enabledMethods: { card: true, upi: true },
      localOffer: {
        currency: 'INR', amount: 4200, amountMinor: 420000, price: 49, rate: 85.7, markupPercent: 2,
        usdPerUnit: 0.0117, expiresAt: '2026-10-16T00:00:00Z', country: 'IN', methods: ['upi'], token: 'offer',
      },
    };
    const u = setup();
    u.api.checkout.mockResolvedValue(local);
    await u.session.load();
    expect(u.session.getSnapshot().methods?.upi).toBe(true);
    u.session.chooseMethod('upi');
    u.api.status.mockResolvedValueOnce({ state: 'requires_action', paymentId: 'pay_1', transactionId: null, livemode: false });

    await u.session.payWithQr('upi', { name: 'Asha Rao', line1: '12 MG Road', country: 'IN' });
    expect(u.api.intent).toHaveBeenCalledWith('tok', 'offer');
    expect(u.api.startQr).toHaveBeenCalledWith('upi', {
      clientSecret: 'pi_7_secret_q',
      connectedAccountId: 'acct_1',
      livemode: false,
      returnUrl: 'https://api.test/v1/secure-payments/tok',
      billing: { name: 'Asha Rao', line1: '12 MG Road', country: 'IN' },
    });
    expect(u.session.getSnapshot().phase).toMatchObject({ name: 'waiting', method: 'upi', qr: { link: 'upi://pay?pa=x' } });

    await u.session.checkNow(); // still in the bank app
    expect(u.session.getSnapshot().phase.name).toBe('waiting');
    await u.session.checkNow(); // paid
    expect(u.api.confirm).toHaveBeenCalledWith('tok', 'pi_7');
    expect(u.session.getSnapshot().phase).toMatchObject({ name: 'done', result: { status: 'succeeded' } });
    u.session.dispose();
  });
});
