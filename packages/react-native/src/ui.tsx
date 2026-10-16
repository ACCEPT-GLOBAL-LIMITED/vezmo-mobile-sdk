import { useEffect, useRef, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Animated,
  Image,
  Easing,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  type ViewStyle,
} from 'react-native';
import { VEZMO_LOGO } from './logo';
import type { SheetPalette } from './theme';

/**
 * The sheet's building blocks, drawn with plain views (no icon font or image the app would have to bundle): the Pay
 * button, the segmented choice, the grouped method list, status marks and skeletons.
 * One type scale: 12.5 / 13 / 15 / 17 / 22 / 34. Labels: 13 medium, quiet ink, 8 above their control.
 */
export type Styles = ReturnType<typeof makeStyles>;

const FILL = { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 } as const;
/**
 * The native element's rows have their own leading margin inside our group; the sheet's own rows (UPI, Pix) copy it
 * so icons and titles line up. Measured on device: iOS icon at 28pt and title at 70pt from the group's edge.
 */
const ROW_LEAD = Platform.OS === 'ios' ? 13 : 0;
const HAIRLINE = StyleSheet.hairlineWidth;

export function makeStyles(p: SheetPalette, radius: number, fontFamily?: string) {
  const font = fontFamily ? { fontFamily } : {};
  return StyleSheet.create({
    scrim: { ...FILL, backgroundColor: p.scrim },
    stage: { ...FILL, justifyContent: 'flex-end' },
    sheet: {
      backgroundColor: p.surface,
      borderTopLeftRadius: 28,
      borderTopRightRadius: 28,
      maxHeight: '92%',
      boxShadow: '0 -10px 40px rgba(11,12,20,0.18)',
      overflow: 'hidden',
    },
    // A faint wash of the brand behind the business and the total.
    glow: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      height: 160,
      opacity: 0.6,
      experimental_backgroundImage: `linear-gradient(180deg, ${p.accentSoft} 0%, rgba(0,0,0,0) 100%)`,
    },
    grabber: { alignSelf: 'center', width: 36, height: 5, borderRadius: 3, backgroundColor: p.fieldLine, marginTop: 8 },
    close: {
      position: 'absolute',
      top: 33,
      right: 16,
      zIndex: 2,
      width: 30,
      height: 30,
      borderRadius: 15,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: p.fill,
    },
    header: { paddingHorizontal: 20, paddingTop: 14, gap: 18 },
    who: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingRight: 44 },
    logo: {
      width: 42,
      height: 42,
      borderRadius: 12,
      backgroundColor: p.logoTile,
      borderWidth: HAIRLINE,
      borderColor: p.fieldLine,
      alignItems: 'center',
      justifyContent: 'center',
      boxShadow: '0 1px 2px rgba(16,24,64,0.06)',
    },
    logoText: { ...font, color: p.accent, fontSize: 17, fontWeight: '700' },
    whoText: { flex: 1, minWidth: 0, gap: 1 },
    merchantRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
    merchant: { ...font, color: p.ink, fontSize: 15, fontWeight: '600', flexShrink: 1 },
    title: { ...font, color: p.ink3, fontSize: 13 },
    badge: { height: 20, paddingHorizontal: 7, borderRadius: 999, backgroundColor: p.warn, justifyContent: 'center' },
    badgeText: { ...font, color: '#FFFFFF', fontSize: 10, fontWeight: '700', letterSpacing: 0.6 },
    amount: {
      ...font,
      color: p.ink,
      fontSize: 34,
      lineHeight: 40,
      fontWeight: '700',
      letterSpacing: -0.9,
      fontVariant: ['tabular-nums'],
    },
    body: { paddingHorizontal: 20, paddingTop: 22, paddingBottom: 16, gap: 24 },
    // The pinned bottom: the main button and "Secured by", always in reach however long the content above.
    dock: { paddingHorizontal: 20, paddingTop: 12, gap: 14, backgroundColor: p.surface },
    dockLine: { borderTopWidth: HAIRLINE, borderTopColor: p.line },
    section: { gap: 8 },
    label: { ...font, color: p.ink2, fontSize: 13, fontWeight: '500' },
    hint: { ...font, color: p.ink3, fontSize: 12.5, lineHeight: 17 },
    // Segmented choice: a quiet track, the chosen side raised.
    segTrack: { flexDirection: 'row', padding: 3, borderRadius: radius, backgroundColor: p.surface2, gap: 3 },
    seg: { flex: 1, height: 38, borderRadius: radius - 3, alignItems: 'center', justifyContent: 'center' },
    segOn: {
      backgroundColor: p.raised,
      boxShadow: '0 1px 2px rgba(16,24,64,0.08), 0 3px 10px -4px rgba(16,24,64,0.14)',
    },
    segText: { ...font, color: p.ink2, fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
    segTextOn: { color: p.ink },
    segSub: { ...font, color: p.ink3, fontSize: 13, fontWeight: '500' },
    // The ways to pay: one rounded group (the element's rows, then the sheet's own UPI or Pix row).
    group: {
      borderRadius: radius,
      borderWidth: 1,
      borderColor: p.fieldLine,
      backgroundColor: p.field,
      paddingHorizontal: 14,
      overflow: 'hidden',
    },
    row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 52, paddingVertical: 10 },
    ownRow: { paddingLeft: ROW_LEAD },
    rowLine: { borderTopWidth: HAIRLINE, borderTopColor: p.line },
    rowIcon: {
      width: 30,
      height: 22,
      borderRadius: 5,
      borderWidth: HAIRLINE,
      borderColor: p.fieldLine,
      backgroundColor: p.field,
      alignItems: 'center',
      justifyContent: 'center',
    },
    rowTitle: { ...font, color: p.ink, fontSize: 15, fontWeight: '500' },
    rowSub: { ...font, color: p.ink3, fontSize: 12.5, marginTop: 1 },
    empty: { paddingVertical: 22, alignItems: 'center', gap: 4 },
    emptyTitle: { ...font, color: p.ink, fontSize: 15, fontWeight: '600', textAlign: 'center' },
    emptyBody: { ...font, color: p.ink3, fontSize: 13, textAlign: 'center' },
    banner: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      paddingVertical: 11,
      paddingHorizontal: 12,
      borderRadius: radius,
      backgroundColor: p.badSoft,
    },
    bannerText: { ...font, flex: 1, color: p.ink, fontSize: 14, lineHeight: 19 },
    pay: {
      height: 56,
      borderRadius: 999,
      backgroundColor: p.accent,
      experimental_backgroundImage: p.accentGradient ?? 'none',
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 9,
      boxShadow: p.payShadow,
    },
    payInk: { backgroundColor: p.ink, experimental_backgroundImage: 'none', boxShadow: 'none' },
    payOff: { backgroundColor: p.disabled, experimental_backgroundImage: 'none', boxShadow: 'none' },
    payText: { ...font, color: p.onAccent, fontSize: 17, fontWeight: '600', letterSpacing: -0.2, fontVariant: ['tabular-nums'] },
    payTextInk: { color: p.surface },
    payTextOff: { color: p.onDisabled },
    ghost: { height: 50, borderRadius: 999, backgroundColor: p.surface2, alignItems: 'center', justifyContent: 'center' },
    ghostText: { ...font, color: p.ink, fontSize: 15, fontWeight: '600' },
    link: { ...font, color: p.accent, fontSize: 14, fontWeight: '600', textAlign: 'center' },
    footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6 },
    footerText: { ...font, color: p.ink3, fontSize: 12.5, fontWeight: '500' },
    input: {
      ...font,
      borderWidth: 1,
      borderColor: p.fieldLine,
      backgroundColor: p.field,
      borderRadius: radius,
      paddingHorizontal: 14,
      height: 50,
      color: p.ink,
      fontSize: 16,
    },
    // Results
    result: { paddingHorizontal: 20, paddingTop: 30, paddingBottom: 8, alignItems: 'center', gap: 6 },
    resultTitle: { ...font, color: p.ink, fontSize: 22, fontWeight: '700', textAlign: 'center', letterSpacing: -0.3, marginTop: 14 },
    resultBody: { ...font, color: p.ink2, fontSize: 15, lineHeight: 21, textAlign: 'center' },
    receipt: { alignSelf: 'stretch', borderRadius: radius + 2, backgroundColor: p.surface2, paddingHorizontal: 16, marginTop: 18 },
    receiptRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 13, gap: 12 },
    receiptKey: { ...font, color: p.ink3, fontSize: 14 },
    receiptValue: { ...font, color: p.ink, fontSize: 14, fontWeight: '500', flexShrink: 1, textAlign: 'right', fontVariant: ['tabular-nums'] },
    // Waiting for UPI or Pix
    status: {
      alignSelf: 'flex-start',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      height: 30,
      paddingHorizontal: 12,
      borderRadius: 999,
      backgroundColor: p.accentSoft,
    },
    statusText: { ...font, color: p.accent, fontSize: 13.5, fontWeight: '600', fontVariant: ['tabular-nums'] },
    codeBox: { borderRadius: radius, backgroundColor: p.surface2, paddingHorizontal: 14, paddingVertical: 12, gap: 4 },
    code: { color: p.ink2, fontSize: 13, lineHeight: 18, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },
    step: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
    stepNo: { width: 24, height: 24, borderRadius: 12, backgroundColor: p.accentSoft, alignItems: 'center', justifyContent: 'center' },
    stepNoText: { ...font, color: p.accent, fontSize: 12, fontWeight: '700' },
    stepText: { ...font, flex: 1, color: p.ink2, fontSize: 14, lineHeight: 20, paddingTop: 2 },
    qrRow: { flexDirection: 'row', alignItems: 'center', gap: 16 },
    // A white tile with a quiet zone, so any phone can read it in either theme.
    qrTile: { padding: 10, borderRadius: 14, backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: p.fieldLine },
    qr: { width: 100, height: 100 },
    skel: { backgroundColor: p.line, borderRadius: 8 },
  });
}

