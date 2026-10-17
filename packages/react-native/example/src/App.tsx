import { useEffect, useState } from 'react';
import {
  Linking,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  Settings,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SDK_VERSION, VezmoProvider, useVezmoCheckout, type CheckoutResult } from '@vezmopay/react-native';

/**
 * The Vezmo SDK example: a small shop with one product and a Buy button that opens the Vezmo sheet. A real app gets
 * the session's client token from its own server; here it comes from the sandbox demo endpoint or is pasted in under
 * Sandbox.
 */
// Vezmo's dev sandbox (TEST mode only): its demo endpoints make sessions with no key in the app.
// EXPO_PUBLIC_VEZMO_API_BASE and EXPO_PUBLIC_VEZMO_DEMO_URL point it elsewhere.
const API_BASE = process.env.EXPO_PUBLIC_VEZMO_API_BASE || 'https://api.dev.vezmo.com/api/v1';
const DEMO_URL = process.env.EXPO_PUBLIC_VEZMO_DEMO_URL || `${API_BASE}/demo/mobile-session`;
// The sandbox's subscription checkout (same demo business): …/demo/mobile-subscription.
const SUBSCRIPTION_URL = DEMO_URL.replace(/mobile-session$/, 'mobile-subscription');

// The end-to-end runs give the app `track`, the sandbox business (`mor`, the merchant-of-record one, or `connect`), and
// `offerCurrency`, which has the sandbox offer the buyer that currency, as it would abroad (the CI runners are in the
// US). On iPhone they are launch arguments (`-track mor`), which iOS keeps in the app's settings; on Android the run
// opens vezmoexample://e2e?track=mor&offerCurrency=AED.
type Launch = { track?: string; offerCurrency?: string };
const launchOf = (track?: string, offerCurrency?: string): Launch | null =>
  track || offerCurrency ? { track: track?.toLowerCase(), offerCurrency: offerCurrency?.toUpperCase() } : null;
const readLaunch = (url?: string | null): Launch | null => {
  if (!url || !/^vezmoexample:\/\/e2e\b/.test(url)) return null;
  const param = (name: string) => new RegExp(`[?&]${name}=([A-Za-z]+)\\b`).exec(url)?.[1];
  return launchOf(param('track'), param('offerCurrency')) ?? {};
};
const setting = (name: string) => {
  const value: unknown = Platform.OS === 'ios' ? Settings.get(name) : undefined;
  return typeof value === 'string' && value ? value : undefined;
};
const launchArguments = launchOf(setting('track'), setting('offerCurrency'));
// The run's settings, shown on the status line: the run waits for them before it taps anything.
const runStatus = (launch: Launch) =>
  `Run: ${launch.track ?? 'connect'}${launch.offerCurrency ? `, ${launch.offerCurrency}` : ''}.`;

// A demo endpoint's address on a business (`mor` or not), with the offer currency the run asked for.
const demoUrl = (base: string, track: string | undefined, launch: Launch) => {
  const query = [track === 'mor' && 'track=mor', launch.offerCurrency && `offerCurrency=${launch.offerCurrency}`];
  const params = query.filter(Boolean).join('&');
  return params ? `${base}${base.includes('?') ? '&' : '?'}${params}` : base;
};

// The sandbox's demo endpoints, with a short timeout and up to three tries (a CI runner's first requests can stall,
// and the sandbox restarts now and then); `show` puts each try on screen. The token in `field`, or null.
async function demoToken(url: string, field: string, label: string, show: (status: string) => void) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    show(attempt === 1 ? label : `${label} (try ${attempt} of 3)`);
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 15000);
    try {
      const res = await fetch(url, { method: 'POST', signal: abort.signal });
      const token = res.ok ? (await res.json())?.data?.[field] : undefined;
      if (typeof token === 'string' && token) return token;
    } catch {
      // Timed out or unreachable: try again.
    } finally {
      clearTimeout(timer);
    }
    if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, attempt * 3000));
  }
  return null;
}

