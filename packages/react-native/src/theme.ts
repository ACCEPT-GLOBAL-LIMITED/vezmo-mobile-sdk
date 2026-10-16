/**
 * The Vezmo checkout's look on the phone: the same tokens as the hosted checkout (`checkoutTheme.ts` on the web),
 * opaque where the web uses glass. A merchant may override the accent, colours, corner radius and font.
 */
export interface SheetPalette {
  page: string;
  surface: string;
  /** Quiet fills: the segmented track, the code box, skeletons. */
  surface2: string;
  /** The chosen side of a segmented track (lighter than the track in both themes). */
  raised: string;
  /** System-grey fill behind small round controls (close). */
  fill: string;
  /** The Pay button before a method is chosen, and its text. */
  disabled: string;
  onDisabled: string;
  field: string;
  fieldLine: string;
  line: string;
  ink: string;
  ink2: string;
  ink3: string;
  accent: string;
  onAccent: string;
  /** The accent at low strength: selected tiles, the waiting status. */
  accentSoft: string;
  /** The Pay button's fill: the logo's indigo-to-blue. Dropped when a merchant sets their own accent. */
  accentGradient: string | null;
  /** The Pay button's lift (none in dark, where a glow reads as cheap). */
  payShadow: string;
  good: string;
  bad: string;
  badSoft: string;
  /** The test-mode badge (as on the hosted checkout). */
  warn: string;
  /** Time running out (the last minute of a UPI or Pix code). */
  warnInk: string;
  warnSoft: string;
  /** Behind the business's monogram. */
  logoTile: string;
  scrim: string;
}

const GRADIENT = 'linear-gradient(100deg, #503AF2 0%, #4651F7 52%, #3866FF 100%)';

export const LIGHT: SheetPalette = {
  page: '#F3F5FA',
  surface: '#FFFFFF',
  surface2: '#F4F5FA',
  raised: '#FFFFFF',
  fill: 'rgba(118,118,128,0.12)',
  disabled: '#ECECF1',
  onDisabled: '#A1A1AA',
  field: '#FFFFFF',
  fieldLine: '#E1E3EC',
  line: '#E8EAF1',
  ink: '#0B0C14',
  ink2: '#454A5A',
  ink3: '#6B7083',
  accent: '#503AF2',
  onAccent: '#FFFFFF',
  accentSoft: 'rgba(80,58,242,0.08)',
  accentGradient: GRADIENT,
  payShadow: '0 1px 2px rgba(80,58,242,0.22), 0 8px 18px -8px rgba(80,58,242,0.45)',
  good: '#0E8A55',
  bad: '#D12C4B',
  badSoft: 'rgba(209,44,75,0.07)',
  warn: '#EA580C',
  warnInk: '#B45309',
  warnSoft: '#FEF3C7',
  logoTile: '#FFFFFF',
  scrim: 'rgba(11,12,20,0.42)',
};

export const DARK: SheetPalette = {
  page: '#07080E',
  surface: '#14151F',
  surface2: '#1C1D29',
  raised: '#2C2D3C',
  fill: 'rgba(118,118,128,0.24)',
  disabled: '#26262F',
  onDisabled: '#6B6B76',
  field: '#1B1C27',
  fieldLine: '#2A2B37',
  line: '#24252F',
  ink: '#F4F4F7',
  ink2: '#B9BBC7',
  ink3: '#8C8FA0',
  accent: '#7B6BFF',
  onAccent: '#FFFFFF',
  accentSoft: 'rgba(123,107,255,0.16)',
  accentGradient: GRADIENT,
  payShadow: 'none',
  good: '#3DD68C',
  bad: '#FF6B85',
  badSoft: 'rgba(255,107,133,0.1)',
  warn: '#EA580C',
  warnInk: '#FBBF24',
  warnSoft: 'rgba(251,191,36,0.14)',
  logoTile: '#1D1D28',
  scrim: 'rgba(0,0,0,0.62)',
};

/** What a merchant may change. Each colour can differ between light and dark. */
export interface VezmoAppearance {
  colors?: Partial<Record<Exclude<keyof SheetPalette, 'accentGradient' | 'payShadow'>, string | { light: string; dark: string }>>;
  /** Corner radius of fields and payment method rows, in points. Default 14. */
  cornerRadius?: number;
  /** A font family already loaded in the app (iOS: its PostScript or family name). */
  fontFamily?: string;
}

export function paletteFor(scheme: 'light' | 'dark', appearance?: VezmoAppearance): SheetPalette {
  const base = scheme === 'dark' ? DARK : LIGHT;
  const overrides = appearance?.colors ?? {};
  // A merchant's own accent replaces the Vezmo gradient (and its indigo lift) on the Pay button.
  const out = {
    ...base,
    ...(overrides.accent
      ? { accentGradient: null, payShadow: base.payShadow === 'none' ? 'none' : '0 1px 2px rgba(16,24,64,0.16), 0 8px 18px -8px rgba(16,24,64,0.3)' }
      : {}),
  };
  for (const [key, value] of Object.entries(overrides) as [Exclude<keyof SheetPalette, 'accentGradient' | 'payShadow'>, unknown][]) {
    if (typeof value === 'string') out[key] = value;
    else if (value && typeof value === 'object') {
      const v = (value as { light: string; dark: string })[scheme];
      if (v) out[key] = v;
    }
  }
  return out;
}

export const DEFAULT_RADIUS = 14;

/** The element's appearance (card fields, method rows), from the sheet's palette. */
export function elementAppearance(p: SheetPalette, appearance?: VezmoAppearance) {
  return {
    colors: {
      primary: p.accent,
      // The rows sit in the sheet's group, on the field colour.
      background: p.field,
      componentBackground: p.field,
      componentBorder: p.fieldLine,
      componentDivider: p.line,
      text: p.ink,
      textSecondary: p.ink3,
      componentText: p.ink,
      placeholderText: p.ink3,
      icon: p.ink2,
      danger: p.bad,
    },
    cornerRadius: appearance?.cornerRadius ?? DEFAULT_RADIUS,
    borderWidth: 1,
    // A flat list with a checkmark; the sheet draws the rounded group around it.
    row: { style: 'checkmark', insets: 6, separatorColor: p.line },
    ...(appearance?.fontFamily ? { fontFamily: appearance.fontFamily } : {}),
  };
}
