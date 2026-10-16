import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import type { CheckoutSession } from './CheckoutSession';
import { elementConfigJson, elementIntentJson, type WalletOptions } from './elementConfig';
import { formatAmount } from './flow';
import { PaymentElement, type PaymentElementHandle } from './PaymentElement';
import { QrForm, QrWaiting } from './QrPanels';
import { paletteFor, type SheetPalette, type VezmoAppearance } from './theme';
import type { CheckoutResult } from './types';

/** Shown instead of a technical message when the payment methods can't load (a network hiccup, usually). */
const LOAD_FAILED = 'We couldn’t load the ways to pay. Check your connection and try again.';

export interface CheckoutSheetProps {
  session: CheckoutSession;
  merchantDisplayName?: string;
  returnUrl: string | null;
  style?: 'automatic' | 'light' | 'dark';
  appearance?: VezmoAppearance;
  wallets?: WalletOptions;
  /** The sheet is finished: the buyer closed it, or tapped Done on the result. */
  onClose(result: CheckoutResult): void;
}

/**
 * The Vezmo payment sheet: the merchant and amount, the buyer's currency, the native payment element, our Pay
 * button and our result screens. Drawn as an overlay in the app's own window (the element's native UI needs it).
 */
export function CheckoutSheet(props: CheckoutSheetProps) {
  const { session } = props;
  const snap = useSyncExternalStore(session.subscribe, session.getSnapshot);
  const element = useRef<PaymentElementHandle>(null);
  const systemScheme = useColorScheme();
  const scheme: 'light' | 'dark' =
    props.style === 'light' || props.style === 'dark'
      ? props.style
      : snap.config?.theme === 'light' || snap.config?.theme === 'dark'
        ? snap.config.theme
        : systemScheme === 'dark'
          ? 'dark'
          : 'light';
  const p = useMemo(() => paletteFor(scheme, props.appearance), [scheme, props.appearance]);
  const s = useMemo(() => styles(p, props.appearance?.cornerRadius ?? 12, props.appearance?.fontFamily), [p, props.appearance]);

  useEffect(() => {
    session.attachElement({
      confirm: () => element.current?.confirm(),
      resolveConfirmation: (secret, message) => element.current?.resolveConfirmation(secret, message),
    });
    return () => {
      session.attachElement(null);
      session.dispose();
    };
  }, [session]);

  const { config, charge, methods, phase } = snap;
  const merchant = props.merchantDisplayName ?? config?.merchantName ?? '';
  const configJson = useMemo(
    () =>
      config && charge && methods
        ? elementConfigJson({
            config,
            methods,
            merchantDisplayName: merchant,
            returnUrl: props.returnUrl,
            scheme,
            palette: p,
            appearance: props.appearance,
            wallets: props.wallets,
            charge,
            attempt: snap.elementAttempt,
          })
        : '',
    // The element is rebuilt only for new keys, wallets or look, never for a new amount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [config?.publishableKey, config?.connectedAccountId, methods?.applePay, methods?.googlePay, charge?.currency, scheme, p, merchant, props.returnUrl, snap.elementAttempt],
  );
  const intentJson = useMemo(() => (charge && methods ? elementIntentJson(charge, methods) : ''), [charge, methods]);

  const close = (result: CheckoutResult) => props.onClose(result);
  const amount = charge ? formatAmount(charge.amountMinor, charge.currency) : '';
  const paying = phase.name === 'paying';

  return (
    <View style={s.scrim} accessibilityViewIsModal>
      <View style={s.sheet}>
        <View style={s.header}>
          <View style={s.headerText}>
            <Text style={s.merchant} numberOfLines={1}>
              {merchant}
            </Text>
            {!!amount && phase.name !== 'done' && <Text style={s.amount}>{amount}</Text>}
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close"
            disabled={paying}
            onPress={() => close(phase.name === 'done' || phase.name === 'closed' ? phase.result : { status: 'canceled' })}
            style={s.close}
            hitSlop={12}
          >
            <Text style={s.closeText}>✕</Text>
          </Pressable>
        </View>

        {phase.name === 'loading' && (
          <View style={s.center}>
            <ActivityIndicator color={p.accent} />
          </View>
        )}

        {(phase.name === 'done' || phase.name === 'closed') && (
          <ResultView result={phase.result} amount={amount} s={s} onDone={() => close(phase.result)} />
        )}

        {phase.name === 'waiting' && (
          <View style={s.body}>
            <QrWaiting session={session} method={phase.method} qr={phase.qr} p={p} s={s} />
          </View>
        )}

        {(phase.name === 'ready' || phase.name === 'paying') && config && charge && methods && (
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={s.body}>
            {config.localOffer && (
              <View style={s.currencyRow} accessibilityRole="radiogroup" accessibilityLabel="Pay in">
                <Text style={s.label}>Pay in</Text>
                <View style={s.pills}>
                  {[true, false].map((local) => {
                    const c = local
                      ? { minor: config.localOffer!.amountMinor, cur: config.localOffer!.currency }
                      : { minor: config.amountMinor, cur: config.currency };
                    const on = snap.payLocal === local;
                    return (
                      <Pressable
                        key={String(local)}
                        accessibilityRole="radio"
                        accessibilityState={{ checked: on, disabled: paying }}
                        disabled={paying}
                        onPress={() => session.choosePayLocal(local)}
                        style={[s.pill, on && s.pillOn]}
                      >
                        <Text style={[s.pillText, on && s.pillTextOn]}>{formatAmount(c.minor, c.cur)}</Text>
                      </Pressable>
                    );
                  })}
                </View>
                {snap.payLocal && (
                  <Text style={s.hint}>
                    1 {config.currency} = {config.localOffer.rate.toFixed(4)} {config.localOffer.currency}
                  </Text>
                )}
              </View>
            )}

            {(methods.upi || methods.pix) && (
              <View style={s.pills} accessibilityRole="tablist">
                {(['element', methods.upi ? 'upi' : 'pix'] as const).map((m) => {
                  const on = snap.method === m;
                  return (
                    <Pressable
                      key={m}
                      accessibilityRole="tab"
                      accessibilityState={{ selected: on, disabled: paying }}
                      disabled={paying}
                      onPress={() => session.chooseMethod(m)}
                      style={[s.pill, on && s.pillOn]}
                    >
                      <Text style={[s.pillText, on && s.pillTextOn]}>
                        {m === 'element' ? 'Card' : m === 'upi' ? 'UPI' : 'Pix'}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            )}

            {snap.method === 'element' ? (
              <>
              <PaymentElement
                ref={element}
                configJson={configJson}
                intentJson={intentJson}
                onPaymentOptionChange={(option) => session.setCanPay(!!option)}
                onLoaded={() => session.setElementError(null)}
                onLoadFailed={(message) => session.setElementError(message || LOAD_FAILED)}
                onConfirmationToken={(id) => void session.onConfirmationToken(id)}
                onConfirmResult={(status, message) => void session.onConfirmResult(status, message)}
              />

              {!!snap.elementError && (
                <View style={{ gap: 8, alignItems: 'flex-start' }}>
                  <Text testID="vezmo-element-error" style={s.error}>
                    {LOAD_FAILED}
                  </Text>
                  <Pressable testID="vezmo-retry" accessibilityRole="button" onPress={() => session.retryElement()}>
                    <Text style={s.link}>Try again</Text>
                  </Pressable>
                </View>
              )}

              {phase.name === 'ready' && phase.error && (
                <Text style={s.error} accessibilityLiveRegion="polite">
                  {phase.error.message}
                </Text>
              )}

              <Pressable
                accessibilityRole="button"
                accessibilityState={{ disabled: !snap.canPay || paying, busy: paying }}
                disabled={!snap.canPay || paying}
                testID="vezmo-pay"
                onPress={() => session.pay()}
                style={[s.pay, (!snap.canPay || paying) && s.payDisabled]}
              >
                {paying ? <ActivityIndicator color={p.onAccent} /> : <Text style={s.payText}>Pay {amount}</Text>}
              </Pressable>
              </>
            ) : (
              <>
                {phase.name === 'ready' && phase.error && <Text style={s.error}>{phase.error.message}</Text>}
                <QrForm
                  method={snap.method}
                  defaultName={config.customerName ?? ''}
                  country={config.localOffer?.country ?? null}
                  busy={paying}
                  amount={amount}
                  p={p}
                  s={s}
                  onPay={(billing) => void session.payWithQr(snap.method as 'upi' | 'pix', billing)}
                />
              </>
            )}
            <Text style={s.secured}>Payments secured by Vezmo{config.livemode ? '' : ' · Test mode'}</Text>
          </ScrollView>
        )}
      </View>
    </View>
  );
}

function ResultView(props: { result: CheckoutResult; amount: string; s: ReturnType<typeof styles>; onDone(): void }) {
  const { result, s } = props;
  const copy =
    result.status === 'succeeded'
      ? { title: 'Payment successful', body: props.amount, tone: s.good }
      : result.status === 'processing'
        ? { title: 'Payment received', body: 'Your payment is on its way. You’ll get a confirmation when it clears.', tone: s.good }
        : result.status === 'failed'
          ? { title: result.error.code === 'already_paid' ? 'Already paid' : 'Payment not completed', body: result.error.message, tone: s.bad }
          : { title: 'Payment canceled', body: '', tone: s.bad };
  return (
    <View style={s.center}>
      <Text testID="vezmo-result-title" style={[s.resultTitle, copy.tone]} accessibilityRole="header">
        {copy.title}
      </Text>
      {!!copy.body && <Text style={s.resultBody}>{copy.body}</Text>}
      <Pressable testID="vezmo-done" accessibilityRole="button" onPress={props.onDone} style={s.pay}>
        <Text style={s.payText}>Done</Text>
      </Pressable>
    </View>
  );
}

function styles(p: SheetPalette, radius: number, fontFamily?: string) {
  const font = fontFamily ? { fontFamily } : {};
  return StyleSheet.create({
    scrim: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      backgroundColor: p.scrim,
      justifyContent: 'flex-end',
    },
    sheet: {
      backgroundColor: p.surface,
      borderTopLeftRadius: 24,
      borderTopRightRadius: 24,
      maxHeight: '92%',
      paddingBottom: 24,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      paddingHorizontal: 20,
      paddingTop: 20,
      paddingBottom: 12,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: p.line,
    },
    headerText: { flex: 1, gap: 2 },
    merchant: { ...font, color: p.ink2, fontSize: 14, fontWeight: '500' },
    amount: { ...font, color: p.ink, fontSize: 30, fontWeight: '700', fontVariant: ['tabular-nums'], letterSpacing: -0.6 },
    close: { width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: p.field },
    closeText: { color: p.ink2, fontSize: 14 },
    body: { padding: 20, gap: 16 },
    center: { padding: 32, alignItems: 'center', gap: 12 },
    currencyRow: { gap: 8 },
    label: { ...font, color: p.ink2, fontSize: 13, fontWeight: '500' },
    pills: { flexDirection: 'row', gap: 8 },
    pill: {
      flex: 1,
      borderRadius: radius,
      borderWidth: 1,
      borderColor: p.fieldLine,
      backgroundColor: p.field,
      paddingVertical: 12,
      alignItems: 'center',
    },
    pillOn: { borderColor: p.accent, borderWidth: 2 },
    pillText: { ...font, color: p.ink, fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
    pillTextOn: { color: p.accent },
    hint: { ...font, color: p.ink3, fontSize: 12.5 },
    error: { ...font, color: p.bad, fontSize: 14 },
    pay: {
      alignSelf: 'stretch',
      height: 54,
      borderRadius: 999,
      backgroundColor: p.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    payDisabled: { opacity: 0.45 },
    payText: { ...font, color: p.onAccent, fontSize: 16, fontWeight: '600' },
    secured: { ...font, color: p.ink3, fontSize: 12, textAlign: 'center' },
    input: {
      ...font,
      borderWidth: 1,
      borderColor: p.fieldLine,
      backgroundColor: p.field,
      borderRadius: radius,
      paddingHorizontal: 14,
      height: 48,
      color: p.ink,
      fontSize: 15,
    },
    link: { ...font, color: p.accent, fontSize: 15, fontWeight: '600' },
    resultTitle: { ...font, fontSize: 21, fontWeight: '700', textAlign: 'center' },
    resultBody: { ...font, color: p.ink2, fontSize: 15, textAlign: 'center' },
    good: { color: p.good },
    bad: { color: p.bad },
  });
}
