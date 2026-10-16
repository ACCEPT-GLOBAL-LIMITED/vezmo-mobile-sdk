/**
 * A stand-in for `@vezmopay/react-native` in your app's Jest tests: no native views, no network.
 *
 *   jest.mock('@vezmopay/react-native', () => require('@vezmopay/react-native/jest/mock'));
 *
 * `presentCheckout()` resolves with a successful payment unless you choose another result:
 *
 *   const vezmo = require('@vezmopay/react-native');
 *   vezmo.mockNextResult({ status: 'canceled' });
 *   vezmo.mockPrepareError({ code: 'expired', message: 'This checkout has expired.' });
 *
 * `prepareCheckout` and `presentCheckout` are `jest.fn()`s, so you can assert on their calls. Call `mockReset()`
 * in `beforeEach` to go back to the defaults.
 */
const SUCCEEDED = { status: 'succeeded', paymentId: 'pay_mock', transactionId: 'VZMOCK01' };

let nextResult = SUCCEEDED;
let prepareError = null;

const prepareCheckout = jest.fn(async () => (prepareError ? { error: prepareError } : {}));
const presentCheckout = jest.fn(async () => nextResult);

module.exports = {
  VezmoProvider: ({ children }) => children,
  useVezmoCheckout: () => ({ prepareCheckout, presentCheckout }),
  SDK_VERSION: require('../package.json').version,
  mockNextResult(result) {
    nextResult = result;
  },
  mockPrepareError(error) {
    prepareError = error;
  },
  mockReset() {
    nextResult = SUCCEEDED;
    prepareError = null;
    prepareCheckout.mockClear();
    presentCheckout.mockClear();
  },
};
