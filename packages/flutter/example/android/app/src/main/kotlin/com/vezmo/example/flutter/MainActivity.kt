package com.vezmo.example.flutter

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

// The Vezmo sheet reports back through the activity's result registry: a FlutterFragmentActivity has one.
class MainActivity : FlutterFragmentActivity() {
    // The end-to-end runs launch the app with `offerCurrency` (an intent extra): Dart asks for it.
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vezmo_example/launch").setMethodCallHandler { call, result ->
            if (call.method == "offerCurrency") result.success(intent.getStringExtra("offerCurrency")) else result.notImplemented()
        }
    }
}