/** The Pay button (and Done): Vezmo's gradient, or ink for a quiet finish; neutral until it can be pressed. */
export function PrimaryButton(props: {
  label: string;
  onPress(): void;
  s: Styles;
  p: SheetPalette;
  disabled?: boolean;
  busy?: boolean;
  lock?: boolean;
  ink?: boolean;
  testID?: string;
}) {
  const { s, p } = props;
  const off = props.disabled && !props.busy;
  const color = off ? p.onDisabled : props.ink ? p.surface : p.onAccent;
  const text = [s.payText, props.ink && s.payTextInk, off && s.payTextOff];
  return (
    <Pressable
      testID={props.testID}
      accessibilityRole="button"
      accessibilityLabel={props.busy ? 'Processing' : props.label}
      accessibilityState={{ disabled: !!(props.disabled || props.busy), busy: !!props.busy }}
      disabled={props.disabled || props.busy}
      onPress={props.onPress}
      style={({ pressed }) => [s.pay, props.ink && s.payInk, off && s.payOff, pressed && { transform: [{ scale: 0.985 }], opacity: 0.94 }]}
    >
      {props.busy ? (
        <>
          <ActivityIndicator color={color} />
          <Text style={text}>Processing…</Text>
        </>
      ) : (
        <>
          {props.lock && !off && <Lock color={color} size={13} />}
          <Text style={text}>{props.label}</Text>
        </>
      )}
    </Pressable>
  );
}

