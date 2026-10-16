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
  };
  const element = { confirm: jest.fn(), resolveConfirmation: jest.fn() };
  const session = new CheckoutSession(api as any, 'tok', 'acme://vezmo/return');
  session.attachElement(element);
  return { api, element, session };
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
    t.api.pay.mockResolvedValue({ state: 'succeeded', paymentId: 'pay_1', transactionId: 'VT1', clientSecret: 'pi_1_secret_a' });
    t.session.pay();
    expect(t.element.confirm).toHaveBeenCalled();
    await t.session.onConfirmationToken('ctoken_1');
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
});
