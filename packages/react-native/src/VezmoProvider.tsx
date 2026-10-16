import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import { DEFAULT_API_BASE, VezmoApi } from './api';
import { CheckoutSession } from './CheckoutSession';
import { CheckoutSheet } from './CheckoutSheet';
import type { WalletOptions } from './elementConfig';
import type { VezmoAppearance } from './theme';
import type { CheckoutResult, VezmoError } from './types';

export interface VezmoProviderProps extends WalletOptions {
  children: ReactNode;
  /**
   * The link that opens your app after a bank app, a pay-later provider or 3D Secure, registered under
   * Developers → Mobile apps (for example `acme://vezmo`). Required for redirect-based methods.
   */
  returnUrl?: string;
  /** Light, dark, or follow the phone (default). */
  style?: 'automatic' | 'light' | 'dark';
  appearance?: VezmoAppearance;
  /** Vezmo's API. Leave as is; for Vezmo's own testing only. */
  apiBase?: string;
}

export interface PrepareOptions {
  /** From your server: the API checkout session's `clientToken` (a one-time payment). */
  clientToken?: string;
  /**
   * From your server: a subscription's `checkoutToken` (`POST /merchant/subscription`), to start it in the app: the
   * first payment, or a free trial with the card saved. Pass this or `clientToken`.
   */
  subscriptionToken?: string;
  /** Shown at the top of the sheet. Defaults to the business name on the session. */
  merchantDisplayName?: string;
}

interface VezmoContextValue {
  prepareCheckout(options: PrepareOptions): Promise<{ error?: VezmoError }>;
  presentCheckout(): Promise<CheckoutResult>;
}

const VezmoContext = createContext<VezmoContextValue | null>(null);

/**
 * Wrap your app (or the screens that take payments) once. The payment sheet is drawn as an overlay inside this
 * provider, so put it high in the tree.
 */
export function VezmoProvider(props: VezmoProviderProps) {
  const [presented, setPresented] = useState<{ session: CheckoutSession; merchantDisplayName?: string } | null>(null);
  const prepared = useRef<{ session: CheckoutSession; merchantDisplayName?: string } | null>(null);
  const resolver = useRef<((r: CheckoutResult) => void) | null>(null);
  const api = useMemo(
    () => new VezmoApi(props.apiBase ?? DEFAULT_API_BASE, Platform.OS === 'android' ? 'android' : 'ios'),
    [props.apiBase],
  );

  const prepareCheckout = useCallback(
    async ({ clientToken, subscriptionToken, merchantDisplayName }: PrepareOptions) => {
      if (!clientToken === !subscriptionToken) {
        return { error: { code: 'config' as const, message: 'Pass a clientToken or a subscriptionToken.' } };
      }
      const session = new CheckoutSession(
        api,
        (clientToken || subscriptionToken)!,
        props.returnUrl ?? null,
        subscriptionToken ? 'subscription' : 'payment',
      );
      await session.load();
      const phase = session.getSnapshot().phase;
      if (phase.name === 'closed' && phase.result.status === 'failed') {
        prepared.current = null;
        return { error: phase.result.error };
      }
      prepared.current = { session, merchantDisplayName };
      return {};
    },
    [api, props.returnUrl],
  );

  const presentCheckout = useCallback(() => {
    const next = prepared.current;
    if (!next) {
      return Promise.resolve<CheckoutResult>({
        status: 'failed',
        error: { code: 'config', message: 'Call prepareCheckout() before presentCheckout().' },
      });
    }
    return new Promise<CheckoutResult>((resolve) => {
      resolver.current = resolve;
      setPresented(next);
    });
  }, []);

  const onClose = useCallback((result: CheckoutResult) => {
    setPresented(null);
    prepared.current = null;
    resolver.current?.(result);
    resolver.current = null;
  }, []);

  const value = useMemo(() => ({ prepareCheckout, presentCheckout }), [prepareCheckout, presentCheckout]);

  return (
    <VezmoContext.Provider value={value}>
      <View style={styles.root}>
        {props.children}
        {presented && (
          <CheckoutSheet
            session={presented.session}
            merchantDisplayName={presented.merchantDisplayName}
            returnUrl={props.returnUrl ?? null}
            style={props.style}
            appearance={props.appearance}
            wallets={{ applePay: props.applePay, googlePay: props.googlePay }}
            onClose={onClose}
          />
        )}
      </View>
    </VezmoContext.Provider>
  );
}

/**
 * `prepareCheckout({ clientToken })` loads the session (call it as soon as you have the token); `presentCheckout()`
 * opens the sheet and resolves with the result. Neither throws.
 */
export function useVezmoCheckout(): VezmoContextValue {
  const ctx = useContext(VezmoContext);
  if (!ctx) throw new Error('useVezmoCheckout must be used inside <VezmoProvider>.');
  return ctx;
}

const styles = StyleSheet.create({ root: { flex: 1 } });