/** Two choices on one track (pay in rupees or dollars). */
export function Segmented<K extends string>(props: {
  items: { key: K; label: string; sub?: string }[];
  value: K;
  onChange(key: K): void;
  disabled?: boolean;
  label: string;
  s: Styles;
}) {
  const { s } = props;
  return (
    <View style={s.segTrack} accessibilityRole="radiogroup" accessibilityLabel={props.label}>
      {props.items.map((it) => {
        const on = it.key === props.value;
        return (
          <Pressable
            key={it.key}
            accessibilityRole="radio"
            accessibilityState={{ checked: on, disabled: props.disabled }}
            disabled={props.disabled}
            onPress={() => props.onChange(it.key)}
            style={[s.seg, on && s.segOn]}
          >
            <Text style={[s.segText, on && s.segTextOn]} numberOfLines={1}>
              {it.label}
              {!!it.sub && <Text style={s.segSub}>{`  ${it.sub}`}</Text>}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** One of the sheet's own methods (UPI, Pix), drawn like the element's rows so the group reads as one list. */
export function MethodRow(props: {
  icon: ReactNode;
  title: string;
  sub: string;
  selected: boolean;
  disabled?: boolean;
  onPress(): void;
  s: Styles;
  p: SheetPalette;
  first?: boolean;
}) {
  const { s, p } = props;
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ checked: props.selected, disabled: props.disabled }}
      disabled={props.disabled}
      onPress={props.onPress}
      style={[s.row, s.ownRow, !props.first && s.rowLine]}
    >
      {props.icon}
      <View style={{ flex: 1 }}>
        <Text style={s.rowTitle}>{props.title}</Text>
        <Text style={s.rowSub}>{props.sub}</Text>
      </View>
      {props.selected && <Tick color={p.accent} size={16} />}
    </Pressable>
  );
}

/** "Secured by" and the Vezmo logo, as under the embedded checkout (white in dark themes). */
export function SecuredBy({ s, dark }: { s: Styles; dark: boolean }) {
  return (
    <View style={s.footer} accessible accessibilityLabel="Secured by Vezmo">
      <Text style={s.footerText}>Secured by</Text>
      <Image
        source={{ uri: VEZMO_LOGO.uri }}
        style={{ width: VEZMO_LOGO.width, height: VEZMO_LOGO.height, ...(dark ? { tintColor: '#FFFFFF', opacity: 0.85 } : {}) }}
        resizeMode="contain"
      />
    </View>
  );
}

/** Something went wrong with the payment, said kindly. */
export function Banner(props: { text: string; s: Styles; p: SheetPalette; testID?: string }) {
  const { s, p } = props;
  return (
    <View style={s.banner} accessibilityLiveRegion="polite">
      <Mark kind="bad" size={20} p={p} />
      <Text testID={props.testID} style={s.bannerText}>
        {props.text}
      </Text>
    </View>
  );
}

/** A padlock: body and shackle. */
export function Lock({ color, size }: { color: string; size: number }) {
  const w = size * 0.8;
  return (
    <View style={{ width: w, height: size, alignItems: 'center' }} accessible={false}>
      <View
        style={{
          width: w * 0.62,
          height: size * 0.5,
          borderWidth: size * 0.13,
          borderBottomWidth: 0,
          borderColor: color,
          borderTopLeftRadius: w,
          borderTopRightRadius: w,
        }}
      />
      <View style={{ width: w, height: size * 0.52, borderRadius: size * 0.14, backgroundColor: color, marginTop: -size * 0.02 }} />
    </View>
  );
}

/** A close cross. */
export function Cross({ color, size }: { color: string; size: number }) {
  const bar = { position: 'absolute' as const, width: size, height: 1.8, borderRadius: 1, backgroundColor: color };
  return (
    <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }} accessible={false}>
      <View style={[bar, { transform: [{ rotate: '45deg' }] }]} />
      <View style={[bar, { transform: [{ rotate: '-45deg' }] }]} />
    </View>
  );
}

/** A checkmark stroke. */
export function Tick({ color, size, weight }: { color: string; size: number; weight?: number }) {
  const w = weight ?? Math.max(2, size * 0.14);
  return (
    <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }} accessible={false}>
      <View
        style={{
          width: size * 0.36,
          height: size * 0.68,
          borderRightWidth: w,
          borderBottomWidth: w,
          borderColor: color,
          transform: [{ translateY: -size * 0.08 }, { rotate: '45deg' }],
        }}
      />
    </View>
  );
}

