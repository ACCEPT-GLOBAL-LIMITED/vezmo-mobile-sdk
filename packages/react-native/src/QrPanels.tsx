import { useEffect, useState } from 'react';
import { AppState, Image, Linking, Pressable, Share, Text, TextInput, View } from 'react-native';
import type { CheckoutSession } from './CheckoutSession';
import type { SheetPalette } from './theme';
import type { QrBilling, QrPayment } from './types';
import { Banner, PrimaryButton, Pulse, type Styles } from './ui';

/** Who pays, for UPI or Pix: name and address (and the CPF or CNPJ for Pix), as the web checkout asks. */
export function QrForm(props: {
  method: 'upi' | 'pix';
  defaultName: string;
  country: string | null;
  busy: boolean;
  amount: string;
  /** The last try failed (shown above the button). */
  error: string | null;
  p: SheetPalette;
  s: Styles;
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
      <TextInput
        value={value}
        onChangeText={set}
        style={s.input}
        placeholderTextColor={p.ink3}
        accessibilityLabel={label}
        editable={!props.busy}
        {...extra}
      />
    </View>
  );

  const shown = (tried && problem) || props.error;
  return (
    <View style={{ gap: 14 }}>
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
      {!!shown && <Banner text={shown} s={s} p={p} />}
      <PrimaryButton
        lock
        label={method === 'upi' ? `Pay ${props.amount} with UPI` : `Get Pix code for ${props.amount}`}
        busy={props.busy}
        s={s}
        p={p}
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
      />
    </View>
  );
}

/** Minutes and seconds left until `expiresAt` (Unix seconds), ticking. */
function useCountdown(expiresAt: number | null | undefined) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!expiresAt) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [expiresAt]);
  if (!expiresAt) return null;
  const left = Math.max(0, Math.round(expiresAt - now / 1000));
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
}

/** The buyer pays in their bank app; we open it (UPI), show the code (Pix), and check when they come back. */
export function QrWaiting(props: {
  session: CheckoutSession;
  method: 'upi' | 'pix';
  qr: QrPayment;
  p: SheetPalette;
  s: Styles;
}) {
  const { session, method, qr, s, p } = props;
  const left = useCountdown(qr.qr?.expiresAt);

  useEffect(() => {
    if (method === 'upi' && qr.link) Linking.openURL(qr.link).catch(() => undefined);
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') void session.checkNow();
    });
    return () => sub.remove();
  }, [session, method, qr.link]);

  const steps =
    method === 'pix'
      ? ['Copy the Pix code below.', 'In your bank app, choose Pix copia e cola and paste it.', 'Confirm there. This screen updates by itself.']
      : ['Open your UPI app.', 'Approve the payment request there.', 'Come back here. This screen updates by itself.'];

  return (
    <View style={{ gap: 18 }}>
      <View style={s.status} accessibilityLiveRegion="polite">
        <Pulse color={p.accent} />
        <Text style={s.statusText}>Waiting for your payment</Text>
      </View>

      {method === 'pix' && qr.code && (
        <View style={{ gap: 10 }}>
          <View style={s.codeBox}>
            <View style={s.codeHead}>
              <Text style={s.label}>Pix code</Text>
              {left && <Text style={[s.hint, { fontVariant: ['tabular-nums'] }]}>{left === '0:00' ? 'Expired' : `Expires in ${left}`}</Text>}
            </View>
            <Text selectable style={s.code} numberOfLines={3}>
              {qr.code}
            </Text>
          </View>
          <PrimaryButton label="Copy or share code" onPress={() => Share.share({ message: qr.code! }).catch(() => undefined)} s={s} p={p} />
        </View>
      )}
      {method === 'upi' && qr.link && (
        <PrimaryButton label="Open UPI app" onPress={() => Linking.openURL(qr.link!).catch(() => undefined)} s={s} p={p} />
      )}

      <View style={{ gap: 10 }}>
        {steps.map((t, i) => (
          <View key={t} style={s.step}>
            <View style={s.stepNo}>
              <Text style={s.stepNoText}>{i + 1}</Text>
            </View>
            <Text style={s.stepText}>{t}</Text>
          </View>
        ))}
      </View>

      {!!qr.qr?.png && (
        <View style={s.qrRow}>
          <Image source={{ uri: qr.qr.png }} style={s.qr} accessibilityLabel={`${method === 'upi' ? 'UPI' : 'Pix'} QR code`} />
          <Text style={[s.hint, { flex: 1, fontSize: 13.5, lineHeight: 19 }]}>
            Paying from another phone? Scan this code in its {method === 'upi' ? 'UPI' : 'bank'} app.
          </Text>
        </View>
      )}

      <View style={{ gap: 12 }}>
        <Pressable accessibilityRole="button" onPress={() => void session.checkNow()} style={s.ghost}>
          <Text style={s.ghostText}>I’ve paid</Text>
        </Pressable>
        <Pressable accessibilityRole="button" onPress={() => session.stopWaiting()} hitSlop={8}>
          <Text style={[s.link, { fontSize: 14 }]}>Use another way to pay</Text>
        </Pressable>
      </View>
    </View>
  );
}
