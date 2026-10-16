import { useEffect, useRef } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  type ViewStyle,
} from 'react-native';
import type { SheetPalette } from './theme';

/**
 * The sheet's building blocks, drawn with plain views (no icon font or image the app would have to bundle): the Pay
 * button, the segmented choice, banners, status marks and skeletons. One type scale: 12.5 / 13 / 15 / 17 / 22 / 34.
 */
export type Styles = ReturnType<typeof makeStyles>;

/** A soft light from the top-left, over any fill (the merchant's own accent included). */
const SHEEN = 'linear-gradient(160deg, rgba(255,255,255,0.26) 0%, rgba(255,255,255,0) 58%)';

const FILL = { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 } as const;

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
    // The brand's light at the top of the sheet, behind the business and the total.
    glow: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      height: 180,
      experimental_backgroundImage: `linear-gradient(180deg, ${p.accentSoft} 0%, rgba(0,0,0,0) 100%)`,
    },
    grabber: { alignSelf: 'center', width: 36, height: 5, borderRadius: 3, backgroundColor: p.fieldLine, marginTop: 8 },
    header: { paddingHorizontal: 20, paddingTop: 14, gap: 18 },
    who: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    logo: {
      width: 42,
      height: 42,
      borderRadius: 12,
      backgroundColor: p.logoTile,
      borderWidth: StyleSheet.hairlineWidth,
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
    close: { width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: p.surface2 },
    totalLabel: { ...font, color: p.ink3, fontSize: 13, fontWeight: '500' },
    amount: {
      ...font,
      color: p.ink,
      fontSize: 34,
      lineHeight: 40,
      fontWeight: '700',
      letterSpacing: -0.9,
      fontVariant: ['tabular-nums'],
    },
    body: { paddingHorizontal: 20, paddingTop: 22, paddingBottom: 8, gap: 22 },
    section: { gap: 10 },
    label: { ...font, color: p.ink2, fontSize: 13, fontWeight: '600' },
    hint: { ...font, color: p.ink3, fontSize: 12.5, lineHeight: 17 },
    // Segmented choice: a quiet track, the chosen side raised.
    segTrack: { flexDirection: 'row', padding: 4, borderRadius: radius + 2, backgroundColor: p.surface2, gap: 4 },
    seg: { flex: 1, minHeight: 44, borderRadius: radius - 2, alignItems: 'center', justifyContent: 'center', paddingVertical: 6 },
    segOn: {
      backgroundColor: p.raised,
      boxShadow: '0 1px 2px rgba(16,24,64,0.08), 0 4px 12px -4px rgba(16,24,64,0.14)',
    },
    segText: { ...font, color: p.ink2, fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
    segTextOn: { color: p.ink },
    segSub: { ...font, color: p.ink3, fontSize: 11.5, fontWeight: '500', marginTop: 1, letterSpacing: 0.3 },
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
    bannerAction: { paddingHorizontal: 12, height: 32, borderRadius: 999, backgroundColor: p.surface, justifyContent: 'center' },
    bannerActionText: { ...font, color: p.ink, fontSize: 13, fontWeight: '600' },
    pay: {
      height: 56,
      borderRadius: 999,
      backgroundColor: p.accent,
      experimental_backgroundImage: p.accentGradient ?? SHEEN,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 9,
      boxShadow: '0 14px 28px -14px rgba(80,58,242,0.7), inset 0 1px 0 rgba(255,255,255,0.22)',
    },
    payInk: { backgroundColor: p.ink, experimental_backgroundImage: 'none', boxShadow: 'none' },
    payOff: { opacity: 0.4, boxShadow: 'none' },
    payText: { ...font, color: p.onAccent, fontSize: 17, fontWeight: '600', letterSpacing: -0.2, fontVariant: ['tabular-nums'] },
    payTextInk: { color: p.surface },
    ghost: { height: 50, borderRadius: 999, backgroundColor: p.surface2, alignItems: 'center', justifyContent: 'center' },
    ghostText: { ...font, color: p.ink, fontSize: 15, fontWeight: '600' },
    link: { ...font, color: p.accent, fontSize: 15, fontWeight: '600', textAlign: 'center' },
    footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingTop: 2 },
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
    result: { paddingHorizontal: 20, paddingTop: 26, paddingBottom: 8, alignItems: 'center', gap: 6 },
    resultTitle: { ...font, color: p.ink, fontSize: 22, fontWeight: '700', textAlign: 'center', letterSpacing: -0.3, marginTop: 14 },
    resultBody: { ...font, color: p.ink2, fontSize: 15, lineHeight: 21, textAlign: 'center' },
    receipt: { alignSelf: 'stretch', borderRadius: radius + 2, backgroundColor: p.surface2, paddingHorizontal: 16, marginTop: 18 },
    receiptRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 13, gap: 12 },
    receiptLine: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: p.fieldLine },
    receiptKey: { ...font, color: p.ink3, fontSize: 14 },
    receiptValue: { ...font, color: p.ink, fontSize: 14, fontWeight: '500', flexShrink: 1, textAlign: 'right', fontVariant: ['tabular-nums'] },
    // Waiting for UPI or Pix
    status: {
      alignSelf: 'center',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      height: 32,
      paddingHorizontal: 14,
      borderRadius: 999,
      backgroundColor: p.accentSoft,
    },
    statusText: { ...font, color: p.accent, fontSize: 13.5, fontWeight: '600' },
    codeBox: { borderRadius: radius, backgroundColor: p.surface2, padding: 14, gap: 8 },
    codeHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
    code: { color: p.ink2, fontSize: 12.5, lineHeight: 18, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },
    step: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
    stepNo: { width: 24, height: 24, borderRadius: 12, backgroundColor: p.accentSoft, alignItems: 'center', justifyContent: 'center' },
    stepNoText: { ...font, color: p.accent, fontSize: 12, fontWeight: '700' },
    stepText: { ...font, flex: 1, color: p.ink2, fontSize: 14, lineHeight: 20, paddingTop: 2 },
    qrRow: { flexDirection: 'row', alignItems: 'center', gap: 14, borderRadius: radius, borderWidth: 1, borderColor: p.fieldLine, padding: 10 },
    qr: { width: 84, height: 84, borderRadius: 8, backgroundColor: '#FFFFFF' },
    skel: { backgroundColor: p.line, borderRadius: 8 },
  });
}

