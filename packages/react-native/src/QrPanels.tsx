import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  Linking,
  Pressable,
  Share,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import type { CheckoutSession } from './CheckoutSession';
import type { SheetPalette } from './theme';
import type { QrBilling, QrPayment } from './types';

export interface PanelStyles {
  label: StyleProp<TextStyle>;
  hint: StyleProp<TextStyle>;
  input: StyleProp<TextStyle>;
  pay: StyleProp<ViewStyle>;
  payText: StyleProp<TextStyle>;
  link: StyleProp<TextStyle>;
  error: StyleProp<TextStyle>;
}

const LABEL = { upi: 'UPI', pix: 'Pix' } as const;

/** Who pays, for UPI or Pix: name and address (and the CPF or CNPJ for Pix), as the web checkout asks. */
export function QrForm(props: {
  method: 'upi' | 'pix';
  defaultName: string;
  country: string | null;
  busy: boolean;
  amount: string;
  p: SheetPalette;
  s: PanelStyles;
  onPay(billing: QrBilling): void;
}) {
  const { method, s, p } = props;
  const [name, setName] = useState(props.defaultName);
  const [line1, setLine1] = useState('');
  const [city, setCity] = useState('');
  const [postalCode, setPostalCode] = useState('');
  const [taxId, setTaxId] = useState('');
  const digits = taxId.replace(/\D/g, '');
  const problem = !name.trim()
    ? 'Enter your name.'
    : !line1.trim()
      ? 'Enter your address.'
      : method === 'pix' && digits.length !== 11 && digits.length !== 14
        ? 'Enter your CPF (11 digits) or CNPJ (14 digits).'
        : null;
  const [tried, setTried] = useState(false);

  const field = (label: string, value: string, set: (v: string) => void, extra: object = {}) => (
    <View style={{ gap: 6 }}>
      <Text style={s.label}>{label}</Text>
      <TextInput value={value} onChangeText={set} style={s.input} placeholderTextColor={p.ink3} {...extra} />
    </View>
  );

  return (
    <View style={{ gap: 12 }}>
      {field('Full name', name, setName, { autoComplete: 'name', textContentType: 'name' })}
      {field('Address', line1, setLine1, { autoComplete: 'street-address', textContentType: 'fullStreetAddress' })}
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <View style={{ flex: 1 }}>{field('City', city, setCity, { textContentType: 'addressCity' })}</View>
        <View style={{ flex: 1 }}>
          {field('Postal code', postalCode, setPostalCode, { textContentType: 'postalCode' })}
        </View>
      </View>
      {method === 'pix' &&
        field('CPF or CNPJ', taxId, setTaxId, { keyboardType: 'number-pad', placeholder: '000.000.000-00' })}
      {tried && problem && <Text style={s.error}>{problem}</Text>}
      <Pressable
        accessibilityRole="button"
        disabled={props.busy}
        onPress={() => {
          setTried(true);
          if (problem) return;
          props.onPay({
            name: name.trim(),
            line1: line1.trim(),
            ...(city.trim() ? { city: city.trim() } : {}),
            ...(postalCode.trim() ? { postalCode: postalCode.trim() } : {}),
            ...(props.country ? { country: props.country } : {}),
            ...(method === 'pix' ? { taxId: taxId.trim() } : {}),
          });
        }}
        style={[s.pay, props.busy && { opacity: 0.45 }]}
      >
        {props.busy ? (
          <ActivityIndicator color={p.onAccent} />
        ) : (
          <Text style={s.payText}>{method === 'upi' ? `Pay ${props.amount} with UPI` : `Get Pix code for ${props.amount}`}</Text>
        )}
      </Pressable>
    </View>
  );
}

/** The buyer pays in their bank app; we open it (UPI), show the code (Pix), and check when they come back. */
export function QrWaiting(props: {
  session: CheckoutSession;
  method: 'upi' | 'pix';
  qr: QrPayment;
  p: SheetPalette;
  s: PanelStyles;
}) {
  const { session, method, qr, s, p } = props;

  useEffect(() => {
    if (method === 'upi' && qr.link) Linking.openURL(qr.link).catch(() => undefined);
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') void session.checkNow();
    });
    return () => sub.remove();
  }, [session, method, qr.link]);

  return (
    <View style={{ gap: 14, alignItems: 'stretch' }}>
      <ActivityIndicator color={p.accent} />
      <Text style={[s.label, { textAlign: 'center' }]}>
        {method === 'upi' ? 'Finish the payment in your UPI app.' : 'Pay with Pix in your bank app.'}
      </Text>
      {method === 'upi' && qr.link && (
        <Pressable accessibilityRole="button" onPress={() => Linking.openURL(qr.link!).catch(() => undefined)} style={s.pay}>
          <Text style={s.payText}>Open UPI app</Text>
        </Pressable>
      )}
      {method === 'pix' && qr.code && (
        <>
          <Text selectable style={[s.hint, { fontFamily: 'Courier', textAlign: 'center' }]} numberOfLines={4}>
            {qr.code}
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => Share.share({ message: qr.code! }).catch(() => undefined)}
            style={s.pay}
          >
            <Text style={s.payText}>Share or copy the Pix code</Text>
          </Pressable>
          <Text style={[s.hint, { textAlign: 'center' }]}>The code works for 10 minutes.</Text>
        </>
      )}
      <Pressable accessibilityRole="button" onPress={() => void session.checkNow()}>
        <Text style={[s.link, { textAlign: 'center' }]}>I’ve paid</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={() => session.stopWaiting()}>
        <Text style={[s.hint, { textAlign: 'center' }]}>Choose another way to pay ({LABEL[method]} not finished)</Text>
      </Pressable>
    </View>
  );
}
