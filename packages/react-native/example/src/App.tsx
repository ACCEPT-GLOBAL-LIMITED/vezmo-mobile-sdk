import { useState } from 'react';
import { SafeAreaView, StyleSheet, Text } from 'react-native';
import { PaymentElement, SDK_VERSION } from '@vezmo/react-native';

/** The Vezmo SDK example app (the full demo comes with the checkout sheet). */
export default function App() {
  const [events, setEvents] = useState<string[]>([]);
  const log = (line: string) => setEvents((e) => [...e.slice(-5), line]);
  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Vezmo SDK {SDK_VERSION}</Text>
      <PaymentElement
        configJson=""
        intentJson=""
        onLoadFailed={(m) => log(`load failed: ${m}`)}
        onConfirmationToken={(id) => log(`token ${id}`)}
        onConfirmResult={(status) => log(`result ${status}`)}
      />
      {events.map((e) => (
        <Text key={e}>{e}</Text>
      ))}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 12 },
  title: { fontSize: 18, fontWeight: '600' },
});
