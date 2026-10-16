/**
 * The package's public surface. A change here is a change for every merchant: update this list on purpose, with a
 * CHANGELOG entry (and a major version for a removal or rename).
 */
import { beforeEach, describe, expect, it } from '@jest/globals';
import * as vezmo from '../index';

const mock = require('../../jest/mock');

describe('public API', () => {
  it('exports exactly these names', () => {
    expect(Object.keys(vezmo).sort()).toEqual(['SDK_VERSION', 'VezmoProvider', 'useVezmoCheckout']);
  });

  it('keeps SDK_VERSION equal to the package version', () => {
    expect(vezmo.SDK_VERSION).toBe(require('../../package.json').version);
  });
});

describe('jest mock', () => {
  beforeEach(() => mock.mockReset());

  it('has every export of the real package', () => {
    for (const name of Object.keys(vezmo)) expect(mock).toHaveProperty(name);
  });

  it('succeeds by default, and returns the result or prepare error chosen', async () => {
    const { prepareCheckout, presentCheckout } = mock.useVezmoCheckout();
    expect(await prepareCheckout({ clientToken: 'tok' })).toEqual({});
    expect(await presentCheckout()).toMatchObject({ status: 'succeeded' });

    mock.mockNextResult({ status: 'canceled' });
    mock.mockPrepareError({ code: 'expired', message: 'This checkout has expired.' });
    expect(await prepareCheckout({ clientToken: 'tok' })).toEqual({
      error: { code: 'expired', message: 'This checkout has expired.' },
    });
    expect(await presentCheckout()).toEqual({ status: 'canceled' });
    expect(presentCheckout).toHaveBeenCalledTimes(2);
  });
});
