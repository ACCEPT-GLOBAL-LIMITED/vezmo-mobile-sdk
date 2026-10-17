package com.vezmo.example.flutter

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

// The Vezmo sheet reports back through the activity's result registry: a FlutterFragmentActivity has one.
class MainActivity : FlutterFragmentActivity() {
    // The end-to-end runs launch the app with arguments (intent extras: `offerCurrency`, `track`): Dart asks for them.
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vezmo_example/launch").setMethodCallHandler { call, result ->
            val name = call.arguments as? String
            if (call.method == "argument" && name != null) result.success(intent.getStringExtra(name)) else result.notImplemented()
        }
    }
}
