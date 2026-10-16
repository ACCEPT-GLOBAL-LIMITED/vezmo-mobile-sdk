import { useState } from 'react';
import { Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SDK_VERSION, VezmoProvider, useVezmoCheckout, type CheckoutResult } from '@vezmo/react-native';

/**
 * The Vezmo SDK example: a small shop with one product and a Buy button that opens the Vezmo sheet. A real app gets
 * the session's client token from its own server; here it comes from the sandbox demo endpoint
 * (EXPO_PUBLIC_VEZMO_DEMO_URL) or is pasted in under Sandbox.
 */
const DEMO_URL = process.env.EXPO_PUBLIC_VEZMO_DEMO_URL;
const API_BASE = process.env.EXPO_PUBLIC_VEZMO_API_BASE;

export default function App() {
  return (
    <VezmoProvider returnUrl="vezmoexample://vezmo" apiBase={API_BASE || undefined}>
      <Shop />
    </VezmoProvider>
  );
}

function Shop() {
  const { prepareCheckout, presentCheckout } = useVezmoCheckout();
  const [token, setToken] = useState('');
  const [status, setStatus] = useState('');
  const [result, setResult] = useState<CheckoutResult | null>(null);

  const newSession = async () => {
    if (!DEMO_URL) return setStatus('Paste a client token, or set EXPO_PUBLIC_VEZMO_DEMO_URL.');
    setStatus('Creating a sandbox session…');
    try {
      const res = await fetch(DEMO_URL, { method: 'POST' });
      const json = await res.json();
      setToken(json?.data?.clientToken ?? '');
      setStatus('Session ready.');
    } catch {
      setStatus('Could not reach the demo endpoint.');
    }
  };

  const pay = async () => {
    setResult(null);
    setStatus('Loading checkout…');
    const prep = await prepareCheckout({ clientToken: token.trim() });
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
          <Pressable testID="new-session" onPress={newSession} style={styles.secondary}>
            <Text style={styles.secondaryText}>New sandbox session</Text>
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
  photo: { height: 210, borderRadius: 14, backgroundColor: '#E9E7F7', alignItems: 'center', justifyContent: 'center' },
  // A pair of headphones, drawn: the band and two cups.
  band: { width: 112, height: 70, borderTopLeftRadius: 60, borderTopRightRadius: 60, borderWidth: 9, borderBottomWidth: 0, borderColor: '#2B2D3A' },
  cups: { flexDirection: 'row', gap: 74, marginTop: -16 },
  cup: { width: 28, height: 48, borderRadius: 12, backgroundColor: '#2B2D3A' },
  details: { gap: 4, paddingHorizontal: 4 },
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
