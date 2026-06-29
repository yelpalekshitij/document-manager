// Adapter wrappers around the generated `built_value` models. The generated
// models are immutable Built classes that are unwieldy for UI consumers;
// these plain Dart classes are the shape screens actually want.

import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

enum AccountStatus { active, locked, pendingVerification, unknown }

AccountStatus _statusFrom(gen.AccountStatus? raw) {
  if (raw == null) return AccountStatus.unknown;
  switch (raw.name) {
    case 'ACTIVE':
      return AccountStatus.active;
    case 'LOCKED':
      return AccountStatus.locked;
    case 'PENDING_VERIFICATION':
      return AccountStatus.pendingVerification;
    default:
      return AccountStatus.unknown;
  }
}

class AuthUser {
  const AuthUser({
    required this.id,
    required this.email,
    required this.status,
    required this.createdAt,
    this.displayName,
  });

  final String id;
  final String email;
  final String? displayName;
  final AccountStatus status;
  final DateTime createdAt;

  factory AuthUser.fromGenerated(gen.MeResponse src) {
    return AuthUser(
      id: src.id,
      email: src.email,
      displayName: src.displayName,
      status: _statusFrom(src.status),
      createdAt: src.createdAt,
    );
  }
}

class AuthSession {
  const AuthSession({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresInSeconds,
    required this.user,
  });

  final String accessToken;
  final String refreshToken;
  final int expiresInSeconds;
  final AuthUser user;

  factory AuthSession.fromGenerated(gen.AuthResponse src) {
    return AuthSession(
      accessToken: src.accessToken,
      refreshToken: src.refreshToken,
      expiresInSeconds: src.expiresInSeconds,
      user: AuthUser.fromGenerated(src.user),
    );
  }
}
