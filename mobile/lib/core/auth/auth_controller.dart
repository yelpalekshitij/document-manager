import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

import '../api/api_client.dart';
import '../api/api_exception.dart';
import 'auth_models.dart';
import 'token_storage.dart';

@immutable
sealed class AuthState {
  const AuthState();
}

class Unauthenticated extends AuthState {
  const Unauthenticated({this.lastError});
  final String? lastError;
}

class Authenticated extends AuthState {
  const Authenticated(this.session);
  final AuthSession session;
}

/// Emitted when the user passed the password check but 2FA is enabled.
/// The router should push to [TwoFactorScreen] when this state is reached.
class TwoFactorRequired extends AuthState {
  const TwoFactorRequired(this.twoFactorToken);
  final String twoFactorToken;
}

final tokenStorageProvider = Provider<TokenStorage>((_) => TokenStorage());

final authApiProvider = Provider<gen.AuthApi>((ref) => ref.watch(vaultdocApiProvider).getAuthApi());

class AuthController extends AsyncNotifier<AuthState> {
  late final gen.AuthApi _api = ref.read(authApiProvider);
  late final TokenStorage _storage = ref.read(tokenStorageProvider);

  @override
  Future<AuthState> build() async {
    final refreshToken = await _storage.readRefreshToken();
    if (refreshToken == null) {
      return const Unauthenticated();
    }
    try {
      final res = await _api.refresh(
        refreshRequest: gen.RefreshRequest((b) => b..refreshToken = refreshToken),
      );
      final auth = res.data;
      if (auth == null) {
        await _storage.clear();
        return const Unauthenticated();
      }
      await _storage.write(accessToken: auth.accessToken, refreshToken: auth.refreshToken);
      return Authenticated(AuthSession.fromGenerated(auth));
    } catch (_) {
      await _storage.clear();
      return const Unauthenticated();
    }
  }

  Future<void> register({
    required String email,
    required String password,
    String? displayName,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      try {
        await _api.register(
          registerRequest: gen.RegisterRequest((b) => b
            ..email = email
            ..password = password
            ..displayName = displayName),
        );
      } on DioException catch (e) {
        throw ApiException.fromDio(e);
      }
      // Register does NOT issue tokens — the user must verify their email first.
      return const Unauthenticated();
    });
  }

  Future<void> login({required String email, required String password}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      try {
        final res = await _api.login(
          loginRequest: gen.LoginRequest((b) => b
            ..email = email
            ..password = password),
        );
        final auth = res.data;
        // The /auth/login response can be either AuthResponse (accessToken present)
        // or TwoFactorChallengeResponse (twoFactorToken present).
        // The generated client tries to deserialize as AuthResponse; if accessToken
        // is null the server returned a 2FA challenge.
        if (auth == null || auth.accessToken.isEmpty) {
          // Extract the twoFactorToken from the raw response map.
          final raw = res.response?.data;
          if (raw is Map && raw.containsKey('twoFactorToken')) {
            return TwoFactorRequired(raw['twoFactorToken'] as String);
          }
          return const Unauthenticated();
        }
        await _storage.write(accessToken: auth.accessToken, refreshToken: auth.refreshToken);
        return Authenticated(AuthSession.fromGenerated(auth));
      } on DioException catch (e) {
        throw ApiException.fromDio(e);
      }
    });
  }

  Future<void> verifyEmail(String token) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      try {
        final res = await _api.verifyEmail(
          verifyEmailRequest: gen.VerifyEmailRequest((b) => b..token = token),
        );
        final auth = res.data!;
        await _storage.write(accessToken: auth.accessToken, refreshToken: auth.refreshToken);
        return Authenticated(AuthSession.fromGenerated(auth));
      } on DioException catch (e) {
        throw ApiException.fromDio(e);
      }
    });
  }

  Future<void> resendVerification(String email) async {
    try {
      await _api.resendVerification(
        resendVerificationRequest: gen.ResendVerificationRequest((b) => b..email = email),
      );
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  Future<void> logout() async {
    final refreshToken = await _storage.readRefreshToken();
    if (refreshToken != null) {
      try {
        await _api.logout(refreshRequest: gen.RefreshRequest((b) => b..refreshToken = refreshToken));
      } on DioException {
        // Best effort — local state will be cleared regardless.
      }
    }
    await _storage.clear();
    state = const AsyncData(Unauthenticated());
  }

  /// Promote an externally-issued session (e.g. from the passkey sign-in
  /// flow) into the controller's state. Persists tokens and transitions to
  /// [Authenticated].
  Future<void> adoptSession(AuthSession session) async {
    await _storage.write(
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
    );
    state = AsyncData(Authenticated(session));
  }

  /// Called by the Dio interceptor on a 401. Returns the new access token,
  /// or null if the session is gone.
  Future<String?> attemptRefresh() async {
    final refreshToken = await _storage.readRefreshToken();
    if (refreshToken == null) {
      state = const AsyncData(Unauthenticated());
      return null;
    }
    try {
      final res = await _api.refresh(
        refreshRequest: gen.RefreshRequest((b) => b..refreshToken = refreshToken),
      );
      final auth = res.data!;
      await _storage.write(accessToken: auth.accessToken, refreshToken: auth.refreshToken);
      state = AsyncData(Authenticated(AuthSession.fromGenerated(auth)));
      return auth.accessToken;
    } catch (_) {
      await _storage.clear();
      state = const AsyncData(Unauthenticated());
      return null;
    }
  }
}

final authControllerProvider = AsyncNotifierProvider<AuthController, AuthState>(AuthController.new);
