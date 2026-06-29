import 'dart:io' show Platform;

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart';

import '../auth/auth_controller.dart';
import '../auth/token_storage.dart';

String _defaultBaseUrl() {
  const fromEnv = String.fromEnvironment('API_BASE_URL');
  if (fromEnv.isNotEmpty) return fromEnv;
  if (!kIsWeb && Platform.isAndroid) return 'http://10.0.2.2:8080';
  return 'http://localhost:8080';
}

/// The bare Dio used by the generated client. Other code should not call
/// methods on this directly — use the generated API classes via
/// [vaultdocApiProvider].
final apiDioProvider = Provider<Dio>((ref) {
  final dio = Dio(
    BaseOptions(
      baseUrl: _defaultBaseUrl(),
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
      contentType: 'application/json',
      validateStatus: (status) => status != null && status < 500,
    ),
  );

  dio.interceptors.add(_AuthInterceptor(ref));

  if (kDebugMode) {
    dio.interceptors.add(
      LogInterceptor(requestBody: false, responseBody: false, logPrint: (o) => debugPrint('$o')),
    );
  }

  return dio;
});

/// The generated client. Each tag-specific service (auth, system, etc.) is
/// reached via `client.getAuthApi()`.
final vaultdocApiProvider = Provider<Openapi>((ref) {
  return Openapi(dio: ref.watch(apiDioProvider), basePathOverride: _defaultBaseUrl());
});

class _AuthInterceptor extends Interceptor {
  _AuthInterceptor(this._ref);

  final Ref _ref;
  Future<String?>? _inFlightRefresh;

  bool _isAuthEndpoint(String path) =>
      path.startsWith('/api/v1/auth/') || path == '/api/v1/health';

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    if (!_isAuthEndpoint(options.path)) {
      final token = await _ref.read(tokenStorageProvider).readAccessToken();
      if (token != null) {
        options.headers['Authorization'] = 'Bearer $token';
      }
    }
    handler.next(options);
  }

  @override
  Future<void> onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) async {
    if (response.statusCode != 401 || _isAuthEndpoint(response.requestOptions.path)) {
      handler.next(response);
      return;
    }

    final newToken = await _refreshOnce();
    if (newToken == null) {
      handler.next(response);
      return;
    }

    final retryOpts = response.requestOptions
      ..headers['Authorization'] = 'Bearer $newToken';
    try {
      final retried = await _ref.read(apiDioProvider).fetch<dynamic>(retryOpts);
      handler.resolve(retried);
    } on DioException catch (e) {
      handler.reject(e);
    }
  }

  Future<String?> _refreshOnce() {
    return _inFlightRefresh ??= _ref.read(authControllerProvider.notifier).attemptRefresh()
      ..whenComplete(() => _inFlightRefresh = null);
  }
}
