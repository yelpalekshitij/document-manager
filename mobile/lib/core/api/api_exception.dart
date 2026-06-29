import 'package:dio/dio.dart';

const problemUnverifiedAccount = 'https://vaultdoc.app/problems/unverified-account';
const problemInvalidVerificationToken = 'https://vaultdoc.app/problems/invalid-verification-token';
const problemVerificationRateLimited = 'https://vaultdoc.app/problems/verification-rate-limited';
const problemEmailAlreadyInUse = 'https://vaultdoc.app/problems/email-already-in-use';

/// Typed wrapper around an HTTP error. Carries the RFC 7807 Problem body so
/// screens can branch on `problem.type`.
class ApiException implements Exception {
  ApiException({
    required this.status,
    required this.message,
    this.type,
    this.title,
    this.detail,
  });

  final int status;
  final String message;
  final String? type;
  final String? title;
  final String? detail;

  bool isType(String t) => type == t;

  factory ApiException.fromDio(DioException e) {
    final res = e.response;
    if (res == null) {
      return ApiException(status: 0, message: e.message ?? 'Network error');
    }
    final data = res.data;
    if (data is Map) {
      final type = data['type']?.toString();
      final title = data['title']?.toString();
      final detail = data['detail']?.toString();
      return ApiException(
        status: res.statusCode ?? 0,
        type: type,
        title: title,
        detail: detail,
        message: detail ?? title ?? 'Request failed',
      );
    }
    return ApiException(
      status: res.statusCode ?? 0,
      message: res.statusMessage ?? 'Request failed',
    );
  }

  @override
  String toString() => 'ApiException($status, $type): $message';
}
