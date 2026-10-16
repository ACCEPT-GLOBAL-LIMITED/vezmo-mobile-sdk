import type * as React from 'react';
import {
  codegenNativeCommands,
  codegenNativeComponent,
  type CodegenTypes,
  type HostComponent,
  type ViewProps,
} from 'react-native';

/**
 * The native payment element: card fields, Apple Pay / Google Pay and the methods the business offers, drawn by the
 * platform's own UI. Inputs are JSON so the native side stays a thin host (see `elementConfig.ts`).
 */
export interface NativeProps extends ViewProps {
  /** Keys, account, wallets, return link and appearance. A change rebuilds the element. */
  configJson: string;
  /** What is charged: `{ amount, currency, paymentMethodTypes }`. A change updates the element in place. */
  intentJson: string;
  onLoaded?: CodegenTypes.DirectEventHandler<Readonly<{ ok: boolean }>>;
  onLoadFailed?: CodegenTypes.DirectEventHandler<Readonly<{ message: string }>>;
  onHeightChange?: CodegenTypes.DirectEventHandler<Readonly<{ height: CodegenTypes.Double }>>;
  onPaymentOptionChange?: CodegenTypes.DirectEventHandler<
    Readonly<{ paymentMethodType: string; label: string }>
  >;
  /** The buyer confirmed: here is the confirmation token for our server to price and confirm. */
  onConfirmationToken?: CodegenTypes.DirectEventHandler<Readonly<{ id: string }>>;
  /** `completed`, `canceled` or `failed` (with `message`). */
  onConfirmResult?: CodegenTypes.DirectEventHandler<Readonly<{ status: string; message: string }>>;
  /**
   * The card typed in the card form (the sheet draws the Card row itself): its label ("•••• 4242") and the network's
   * logo as a PNG data URI; both empty when there is no card yet.
   */
  onCardOptionChange?: CodegenTypes.DirectEventHandler<Readonly<{ label: string; image: string }>>;
}

interface NativeCommands {
  /** Pay with the selected method (opens 3D Secure, a wallet or a provider when needed). */
  confirm: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Answer `onConfirmationToken`: the intent's client secret, or an error message to show. */
  resolveConfirmation: (
    viewRef: React.ElementRef<HostComponent<NativeProps>>,
    clientSecret: string,
    errorMessage: string,
  ) => void;
  /** Unselect the element's method (the buyer chose one of the sheet's own: the card, UPI or Pix). */
  clearSelection: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Open the native card form (the Card row, or its Change). */
  presentCard: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Pay with the card from the card form. The outcome arrives in `onConfirmResult`. */
  confirmCard: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
}

export const Commands: NativeCommands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['confirm', 'resolveConfirmation', 'clearSelection', 'presentCard', 'confirmCard'],
});

export default codegenNativeComponent<NativeProps>(
  'VezmoPaymentElementView',
) as HostComponent<NativeProps>;
