package app.vaultdoc.vaultdoc_mobile

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register our platform-channel bridges. Each channel owns one feature.
        PasskeyMethodChannel.register(this, flutterEngine)
    }
}
