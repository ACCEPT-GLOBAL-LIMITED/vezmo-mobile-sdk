import { forwardRef, useImperativeHandle, useRef, useState } from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import NativeElement, { Commands } from './VezmoPaymentElementViewNativeComponent';

export interface PaymentElementHandle {
  /** Pay with the selected method. The outcome arrives in `onConfirmResult`. */
  confirm(): void;
  /** Answer `onConfirmationToken` with the intent's client secret, or an error message to show. */
  resolveConfirmation(clientSecret: string | null, errorMessage?: string): void;
}

export interface PaymentElementProps {
  configJson: string;
  intentJson: string;
  style?: StyleProp<ViewStyle>;
  onLoaded?: () => void;
  onLoadFailed?: (message: string) => void;
  onPaymentOptionChange?: (option: { paymentMethodType: string; label: string } | null) => void;
  onConfirmationToken: (id: string) => void;
  onConfirmResult: (status: 'completed' | 'canceled' | 'failed', message: string) => void;
}

/** The native element, sized to its own content (it reports its height as it changes). */
export const PaymentElement = forwardRef<PaymentElementHandle, PaymentElementProps>(
  function PaymentElement(props, ref) {
    const native = useRef<React.ElementRef<typeof NativeElement>>(null);
    const [height, setHeight] = useState(1);

    useImperativeHandle(ref, () => ({
      confirm() {
        if (native.current) Commands.confirm(native.current);
      },
      resolveConfirmation(clientSecret, errorMessage) {
        if (native.current) Commands.resolveConfirmation(native.current, clientSecret ?? '', errorMessage ?? '');
      },
    }));

    return (
      <NativeElement
        ref={native}
        style={[{ width: '100%', height }, props.style]}
        configJson={props.configJson}
        intentJson={props.intentJson}
        onLoaded={() => props.onLoaded?.()}
        onLoadFailed={(e) => props.onLoadFailed?.(e.nativeEvent.message)}
        onHeightChange={(e) => setHeight(Math.max(1, Math.ceil(e.nativeEvent.height)))}
        onPaymentOptionChange={(e) =>
          props.onPaymentOptionChange?.(e.nativeEvent.paymentMethodType ? { ...e.nativeEvent } : null)
        }
        onConfirmationToken={(e) => props.onConfirmationToken(e.nativeEvent.id)}
        onConfirmResult={(e) =>
          props.onConfirmResult(
            e.nativeEvent.status as 'completed' | 'canceled' | 'failed',
            e.nativeEvent.message,
          )
        }
      />
    );
  },
);
