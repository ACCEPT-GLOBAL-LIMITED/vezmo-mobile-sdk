import { afterEach, beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { VezmoProvider, useVezmoCheckout } from '../VezmoProvider';
import type { CheckoutConfig, CheckoutResult } from '../types';

// The sheet itself (native views, animations) is not what is tested here: the provider's bookkeeping is.
jest.mock('../CheckoutSheet', () => ({ CheckoutSheet: () => null }));

const config: CheckoutConfig = {
  publishableKey: 'pk_test_x',
  connectedAccountId: 'acct_1',
  livemode: false,
  amount: 49,
  amountMinor: 4900,
  currency: 'USD',
  title: 'Order',
  merchantName: 'Acme',
  enabledMethods: { card: true },
  bankSupported: false,
  customerName: null,
  customerEmail: null,
  theme: null,
  paymentId: 'pay_1',
  localOffer: null,
  appReturnUrl: null,
};

let checkout: ReturnType<typeof useVezmoCheckout>;
function App() {
  checkout = useVezmoCheckout();
  return null;
}

describe('VezmoProvider', () => {
  const realFetch = global.fetch;
  beforeEach(() => {
    global.fetch = jest.fn(async () => ({ ok: true, status: 200, json: async () => ({ success: true, data: config }) })) as any;
  });
  afterEach(() => {
    global.fetch = realFetch;
  });

  it('one sheet at a time, and every presentCheckout() ends, even when the provider goes away', async () => {
    let root: ReactTestRenderer;
    await act(async () => {
      root = create(
        <VezmoProvider>
          <App />
        </VezmoProvider>,
      );
    });
    await act(async () => {
      expect(await checkout.prepareCheckout({ clientToken: 'tok' })).toEqual({});
    });
    let first!: Promise<CheckoutResult>;
    await act(async () => {
      first = checkout.presentCheckout();
    });
    // A second call while the sheet is open answers at once instead of leaving the first one waiting forever.
    await expect(checkout.presentCheckout()).resolves.toEqual({
      status: 'failed',
      error: { code: 'config', message: 'The checkout is already open.' },
    });
    // The app left the screen with the sheet open: its await ends as a cancel.
    await act(async () => root.unmount());
    await expect(first).resolves.toEqual({ status: 'canceled' });
  });
});