/** A clock face: a ring and two hands. */
function Clock({ color, size }: { color: string; size: number }) {
  const w = Math.max(2, size * 0.13);
  return (
    <View style={{ width: size, height: size, borderRadius: size, borderWidth: w, borderColor: color }} accessible={false}>
      <View style={{ position: 'absolute', left: '50%', top: '16%', width: w, height: '38%', marginLeft: -w / 2, borderRadius: w, backgroundColor: color }} />
      <View style={{ position: 'absolute', left: '50%', top: '50%', width: '30%', height: w, marginTop: -w / 2, borderRadius: w, backgroundColor: color }} />
    </View>
  );
}

export type MarkKind = 'good' | 'bad' | 'wait' | 'neutral';

/** A flat status disc: a check (paid), a mark (problem), a clock (on its way, or ran out of time). */
export function Mark({ kind, size, p }: { kind: MarkKind; size: number; p: SheetPalette }) {
  const fill = kind === 'good' ? p.good : kind === 'bad' ? p.bad : kind === 'wait' ? p.accent : p.surface2;
  const ink = kind === 'neutral' ? p.ink2 : '#FFFFFF';
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: fill, alignItems: 'center', justifyContent: 'center' }} accessible={false}>
      {kind === 'good' && <Tick color={ink} size={size * 0.56} weight={Math.max(2, size * 0.075)} />}
      {kind === 'bad' && (
        <View style={{ alignItems: 'center', gap: size * 0.07 }}>
          <View style={{ width: Math.max(2, size * 0.09), height: size * 0.3, borderRadius: size, backgroundColor: ink }} />
          <View style={{ width: Math.max(2, size * 0.09), height: Math.max(2, size * 0.09), borderRadius: size, backgroundColor: ink }} />
        </View>
      )}
      {(kind === 'wait' || kind === 'neutral') && <Clock color={ink} size={size * 0.46} />}
    </View>
  );
}