export default function App() {
  return (
    <VezmoProvider
      returnUrl="vezmoexample://vezmo"
      apiBase={API_BASE}
      // The app's own Apple Merchant ID (Apple Developer → Identifiers → Merchant IDs): Apple Pay shows only with it.
      applePay={{ merchantId: 'merchant.com.vezmo.example' }}
    >
      <Shop />
    </VezmoProvider>
  );
}

function Shop() {
  const { prepareCheckout, presentCheckout } = useVezmoCheckout();
  const [token, setToken] = useState('');
  const [status, setStatus] = useState(launchArguments ? runStatus(launchArguments) : '');
  const [result, setResult] = useState<CheckoutResult | null>(null);
  const [launch, setLaunch] = useState<Launch>(launchArguments ?? {});
  useEffect(() => {
    const take = (url?: string | null) => {
      const read = readLaunch(url);
      if (!read) return;
      setLaunch(read);
      setStatus(runStatus(read));
    };
    Linking.getInitialURL()
      .then(take)
      .catch(() => undefined);
    const subscription = Linking.addEventListener('url', ({ url }) => take(url));
    return () => subscription.remove();
  }, []);
  // The business of the sandbox buttons that name none (the run's `track`, else Connect), and of "Subscribe, no
  // trial" (the run's `track`, else merchant of record).
  const track = launch.track;
  const noTrialTrack = track ?? 'mor';

  // `mor`: a session on the sandbox's merchant-of-record business (it has Cash App, Klarna and Amazon Pay on).
  const newSession = async (sessionTrack?: string) => {
    const url = demoUrl(DEMO_URL, sessionTrack, launch);
    const created = await demoToken(url, 'clientToken', 'Creating a sandbox session…', setStatus);
    if (created) setToken(created);
    setStatus(created ? 'Session ready.' : 'Could not reach the demo endpoint.');
  };

  const pay = async () => {
    setResult(null);
    setStatus('Loading checkout…');
    const prep = await prepareCheckout({ clientToken: token.trim() });
    if (prep.error) return setStatus(`Could not load: ${prep.error.message}`);
    setStatus('');
    setResult(await presentCheckout());
  };

  // A real app's server creates the subscription (POST /merchant/subscription) and returns its checkoutToken.
  const subscribe = async (subscriptionTrack: string | undefined, trial: boolean) => {
    setResult(null);
    const url = demoUrl(trial ? `${SUBSCRIPTION_URL}?trial=1` : SUBSCRIPTION_URL, subscriptionTrack, launch);
    const subscriptionToken = await demoToken(url, 'subscriptionToken', 'Loading checkout…', setStatus);
    if (!subscriptionToken) return setStatus('Could not reach the demo endpoint.');
    const prep = await prepareCheckout({ subscriptionToken });
    if (prep.error) return setStatus(`Could not load: ${prep.error.message}`);
    setStatus('');
    setResult(await presentCheckout());
  };

  const ready = !!token.trim();
  return (
    <SafeAreaView style={styles.page}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.store}>Northwind Audio</Text>

        <View style={styles.product}>
          <View style={styles.photo}>
            <View style={styles.band} />
            <View style={styles.cups}>
              <View style={styles.cup} />
              <View style={styles.cup} />
            </View>
          </View>
          <View style={styles.details}>
            <Text style={styles.name}>Aurora wireless headphones</Text>
            <Text style={styles.price}>$49.00</Text>
          </View>
          <Pressable testID="pay" onPress={pay} disabled={!ready} style={[styles.buy, !ready && styles.off]}>
            <Text style={styles.buyText}>Buy now</Text>
          </Pressable>
        </View>

        {!!status && (
          <Text testID="status" style={styles.status}>
            {status}
          </Text>
        )}
        {result && (
          <Text testID="result" style={styles.result}>
            Result: {JSON.stringify(result)}
          </Text>
        )}

        <View style={styles.sandbox}>
          <Text style={styles.sandboxTitle}>Sandbox · SDK {SDK_VERSION}</Text>
          <TextInput
            testID="client-token"
            value={token}
            onChangeText={setToken}
            placeholder="Client token from your server"
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.input}
          />
          <Pressable testID="new-session" onPress={() => newSession(track)} style={styles.secondary}>
            <Text style={styles.secondaryText}>New sandbox session{track === 'mor' ? ' · merchant of record' : ''}</Text>
          </Pressable>
          <Pressable testID="new-session-mor" onPress={() => newSession('mor')} style={styles.secondary}>
            <Text style={styles.secondaryText}>New session · merchant of record</Text>
          </Pressable>
          <Pressable testID="subscribe-now" onPress={() => subscribe(noTrialTrack, false)} style={styles.secondary}>
            <Text style={styles.secondaryText}>
              Subscribe, no trial{noTrialTrack === 'mor' ? ' · merchant of record' : ''}
            </Text>
          </Pressable>
        </View>

        <View style={styles.product}>
          <View style={styles.clubRow}>
            <View style={styles.clubMark}>
              <View style={styles.clubDisc} />
            </View>
            <View style={styles.details}>
              <Text style={styles.name}>Northwind Audio Club</Text>
              <Text style={styles.price}>$9.99 / month · first 7 days free</Text>
            </View>
          </View>
          <Pressable testID="subscribe" onPress={() => subscribe(track, true)} style={styles.buy}>
            <Text style={styles.buyText}>Try 7 days free</Text>
          </Pressable>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#F4F5F8' },
  content: { padding: 20, paddingTop: Platform.OS === 'android' ? 44 : 12, gap: 16 },
  store: { fontSize: 28, fontWeight: '700', letterSpacing: -0.5, color: '#0B0C14' },
  product: { backgroundColor: '#FFFFFF', borderRadius: 22, padding: 14, gap: 14, boxShadow: '0 1px 2px rgba(16,24,64,0.06)' },
  photo: { height: 150, borderRadius: 14, backgroundColor: '#E9E7F7', alignItems: 'center', justifyContent: 'center' },
  // A pair of headphones, drawn: the band and two cups.
  band: { width: 112, height: 70, borderTopLeftRadius: 60, borderTopRightRadius: 60, borderWidth: 9, borderBottomWidth: 0, borderColor: '#2B2D3A' },
  cups: { flexDirection: 'row', gap: 74, marginTop: -16 },
  cup: { width: 28, height: 48, borderRadius: 12, backgroundColor: '#2B2D3A' },
  details: { gap: 4, paddingHorizontal: 4, flexShrink: 1 },
  clubRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  clubMark: { width: 56, height: 56, borderRadius: 14, backgroundColor: '#E9E7F7', alignItems: 'center', justifyContent: 'center' },
  clubDisc: { width: 26, height: 26, borderRadius: 13, borderWidth: 7, borderColor: '#2B2D3A' },
  name: { fontSize: 17, fontWeight: '600', color: '#0B0C14' },
  price: { fontSize: 15, color: '#5A5F70' },
  buy: { height: 52, borderRadius: 999, backgroundColor: '#0B0C14', alignItems: 'center', justifyContent: 'center' },
  buyText: { color: '#FFFFFF', fontSize: 16, fontWeight: '600' },
  off: { opacity: 0.35 },
  status: { color: '#5A5F70', fontSize: 14, textAlign: 'center' },
  result: { color: '#0B0C14', fontSize: 13, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },
  sandbox: { gap: 10, padding: 14, borderRadius: 16, borderWidth: 1, borderColor: '#E1E3EC', borderStyle: 'dashed' },
  sandboxTitle: { fontSize: 12, fontWeight: '600', color: '#8A8FA0', letterSpacing: 0.4, textTransform: 'uppercase' },
  input: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#E1E3EC', borderRadius: 12, paddingHorizontal: 12, height: 44, fontSize: 14 },
  secondary: { height: 44, borderRadius: 999, backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#E1E3EC', alignItems: 'center', justifyContent: 'center' },
  secondaryText: { color: '#0B0C14', fontSize: 14, fontWeight: '600' },
});