/** The Pay button (and Done): Vezmo's gradient, or ink for a quiet finish. */
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
  const off = props.disabled || props.busy;
  const color = props.ink ? p.surface : p.onAccent;
  return (
    <Pressable
      testID={props.testID}
      accessibilityRole="button"
      accessibilityLabel={props.busy ? 'Processing' : props.label}
      accessibilityState={{ disabled: !!off, busy: !!props.busy }}
      disabled={off}
      onPress={props.onPress}
      style={({ pressed }) => [
        s.pay,
        props.ink && s.payInk,
        props.disabled && !props.busy && s.payOff,
        pressed && { transform: [{ scale: 0.985 }], opacity: 0.94 },
      ]}
    >
      {props.busy ? (
        <>
          <ActivityIndicator color={color} />
          <Text style={[s.payText, props.ink && s.payTextInk]}>Processing…</Text>
        </>
      ) : (
        <>
          {props.lock && <Lock color={color} size={13} />}
          <Text style={[s.payText, props.ink && s.payTextInk]}>{props.label}</Text>
        </>
      )}
    </Pressable>
  );
}

/** Two choices on one track (pay in rupees or dollars; card or UPI). */
export function Segmented<K extends string>(props: {
  items: { key: K; label: string; sub?: string }[];
  value: K;
  onChange(key: K): void;
  disabled?: boolean;
  label: string;
  role: 'radio' | 'tab';
  s: Styles;
}) {
  const { s } = props;
  return (
    <View style={s.segTrack} accessibilityRole={props.role === 'radio' ? 'radiogroup' : 'tablist'} accessibilityLabel={props.label}>
      {props.items.map((it) => {
        const on = it.key === props.value;
        return (
          <Pressable
            key={it.key}
            accessibilityRole={props.role}
            accessibilityState={props.role === 'radio' ? { checked: on, disabled: props.disabled } : { selected: on, disabled: props.disabled }}
            disabled={props.disabled}
            onPress={() => props.onChange(it.key)}
            style={[s.seg, on && s.segOn]}
          >
            <Text style={[s.segText, on && s.segTextOn]}>{it.label}</Text>
            {!!it.sub && <Text style={s.segSub}>{it.sub}</Text>}
          </Pressable>
        );
      })}
    </View>
  );
}

