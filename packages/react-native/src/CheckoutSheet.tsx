import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
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
import { CheckoutSession } from './CheckoutSession';
import { elementConfigJson, elementIntentJson, type WalletOptions } from './elementConfig';
import { formatAmount } from './flow';
import { PaymentElement, type PaymentElementHandle } from './PaymentElement';
import { QrForm, QrWaiting, type QrFormHandle } from './QrPanels';
import { DEFAULT_RADIUS, paletteFor, type SheetPalette, type VezmoAppearance } from './theme';
import { markForBrand } from './marks';
import type { CheckoutResult, PaidMethod } from './types';
import {
  Banner,
  BrandTile,
  CardRow,
  Cross,
  makeStyles,
  MethodRow,
  PrimaryButton,
  ResultMark,
  RowsSkeleton,
  SecuredBy,
  Segmented,
  Skeleton,
  type MarkKind,
  type Styles,
} from './ui';

/** Room for the home indicator or the navigation bar under the sheet. */
// ponytail: fixed inset; read the real one if a merchant app needs it to the point.
const BOTTOM_INSET = Platform.OS === 'ios' ? 30 : 28;

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
 * The Vezmo payment sheet: the business and the total, the buyer's currency, one list of ways to pay (the native
 * element's rows and our own UPI or Pix), our Pay button and our result screens. Drawn as an overlay in the app's own
 * window (the element's native UI needs it).
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
      clearSelection: () => element.current?.clearSelection(),
      presentCard: () => element.current?.presentCard(),
      confirmCard: () => element.current?.confirmCard(),
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
  // The native element takes a moment to load: placeholder rows hold its place until it reports in.
  const [loadedConfig, setLoadedConfig] = useState('');
  const elementLoading = loadedConfig !== configJson && !snap.elementError;

  const close = (result: CheckoutResult) => props.onClose(result);
  const amount = charge ? formatAmount(charge.amountMinor, charge.currency) : '';
  const paying = phase.name === 'paying';
  const finished = phase.name === 'done' || phase.name === 'closed';
  const own = methods?.upi ? ('upi' as const) : methods?.pix ? ('pix' as const) : null;
  const qrForm = useRef<QrFormHandle>(null);
  // The Card row is the sheet's own; the element draws the other methods and this phone's wallet when offered.
  const cardOn = !!methods?.elementTypes.includes('card');
  const walletRow = Platform.OS === 'ios' ? !!(methods?.applePay && props.wallets?.applePay) : !!methods?.googlePay;
  const elementRows = !!methods && (methods.elementTypes.some((t) => t !== 'card') || walletRow);
  // Placeholders for the rows about to load.
  const expectedRows = methods ? Math.max(1, methods.elementTypes.length + (walletRow ? 1 : 0)) : 3;
  // A hairline over the pinned bottom only while content scrolls under it.
  const [scroll, setScroll] = useState({ content: 0, frame: 0 });
  const scrolls = scroll.content > scroll.frame + 1;

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
          {/* The result has its own way out. */}
          {!finished && (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close"
              disabled={paying}
              onPress={() => close({ status: 'canceled' })}
              style={[s.close, paying && { opacity: 0.4 }]}
              hitSlop={12}
            >
              <Cross color={p.ink2} size={11} />
            </Pressable>
          )}

          {phase.name === 'loading' && <Skeleton s={s} />}

          {finished && (
            <ResultView
              result={phase.result}
              amount={amount}
              merchant={merchant}
              paidWith={snap.paidWith}
              paidMethod={snap.paidMethod}
              s={s}
              p={p}
              onDone={() => close(phase.result)}
            />
          )}

          {!finished && phase.name !== 'loading' && config && charge && methods && (
            <>
            <ScrollView
              style={{ flexShrink: 1 }}
              keyboardShouldPersistTaps="handled"
              bounces={false}
              onLayout={(e) => {
                const frame = e.nativeEvent.layout.height;
                setScroll((v) => (v.frame === frame ? v : { ...v, frame }));
              }}
              onContentSizeChange={(_, content) => setScroll((v) => (v.content === content ? v : { ...v, content }))}
            >
              <View style={s.header}>
                <View style={s.who}>
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
                <View style={{ gap: 2 }}>
                  <Text style={s.label}>Total</Text>
                  <Text style={s.amount}>{amount}</Text>
                </View>
              </View>

              <View style={s.body}>
                {phase.name === 'waiting' ? (
                  <QrWaiting session={session} method={phase.method} qr={phase.qr} amount={amount} p={p} s={s} />
                ) : (
                  <>
                    {config.localOffer && (
                      <View style={s.section}>
                        <Text style={s.label}>Pay in</Text>
                        <Segmented
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
                      <View style={s.labelRow}>
                        <Text style={s.label}>Payment method</Text>
                        {/* The card networks, as their real marks (the card row itself shows a plain card). */}
                        <View style={s.marks} accessible accessibilityLabel="Visa, Mastercard, American Express and Discover accepted">
                          <BrandTile mark="visa" s={s} />
                          <BrandTile mark="mastercard" s={s} />
                          <BrandTile mark="amex" s={s} />
                          <BrandTile mark="discover" s={s} />
                        </View>
                      </View>
                      <View style={s.group} accessibilityRole="radiogroup" accessibilityLabel="Payment method">
                        {elementLoading && <RowsSkeleton s={s} rows={expectedRows} />}
                        {cardOn && !elementLoading && !snap.elementError && (
                          <CardRow
                            card={snap.card}
                            selected={snap.method === 'card'}
                            disabled={paying}
                            onPress={() => session.chooseCard()}
                            onChange={() => session.changeCard()}
                            s={s}
                            p={p}
                          />
                        )}
                        <View style={cardOn && elementRows && !elementLoading && !snap.elementError ? s.rowLine : undefined}>
                          <PaymentElement
                            ref={element}
                            configJson={configJson}
                            intentJson={intentJson}
                            onPaymentOptionChange={(option) => session.setOption(option)}
                            onCardOptionChange={(card) => session.setCard(card)}
                            onLoaded={() => {
                              setLoadedConfig(configJson);
                              session.setElementError(null);
                            }}
                            onLoadFailed={(message) => session.setElementError(message || 'load failed')}
                            onConfirmationToken={(id) => void session.onConfirmationToken(id)}
                            onConfirmResult={(status, message) => void session.onConfirmResult(status, message)}
                          />
                        </View>
                        {!!snap.elementError && (
                          <View style={s.empty} accessibilityLiveRegion="polite">
                            <Text testID="vezmo-element-error" style={s.emptyTitle}>
                              Couldn’t load payment options
                            </Text>
                            <Text style={s.emptyBody}>Check your connection and try again.</Text>
                          </View>
                        )}
                        {own && (
                          <MethodRow
                            icon={<BrandTile mark={own} s={s} large />}
                            title={own === 'upi' ? 'UPI' : 'Pix'}
                            sub={own === 'upi' ? 'Pay from your UPI app' : 'Instant transfer from your bank app'}
                            selected={snap.method === own}
                            disabled={paying}
                            onPress={() => session.chooseMethod(own)}
                            s={s}
                            p={p}
                          />
                        )}
                      </View>
                    </View>

                    {snap.method === 'upi' || snap.method === 'pix' ? (
                      <QrForm
                        ref={qrForm}
                        method={snap.method}
                        defaultName={config.customerName ?? ''}
                        country={config.localOffer?.country ?? null}
                        busy={paying}
                        error={phase.name === 'ready' ? (phase.error?.message ?? null) : null}
                        p={p}
                        s={s}
                        onPay={(billing) => void session.payWithQr(snap.method as 'upi' | 'pix', billing)}
                      />
                    ) : (
                      phase.name === 'ready' && phase.error && <Banner text={phase.error.message} s={s} p={p} />
                    )}
                  </>
                )}
              </View>
            </ScrollView>

            <View style={[s.dock, scrolls && s.dockLine]}>
              {phase.name === 'waiting' ? null : snap.method === 'upi' || snap.method === 'pix' ? (
                <PrimaryButton
                  lock
                  label={snap.method === 'upi' ? `Pay ${amount} with UPI` : `Get Pix code for ${amount}`}
                  busy={paying}
                  onPress={() => qrForm.current?.submit()}
                  s={s}
                  p={p}
                />
              ) : snap.elementError ? (
                <PrimaryButton testID="vezmo-retry" label="Try again" onPress={() => session.retryElement()} s={s} p={p} />
              ) : (
                <PrimaryButton
                  testID="vezmo-pay"
                  label={`Pay ${amount}`}
                  lock
                  disabled={!CheckoutSession.canPay(snap)}
                  busy={paying}
                  onPress={() => session.pay()}
                  s={s}
                  p={p}
                />
              )}
              <SecuredBy s={s} dark={scheme === 'dark'} />
            </View>
            </>
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
  paidMethod: PaidMethod | null;
  s: Styles;
  p: SheetPalette;
  onDone(): void;
}) {
  const { result, s, p } = props;
  const expired = result.status === 'failed' && result.error.code === 'expired';
  const copy: { title: string; body: string; kind: MarkKind; action: string } =
    result.status === 'succeeded'
      ? { title: 'Payment successful', body: '', kind: 'good', action: 'Done' }
      : result.status === 'processing'
        ? { title: 'Payment received', body: 'Your payment is on its way. You’ll get a confirmation when it clears.', kind: 'wait', action: 'Done' }
        : expired
          ? { title: 'Checkout expired', body: `Return to ${props.merchant || 'the app'} to start again.`, kind: 'neutral', action: 'Close' }
          : result.status === 'failed' && result.error.code === 'already_paid'
            ? { title: 'Already paid', body: result.error.message, kind: 'good', action: 'Done' }
            : result.status === 'failed'
              ? { title: 'Payment not completed', body: result.error.message, kind: 'bad', action: 'Close' }
              : { title: 'Payment canceled', body: '', kind: 'neutral', action: 'Close' };
  const paymentId = result.status === 'succeeded' || result.status === 'processing' ? result.paymentId : '';
  // The short transaction reference the buyer can quote; the payment's own id until there is one.
  const reference = (result.status === 'succeeded' && result.transactionId) || paymentId;
  // Paid with: the network's mark and last four from Vezmo's server, else the element's own label (or UPI / Pix).
  const mark = markForBrand(props.paidMethod?.brand) ?? (props.paidWith === 'UPI' ? 'upi' : props.paidWith === 'Pix' ? 'pix' : null);
  const last4 = props.paidMethod?.last4;
  const paidText = last4 ? `•••• ${last4}` : mark === 'upi' || mark === 'pix' ? '' : (props.paidWith ?? '');
  const rows: [string, string, ReactNode?][] = [
    ['Paid to', props.merchant],
    ['Paid with', paidText || (mark ?? ''), mark ? <BrandTile mark={mark} s={s} /> : null],
    ['Reference', reference],
  ];
  const shown = rows.filter(([, v]) => !!v);
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
          {shown.map(([k, v, logo], i) => (
            <View key={k} style={[s.receiptRow, i > 0 && s.rowLine]}>
              <Text style={s.receiptKey}>{k}</Text>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, flexShrink: 1 }}>
                {logo}
                {!(logo && v === mark) && (
                  <Text style={s.receiptValue} numberOfLines={1} ellipsizeMode="middle" selectable>
                    {v}
                  </Text>
                )}
              </View>
            </View>
          ))}
        </View>
      )}
      <View style={{ alignSelf: 'stretch', marginTop: 24 }}>
        <PrimaryButton testID="vezmo-done" label={copy.action} ink onPress={props.onDone} s={s} p={p} />
      </View>
    </View>
  );
}
