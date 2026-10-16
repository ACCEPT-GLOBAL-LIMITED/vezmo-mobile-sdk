/**
 * The Vezmo checkout's look on the phone: the same tokens as the hosted checkout (`checkoutTheme.ts` on the web),
 * opaque where the web uses glass. A merchant may override the accent, colours, corner radius and font.
 */
export interface SheetPalette {
  page: string;
  surface: string;
  field: string;
  fieldLine: string;
  line: string;
  ink: string;
  ink2: string;
  ink3: string;
  accent: string;
  onAccent: string;
  good: string;
  bad: string;
  scrim: string;
}

export const LIGHT: SheetPalette = {
  page: '#F3F5FA',
  surface: '#FFFFFF',
  field: '#FFFFFF',
  fieldLine: '#E1E3EC',
  line: '#E8EAF1',
  ink: '#0B0C14',
  ink2: '#454A5A',
  ink3: '#6B7083',
  accent: '#503AF2',
  onAccent: '#FFFFFF',
  good: '#0E8A55',
  bad: '#D12C4B',
  scrim: 'rgba(11,12,20,0.45)',
};

export const DARK: SheetPalette = {
  page: '#07080E',
  surface: '#14151F',
  field: '#1B1C27',
  fieldLine: '#2A2B37',
  line: '#24252F',
  ink: '#F4F4F7',
  ink2: '#B9BBC7',
  ink3: '#8C8FA0',
  accent: '#7B6BFF',
  onAccent: '#FFFFFF',
  good: '#3DD68C',
  bad: '#FF6B85',
  scrim: 'rgba(0,0,0,0.6)',
};

/** What a merchant may change. Each colour can differ between light and dark. */
export interface VezmoAppearance {
  colors?: Partial<Record<keyof SheetPalette, string | { light: string; dark: string }>>;
  /** Corner radius of fields and the Pay button, in points. Default 12. */
  cornerRadius?: number;
  /** A font family already loaded in the app (iOS: its PostScript or family name). */
  fontFamily?: string;
}

export function paletteFor(scheme: 'light' | 'dark', appearance?: VezmoAppearance): SheetPalette {
  const base = scheme === 'dark' ? DARK : LIGHT;
  const overrides = appearance?.colors ?? {};
  const out = { ...base };
  for (const [key, value] of Object.entries(overrides) as [keyof SheetPalette, unknown][]) {
    if (typeof value === 'string') out[key] = value;
    else if (value && typeof value === 'object') {
      const v = (value as { light: string; dark: string })[scheme];
      if (v) out[key] = v;
    }
  }
  return out;
}

/** The element's appearance (card fields, method rows), from the sheet's palette. */
export function elementAppearance(p: SheetPalette, appearance?: VezmoAppearance) {
  return {
    colors: {
      primary: p.accent,
      background: p.surface,
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
    cornerRadius: appearance?.cornerRadius ?? 12,
    borderWidth: 1,
    ...(appearance?.fontFamily ? { fontFamily: appearance.fontFamily } : {}),
  };
}