/** Something went wrong, said kindly, with the way out next to it. */
export function Banner(props: {
  text: string;
  s: Styles;
  p: SheetPalette;
  action?: { label: string; onPress(): void; testID?: string };
  testID?: string;
}) {
  const { s, p } = props;
  return (
    <View style={s.banner} accessibilityLiveRegion="polite">
      <Mark kind="bad" size={20} p={p} />
      <Text testID={props.testID} style={s.bannerText}>
        {props.text}
      </Text>
      {props.action && (
        <Pressable testID={props.action.testID} accessibilityRole="button" onPress={props.action.onPress} style={s.bannerAction} hitSlop={8}>
          <Text style={s.bannerActionText}>{props.action.label}</Text>
        </Pressable>
      )}
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

/** A filled status circle: a check (paid), a mark (problem) or a clock (on its way). */
export function Mark({ kind, size, p }: { kind: 'good' | 'bad' | 'wait'; size: number; p: SheetPalette }) {
  const fill = kind === 'good' ? p.good : kind === 'bad' ? p.bad : p.accent;
  const ink = '#FFFFFF';
  return (
    <View
      accessible={false}
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: fill,
        experimental_backgroundImage: SHEEN,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {kind === 'good' && (
        <View
          style={{
            width: size * 0.22,
            height: size * 0.42,
            borderRightWidth: Math.max(2, size * 0.075),
            borderBottomWidth: Math.max(2, size * 0.075),
            borderColor: ink,
            transform: [{ translateY: -size * 0.04 }, { rotate: '45deg' }],
          }}
        />
      )}
      {kind === 'bad' && (
        <View style={{ alignItems: 'center', gap: size * 0.07 }}>
          <View style={{ width: Math.max(2, size * 0.09), height: size * 0.3, borderRadius: size, backgroundColor: ink }} />
          <View style={{ width: Math.max(2, size * 0.09), height: Math.max(2, size * 0.09), borderRadius: size, backgroundColor: ink }} />
        </View>
      )}
      {kind === 'wait' && (
        <View style={{ width: size * 0.46, height: size * 0.46, borderRadius: size, borderWidth: Math.max(2, size * 0.065), borderColor: ink }}>
          <View style={{ position: 'absolute', left: '50%', top: '18%', width: 2.5, height: '34%', marginLeft: -1.25, borderRadius: 2, backgroundColor: ink }} />
          <View style={{ position: 'absolute', left: '48%', top: '48%', width: '30%', height: 2.5, borderRadius: 2, backgroundColor: ink }} />
        </View>
      )}
    </View>
  );
}

/** The result's mark, arriving: a soft halo, then the circle with a small spring. */
export function ResultMark({ kind, p }: { kind: 'good' | 'bad' | 'wait'; p: SheetPalette }) {
  const t = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.spring(t, { toValue: 1, friction: 6, tension: 90, useNativeDriver: true }).start();
  }, [t]);
  const halo = kind === 'good' ? p.good : kind === 'bad' ? p.bad : p.accent;
  return (
    <View style={{ width: 96, height: 96, alignItems: 'center', justifyContent: 'center' }}>
      <Animated.View
        style={{
          position: 'absolute',
          width: 96,
          height: 96,
          borderRadius: 48,
          backgroundColor: halo,
          opacity: t.interpolate({ inputRange: [0, 1], outputRange: [0, 0.12] }),
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
        Animated.timing(t, { toValue: 1, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
        Animated.timing(t, { toValue: 0, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [t]);
  return (
    <Animated.View
      style={{
        width: 8,
        height: 8,
        borderRadius: 4,
        backgroundColor: color,
        opacity: t.interpolate({ inputRange: [0, 1], outputRange: [0.35, 1] }),
        transform: [{ scale: t.interpolate({ inputRange: [0, 1], outputRange: [0.8, 1.15] }) }],
      }}
    />
  );
}

/** Placeholder shapes in the sheet's own layout while the checkout loads, gently pulsing. */
export function Skeleton({ s }: { s: Styles }) {
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
  const block = (style: ViewStyle, key?: number) => <View key={key} style={[s.skel, style]} />;
  return (
    <Animated.View accessibilityLabel="Loading" style={{ opacity: t.interpolate({ inputRange: [0, 1], outputRange: [1, 0.5] }) }}>
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
          {[0, 1, 2].map((i) => block({ height: 54, borderRadius: 14 }, i))}
        </View>
        {block({ height: 56, borderRadius: 999 })}
      </View>
    </Animated.View>
  );
}
