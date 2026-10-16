import type * as React from 'react';
import {
  codegenNativeCommands,
  codegenNativeComponent,
  type CodegenTypes,
  type HostComponent,
  type ViewProps,
} from 'react-native';

/**
 * Cards and wallets: the platform's native card form and its express wallet buttons (Apple Pay, Google Pay), confirmed
 * through Vezmo's server like the element. It draws only the wallet buttons; the sheet draws the Card row itself and
 * opens the card form with `presentCard`.
 */
export interface NativeProps extends ViewProps {
  /** Keys, account, wallets, return link and appearance. A change rebuilds the card flow. */
  configJson: string;
  /** What is charged: `{ amount, currency, card }`. A change updates the card flow in place. */
  intentJson: string;
  onLoaded?: CodegenTypes.DirectEventHandler<Readonly<{ ok: boolean }>>;
  onLoadFailed?: CodegenTypes.DirectEventHandler<Readonly<{ message: string }>>;
  /** The wallet buttons' height (0 when this phone has none). */
  onHeightChange?: CodegenTypes.DirectEventHandler<Readonly<{ height: CodegenTypes.Double }>>;
  /** The card typed in the card form: "•••• 4242" and the network's logo as a PNG data URI; empty when none. */
  onCardOptionChange?: CodegenTypes.DirectEventHandler<Readonly<{ label: string; image: string }>>;
  /** A wallet button was tapped ("apple_pay", "google_pay"): the payment has started. */
  onWalletTap?: CodegenTypes.DirectEventHandler<Readonly<{ wallet: string }>>;
  /** The buyer confirmed: here is the confirmation token for our server to price and confirm. */
  onConfirmationToken?: CodegenTypes.DirectEventHandler<Readonly<{ id: string }>>;
  /** `completed`, `canceled` or `failed` (with `message`). */
  onConfirmResult?: CodegenTypes.DirectEventHandler<Readonly<{ status: string; message: string }>>;
}

interface NativeCommands {
  /** Open the native card form (the Card row, or its Change). */
  presentCard: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Pay with the card from the card form. The outcome arrives in `onConfirmResult`. */
  confirmCard: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Answer `onConfirmationToken`: the intent's client secret, or an error message to show. */
  resolveConfirmation: (
    viewRef: React.ElementRef<HostComponent<NativeProps>>,
    clientSecret: string,
    errorMessage: string,
  ) => void;
}

export const Commands: NativeCommands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['presentCard', 'confirmCard', 'resolveConfirmation'],
});

export default codegenNativeComponent<NativeProps>('VezmoCardFlowView') as HostComponent<NativeProps>;
