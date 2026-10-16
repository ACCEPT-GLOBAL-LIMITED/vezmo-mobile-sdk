package com.vezmo.reactnative

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.VezmoPaymentElementViewManagerDelegate
import com.facebook.react.viewmanagers.VezmoPaymentElementViewManagerInterface

@ReactModule(name = VezmoPaymentElementViewManager.NAME)
class VezmoPaymentElementViewManager :
  SimpleViewManager<VezmoPaymentElementView>(),
  VezmoPaymentElementViewManagerInterface<VezmoPaymentElementView> {
  private val delegate: ViewManagerDelegate<VezmoPaymentElementView> = VezmoPaymentElementViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<VezmoPaymentElementView> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): VezmoPaymentElementView = VezmoPaymentElementView(context)

  @ReactProp(name = "configJson")
  override fun setConfigJson(
    view: VezmoPaymentElementView,
    value: String?,
  ) {
    view.setConfigJson(value)
  }

  @ReactProp(name = "intentJson")
  override fun setIntentJson(
    view: VezmoPaymentElementView,
    value: String?,
  ) {
    view.setIntentJson(value)
  }

  override fun onDropViewInstance(view: VezmoPaymentElementView) {
    view.dispose()
    super.onDropViewInstance(view)
  }

  override fun onAfterUpdateTransaction(view: VezmoPaymentElementView) {
    super.onAfterUpdateTransaction(view)
    view.commit()
  }

  override fun confirm(view: VezmoPaymentElementView) {
    view.confirm()
  }

  override fun resolveConfirmation(
    view: VezmoPaymentElementView,
    clientSecret: String,
    errorMessage: String,
  ) {
    view.resolveConfirmation(clientSecret, errorMessage)
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
    mutableMapOf(
      "topLoaded" to mapOf("registrationName" to "onLoaded"),
      "topLoadFailed" to mapOf("registrationName" to "onLoadFailed"),
      "topHeightChange" to mapOf("registrationName" to "onHeightChange"),
      "topPaymentOptionChange" to mapOf("registrationName" to "onPaymentOptionChange"),
      "topConfirmationToken" to mapOf("registrationName" to "onConfirmationToken"),
      "topConfirmResult" to mapOf("registrationName" to "onConfirmResult"),
    )

  companion object {
    const val NAME = "VezmoPaymentElementView"
  }
}
