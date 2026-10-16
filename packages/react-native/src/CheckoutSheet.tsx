import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import {
  Animated,
  Easing,
  KeyboardAvoidingView,
  Platform,
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
import { DEFAULT_RADIUS, paletteFor, type SheetPalette, type VezmoAppearance } from './theme';
import type { CheckoutResult } from './types';
import { Banner, Cross, Lock, makeStyles, PrimaryButton, ResultMark, Segmented, Skeleton, type Styles } from './ui';

/** Shown instead of a technical message when the payment methods can't load (a network hiccup, usually). */
const LOAD_FAILED = 'We couldn’t load the ways to pay. Check your connection and try again.';

/** Room for the home indicator or the navigation bar under the sheet. */
// ponytail: fixed inset; read the real one if a merchant app needs it to the point.
const BOTTOM_INSET = Platform.OS === 'ios' ? 30 : 20;

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
 * The Vezmo payment sheet: the business and the total, the buyer's currency, the native payment element, our Pay
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
  const radius = props.appearance?.cornerRadius ?? DEFAULT_RADIUS;
  const p = useMemo(() => paletteFor(scheme, props.appearance), [scheme, props.appearance]);
  const s = useMemo(() => makeStyles(p, radius, props.appearance?.fontFamily), [p, radius, props.appearance]);

  // The sheet rises over a fading scrim.
  const enter = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(enter, { toValue: 1, duration: 320, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
  }, [enter]);

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
  // The native element takes a moment to load: placeholders hold its rows until it reports in.
  const [loadedConfig, setLoadedConfig] = useState('');
  const elementLoading = loadedConfig !== configJson && !snap.elementError;

  const close = (result: CheckoutResult) => props.onClose(result);
  const amount = charge ? formatAmount(charge.amountMinor, charge.currency) : '';
  const paying = phase.name === 'paying';
  const finished = phase.name === 'done' || phase.name === 'closed';

  return (
    <View style={StyleSheet.absoluteFill} accessibilityViewIsModal>
      <Animated.View style={[s.scrim, { opacity: enter }]} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={s.stage} pointerEvents="box-none">
        <Animated.View
          style={[
            s.sheet,
            { paddingBottom: BOTTOM_INSET, transform: [{ translateY: enter.interpolate({ inputRange: [0, 1], outputRange: [80, 0] }) }] },
          ]}
        >
          {!finished && <View style={s.glow} pointerEvents="none" />}
          <View style={s.grabber} />
          {/* The result has its own Done. */}
          {!finished && (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close"
              disabled={paying}
              onPress={() => close({ status: 'canceled' })}
              style={[s.close, { position: 'absolute', top: 32, right: 16, zIndex: 2, opacity: paying ? 0.4 : 1 }]}
              hitSlop={12}
            >
              <Cross color={p.ink2} size={12} />
            </Pressable>
          )}

          {phase.name === 'loading' && <Skeleton s={s} />}

          {finished && (
            <ResultView
              result={phase.result}
              amount={amount}
              merchant={merchant}
              paidWith={snap.option}
              s={s}
              p={p}
              onDone={() => close(phase.result)}
            />
          )}

          {!finished && phase.name !== 'loading' && config && charge && methods && (
            <ScrollView keyboardShouldPersistTaps="handled" bounces={false}>
              <View style={s.header}>
                <View style={[s.who, { paddingRight: 44 }]}>
                  <View style={s.logo}>
                    <Text style={s.logoText}>{(merchant.trim()[0] ?? '•').toUpperCase()}</Text>
                  </View>
                  <View style={s.whoText}>
                    <View style={s.merchantRow}>
                      <Text style={s.merchant} numberOfLines={1}>
                        {merchant}
                      </Text>
                      {!config.livemode && (
                        <View style={s.badge}>
                          <Text style={s.badgeText}>TEST MODE</Text>
                        </View>
                      )}
                    </View>
                    {!!config.title && (
                      <Text style={s.title} numberOfLines={1}>
                        {config.title}
                      </Text>
                    )}
                  </View>
                </View>
                <View>
                  <Text style={s.totalLabel}>Total</Text>
                  <Text style={s.amount}>{amount}</Text>
                </View>
              </View>

              <View style={s.body}>
                {phase.name === 'waiting' ? (
                  <QrWaiting session={session} method={phase.method} qr={phase.qr} p={p} s={s} />
                ) : (
                  <>
                    {config.localOffer && (
                      <View style={s.section}>
                        <Text style={s.label}>Pay in</Text>
                        <Segmented
                          role="radio"
                          label="Pay in"
                          s={s}
                          disabled={paying}
                          value={snap.payLocal ? 'local' : 'price'}
                          onChange={(k) => session.choosePayLocal(k === 'local')}
                          items={[
                            {
                              key: 'local',
                              label: formatAmount(config.localOffer.amountMinor, config.localOffer.currency),
                              sub: config.localOffer.currency.toUpperCase(),
                            },
                            { key: 'price', label: formatAmount(config.amountMinor, config.currency), sub: config.currency.toUpperCase() },
                          ]}
                        />
                        {snap.payLocal && (
                          <Text style={s.hint}>
                            1 {config.currency.toUpperCase()} = {config.localOffer.rate.toFixed(4)} {config.localOffer.currency.toUpperCase()}
                          </Text>
                        )}
                      </View>
                    )}

                    <View style={s.section}>
                      <Text style={s.label}>Payment method</Text>
                      {(methods.upi || methods.pix) && (
                        <Segmented
                          role="tab"
                          label="Payment method"
                          s={s}
                          disabled={paying}
                          value={snap.method}
                          onChange={(m) => session.chooseMethod(m)}
                          items={[
                            { key: 'element', label: 'Card and more' },
                            methods.upi ? { key: 'upi', label: 'UPI' } : { key: 'pix', label: 'Pix' },
                          ]}
                        />
                      )}
                      {snap.method === 'element' ? (
                        <>
                          <PaymentElement
                            ref={element}
                            configJson={configJson}
                            intentJson={intentJson}
                            onPaymentOptionChange={(option) => session.setOption(option)}
                            onLoaded={() => {
                              setLoadedConfig(configJson);
                              session.setElementError(null);
                            }}
                            onLoadFailed={(message) => session.setElementError(message || LOAD_FAILED)}
                            onConfirmationToken={(id) => void session.onConfirmationToken(id)}
                            onConfirmResult={(status, message) => void session.onConfirmResult(status, message)}
                          />
                          {elementLoading && (
                            <View accessibilityLabel="Loading payment methods" style={{ gap: 10 }}>
                              {[0, 1, 2].map((i) => (
                                <View key={i} style={[s.skel, { height: 54, borderRadius: radius }]} />
                              ))}
                            </View>
                          )}
                        </>
                      ) : (
                        <QrForm
                          method={snap.method}
                          defaultName={config.customerName ?? ''}
                          country={config.localOffer?.country ?? null}
                          busy={paying}
                          amount={amount}
                          error={phase.name === 'ready' ? (phase.error?.message ?? null) : null}
                          p={p}
                          s={s}
                          onPay={(billing) => void session.payWithQr(snap.method as 'upi' | 'pix', billing)}
                        />
                      )}
                    </View>

                    {snap.method === 'element' && (
                      <>
                        {!!snap.elementError && (
                          <Banner
                            testID="vezmo-element-error"
                            text={LOAD_FAILED}
                            s={s}
                            p={p}
                            action={{ label: 'Try again', testID: 'vezmo-retry', onPress: () => session.retryElement() }}
                          />
                        )}
                        {phase.name === 'ready' && phase.error && <Banner text={phase.error.message} s={s} p={p} />}
                        <PrimaryButton
                          testID="vezmo-pay"
                          label={`Pay ${amount}`}
                          lock
                          disabled={!snap.canPay}
                          busy={paying}
                          onPress={() => session.pay()}
                          s={s}
                          p={p}
                        />
                      </>
                    )}
                  </>
                )}
                <View style={s.footer}>
                  <Lock color={p.ink3} size={11} />
                  <Text style={s.footerText}>Secured by Vezmo</Text>
                </View>
              </View>
            </ScrollView>
          )}
        </Animated.View>
      </KeyboardAvoidingView>
    </View>
  );
}

