import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

import '../api/api_client.dart';
import '../api/api_exception.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_models.dart';
import 'passkey_platform.dart';

/// Orchestrates the four WebAuthn ceremonies for the mobile client. Mirrors
/// the web's PasskeyClient shape: the backend speaks W3C JSON, the platform
/// channel speaks W3C JSON, and this class is the string pipe in between.
class PasskeyService {
  PasskeyService(this._api, this._platform, this._auth);

  final gen.PasskeyApi _api;
  final PasskeyPlatform _platform;
  final AuthController _auth;

  Future<bool> isSupported() => _platform.isSupported();

  /// Caller must be already authenticated. Returns the persisted passkey
  /// summary on success.
  Future<gen.PasskeySummary> addPasskey({String? label}) async {
    final beginRes = await _wrap(() => _api.beginPasskeyRegistration());
    final challengeId = beginRes.data!.challengeId;
    final optionsJson = jsonEncode(beginRes.data!.publicKey);

    final credentialJson = await _platform.createCredential(optionsJson);
    final credential = jsonDecode(credentialJson) as Map<String, dynamic>;

    final finishRes = await _wrap(() => _api.finishPasskeyRegistration(
          passkeyRegistrationRequest: gen.PasskeyRegistrationRequest((b) => b
            ..challengeId = challengeId
            ..label = label
            ..credential.replace(credential)),
        ));
    return finishRes.data!;
  }

  /// Sign-in flow. Discovers the credential via the OS, posts the assertion,
  /// and adopts the resulting session into the auth controller.
  Future<AuthSession> signIn() async {
    final beginRes = await _wrap(() => _api.beginPasskeySignin());
    final challengeId = beginRes.data!.challengeId;
    final optionsJson = jsonEncode(beginRes.data!.publicKey);

    final assertionJson = await _platform.getCredential(optionsJson);
    final assertion = jsonDecode(assertionJson) as Map<String, dynamic>;

    final finishRes = await _wrap(() => _api.finishPasskeySignin(
          passkeySigninRequest: gen.PasskeySigninRequest((b) => b
            ..challengeId = challengeId
            ..assertion.replace(assertion)),
        ));
    final authResponse = finishRes.data!;
    final session = AuthSession.fromGenerated(authResponse);
    _auth.adoptSession(session);
    return session;
  }

  Future<List<gen.PasskeySummary>> list() async {
    final res = await _wrap(() => _api.listPasskeys());
    return res.data!.passkeys.toList();
  }

  Future<void> delete(String id) async {
    await _wrap(() => _api.deletePasskey(id: id));
  }

  Future<Response<T>> _wrap<T>(Future<Response<T>> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final passkeyApiProvider =
    Provider<gen.PasskeyApi>((ref) => ref.watch(vaultdocApiProvider).getPasskeyApi());

final passkeyServiceProvider = Provider<PasskeyService>((ref) {
  return PasskeyService(
    ref.watch(passkeyApiProvider),
    ref.watch(passkeyPlatformProvider),
    ref.read(authControllerProvider.notifier),
  );
});
