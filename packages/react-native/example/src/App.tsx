import { useState } from 'react';
import { Pressable, SafeAreaView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SDK_VERSION, VezmoProvider, useVezmoCheckout, type CheckoutResult } from '@vezmo/react-native';

/**
 * The Vezmo SDK example. A real app gets the session's client token from its own server; here it comes from the
 * sandbox demo endpoint (EXPO_PUBLIC_VEZMO_DEMO_URL) or is pasted in.
 */
const DEMO_URL = process.env.EXPO_PUBLIC_VEZMO_DEMO_URL;
const API_BASE = process.env.EXPO_PUBLIC_VEZMO_API_BASE;

export default function App() {
  return (
    <VezmoProvider returnUrl="vezmoexample://vezmo" apiBase={API_BASE || undefined}>
      <Checkout />
    </VezmoProvider>
  );
}

function Checkout() {
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

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Vezmo SDK {SDK_VERSION}</Text>
      <TextInput
        testID="client-token"
        value={token}
        onChangeText={setToken}
        placeholder="Client token"
        autoCapitalize="none"
        autoCorrect={false}
        style={styles.input}
      />
      <View style={styles.row}>
        <Pressable testID="new-session" onPress={newSession} style={styles.button}>
          <Text style={styles.buttonText}>New sandbox session</Text>
        </Pressable>
        <Pressable testID="pay" onPress={pay} disabled={!token.trim()} style={[styles.button, !token.trim() && styles.off]}>
          <Text style={styles.buttonText}>Pay</Text>
        </Pressable>
      </View>
      {!!status && <Text testID="status">{status}</Text>}
      {result && <Text testID="result">Result: {JSON.stringify(result)}</Text>}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 14 },
  title: { fontSize: 20, fontWeight: '700' },
  input: { borderWidth: 1, borderColor: '#D0D3DE', borderRadius: 10, padding: 12, fontSize: 14 },
  row: { flexDirection: 'row', gap: 10 },
  button: { flex: 1, backgroundColor: '#503AF2', borderRadius: 999, paddingVertical: 14, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
  off: { opacity: 0.4 },
});
