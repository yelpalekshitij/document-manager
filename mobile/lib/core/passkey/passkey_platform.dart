import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Maps to the Kotlin [PasskeyMethodChannel] companion CHANNEL_NAME.
const _channel = MethodChannel('app.vaultdoc/passkey');

/// Typed exception surface for the platform bridge. Codes mirror the
/// Kotlin handler: CANCELLED, NO_CREDENTIAL, INTERRUPTED,
/// PROVIDER_CONFIGURATION, UNSUPPORTED, UNKNOWN.
class PasskeyPlatformException implements Exception {
  PasskeyPlatformException({required this.code, required this.message});
  final String code;
  final String message;

  bool get isCancelled => code == 'CANCELLED';
  bool get isNoCredential => code == 'NO_CREDENTIAL';
  bool get isUnsupported => code == 'UNSUPPORTED';

  @override
  String toString() => 'PasskeyPlatformException($code): $message';
}

/// Thin async wrapper around the Android Credential Manager bridge. The JSON
/// strings are W3C-shape — same as the backend returns and accepts.
class PasskeyPlatform {
  PasskeyPlatform({MethodChannel? channel}) : _channel = channel ?? _channel;

  // ignore: unused_field — the const default value is hidden behind the override.
  final MethodChannel _channel;

  /// True when Credential Manager is reachable on this device (API 28+).
  Future<bool> isSupported() async {
    try {
      final result = await _channel.invokeMethod<bool>('isSupported');
      return result ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      // Running on a host where the method channel isn't registered
      // (e.g., a desktop test environment).
      return false;
    }
  }

  /// Run the registration ceremony's OS half. Pass the JSON from
  /// `/auth/passkey/register/begin`'s `publicKey` field. Returns the JSON
  /// to send back to `/auth/passkey/register/finish`'s `credential` field.
  Future<String> createCredential(String requestJson) =>
      _invoke('createCredential', {'requestJson': requestJson});

  /// Run the sign-in ceremony's OS half. Pass the JSON from
  /// `/auth/passkey/signin/begin`'s `publicKey` field. Returns the JSON
  /// to send to `/auth/passkey/signin/finish`'s `assertion` field.
  Future<String> getCredential(String requestJson) =>
      _invoke('getCredential', {'requestJson': requestJson});

  Future<String> _invoke(String method, Map<String, dynamic> args) async {
    try {
      final result = await _channel.invokeMethod<String>(method, args);
      if (result == null) {
        throw PasskeyPlatformException(code: 'UNKNOWN', message: 'Empty result');
      }
      return result;
    } on PlatformException catch (e) {
      throw PasskeyPlatformException(
        code: e.code,
        message: e.message ?? 'Unknown error',
      );
    } on MissingPluginException catch (e) {
      throw PasskeyPlatformException(
        code: 'UNSUPPORTED',
        message: e.message ?? 'Passkey channel not registered',
      );
    }
  }
}

final passkeyPlatformProvider = Provider<PasskeyPlatform>((ref) => PasskeyPlatform());