function ResultView(props: {
  result: CheckoutResult;
  amount: string;
  merchant: string;
  paidWith: string | null;
  s: Styles;
  p: SheetPalette;
  onDone(): void;
}) {
  const { result, s, p } = props;
  const copy =
    result.status === 'succeeded'
      ? { title: 'Payment successful', body: '', kind: 'good' as const }
      : result.status === 'processing'
        ? { title: 'Payment received', body: 'Your payment is on its way. You’ll get a confirmation when it clears.', kind: 'wait' as const }
        : result.status === 'failed'
          ? {
              title: result.error.code === 'already_paid' ? 'Already paid' : 'Payment not completed',
              body: result.error.message,
              kind: result.error.code === 'already_paid' ? ('good' as const) : ('bad' as const),
            }
          : { title: 'Payment canceled', body: '', kind: 'bad' as const };
  const paymentId = result.status === 'succeeded' || result.status === 'processing' ? result.paymentId : '';
  const rows = [
    ['Paid to', props.merchant],
    ['Paid with', props.paidWith ?? ''],
    ['Reference', paymentId],
  ].filter(([, v]) => !!v);
  return (
    <View style={s.result}>
      <ResultMark kind={copy.kind} p={p} />
      <Text testID="vezmo-result-title" style={s.resultTitle} accessibilityRole="header">
        {copy.title}
      </Text>
      {result.status === 'succeeded' && !!props.amount && <Text style={s.amount}>{props.amount}</Text>}
      {!!copy.body && <Text style={s.resultBody}>{copy.body}</Text>}
      {!!paymentId && (
        <View style={s.receipt}>
          {rows.map(([k, v], i) => (
            <View key={k} style={[s.receiptRow, i > 0 && s.receiptLine]}>
              <Text style={s.receiptKey}>{k}</Text>
              <Text style={s.receiptValue} numberOfLines={1} selectable>
                {v}
              </Text>
            </View>
          ))}
        </View>
      )}
      <View style={{ alignSelf: 'stretch', marginTop: 24 }}>
        <PrimaryButton testID="vezmo-done" label="Done" ink onPress={props.onDone} s={s} p={p} />
      </View>
    </View>
  );
}