/** The result's mark, arriving with a small spring inside a soft halo; one geometry for every outcome. */
export function ResultMark({ kind, p }: { kind: MarkKind; p: SheetPalette }) {
  const t = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.spring(t, { toValue: 1, friction: 6, tension: 90, useNativeDriver: true }).start();
  }, [t]);
  const halo = kind === 'good' ? p.good : kind === 'bad' ? p.bad : kind === 'wait' ? p.accent : '#767680';
  return (
    <View style={{ width: 96, height: 96, alignItems: 'center', justifyContent: 'center' }}>
      <Animated.View
        style={{
          position: 'absolute',
          width: 96,
          height: 96,
          borderRadius: 48,
          backgroundColor: halo,
          opacity: t.interpolate({ inputRange: [0, 1], outputRange: [0, kind === 'neutral' ? 0.08 : 0.12] }),
          transform: [{ scale: t.interpolate({ inputRange: [0, 1], outputRange: [0.6, 1] }) }],
        }}
      />
      <Animated.View style={{ opacity: t, transform: [{ scale: t.interpolate({ inputRange: [0, 1], outputRange: [0.5, 1] }) }] }}>
        <Mark kind={kind} size={64} p={p} />
      </Animated.View>
    </View>
  );
}

/** A dot that breathes while we wait for the bank. */
export function Pulse({ color }: { color: string }) {
  const t = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(t, { toValue: 1, duration: 600, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
        Animated.timing(t, { toValue: 0, duration: 600, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [t]);
  return (
    <Animated.View
      style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: color, opacity: t.interpolate({ inputRange: [0, 1], outputRange: [1, 0.3] }) }}
    />
  );
}

/** A gentle pulse for placeholders. */
function usePulse() {
  const t = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(t, { toValue: 1, duration: 800, useNativeDriver: true }),
        Animated.timing(t, { toValue: 0, duration: 800, useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [t]);
  return t.interpolate({ inputRange: [0, 1], outputRange: [1, 0.5] });
}

/** Placeholder method rows in the group while the native element loads. */
export function RowsSkeleton({ s, rows, still }: { s: Styles; rows: number; still?: boolean }) {
  const pulse = usePulse();
  return (
    <Animated.View accessibilityLabel="Loading payment methods" style={{ opacity: still ? 1 : pulse }}>
      {Array.from({ length: rows }, (_, i) => (
        <View key={i} style={[s.row, i > 0 && s.rowLine]}>
          <View style={[s.skel, { width: 32, height: 22, borderRadius: 5 }]} />
          <View style={[s.skel, { width: 92 + (i % 2) * 40, height: 12 }]} />
        </View>
      ))}
    </Animated.View>
  );
}

/** The whole sheet in placeholder shapes while the checkout loads, the same height as the sheet it becomes. */
export function Skeleton({ s }: { s: Styles }) {
  const opacity = usePulse();
  const block = (style: ViewStyle) => <View style={[s.skel, style]} />;
  return (
    <Animated.View accessibilityLabel="Loading" style={{ opacity }}>
      <View style={s.header}>
        <View style={s.who}>
          {block({ width: 42, height: 42, borderRadius: 12 })}
          <View style={{ gap: 7 }}>
            {block({ width: 140, height: 13 })}
            {block({ width: 96, height: 11 })}
          </View>
        </View>
        <View style={{ gap: 9 }}>
          {block({ width: 40, height: 11 })}
          {block({ width: 150, height: 32 })}
        </View>
      </View>
      <View style={s.body}>
        <View style={s.section}>
          {block({ width: 110, height: 12 })}
          <View style={s.group}>
            <RowsSkeleton s={s} rows={5} still />
          </View>
        </View>
      </View>
      <View style={s.dock}>
        {block({ height: 56, borderRadius: 999 })}
        {block({ width: 120, height: 12, alignSelf: 'center' })}
      </View>
    </Animated.View>
  );
}
