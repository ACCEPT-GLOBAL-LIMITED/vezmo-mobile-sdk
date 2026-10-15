import { VezmoApi, VezmoApiError } from '../api';

const ok = (data: unknown) =>
  ({ ok: true, status: 200, json: async () => ({ success: true, data }) }) as unknown as Response;
const fail = (status: number, body: unknown) =>
  ({ ok: false, status, json: async () => body }) as unknown as Response;

describe('VezmoApi', () => {
  it('pays with the token, our integration and SDK header; unwraps the data', async () => {
    const fetchImpl = jest.fn().mockResolvedValue(ok({ state: 'processing', paymentId: 'p', clientSecret: null }));
    const api = new VezmoApi('https://api.test/v1', 'android', fetchImpl);
    const res = await api.pay('tok/1', { confirmationToken: 'ctoken_1', localOffer: 'o', returnUrl: 'acme://vezmo' });
    expect(res.state).toBe('processing');
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('https://api.test/v1/secure-payments/tok%2F1/pay');
    expect(init.method).toBe('POST');
    expect(init.headers['Vezmo-SDK']).toMatch(/^react-native\/\d+\.\d+\.\d+/);
    expect(JSON.parse(init.body)).toEqual({
      confirmationToken: 'ctoken_1',
      integration: 'android',
      localOffer: 'o',
      returnUrl: 'acme://vezmo',
    });
  });

  it("turns failures into Vezmo's own codes and words", async () => {
    const api = (r: Response) => new VezmoApi('https://api.test/v1', 'ios', jest.fn().mockResolvedValue(r));
    await expect(api(fail(410, { message: 'Secure-payments token has expired' })).status('t')).rejects.toMatchObject({
      code: 'expired',
    });
    await expect(
      api(fail(400, { message: 'Your bank declined this card.' })).pay('t', { confirmationToken: 'c' }),
    ).rejects.toMatchObject({ code: 'declined', message: 'Your bank declined this card.' });
    const priceChanged = (await api(
      fail(409, { message: 'Price changed', errors: [{ code: 'local_price_changed', message: 'x' }] }),
    )
      .pay('t', { confirmationToken: 'c' })
      .catch((e: unknown) => e)) as VezmoApiError;
    expect(priceChanged.reason).toBe('local_price_changed');
    const offline = new VezmoApi('https://api.test/v1', 'ios', jest.fn().mockRejectedValue(new TypeError('x')));
    await expect(offline.status('t')).rejects.toMatchObject({ code: 'network' });
  });
});
