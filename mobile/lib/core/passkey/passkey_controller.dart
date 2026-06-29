import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

import '../api/api_exception.dart';
import 'passkey_platform.dart';
import 'passkey_service.dart';

@immutable
class PasskeysState {
  const PasskeysState({
    required this.supported,
    required this.loading,
    required this.busy,
    required this.passkeys,
    this.error,
  });

  final bool supported;
  final bool loading;
  final bool busy;
  final List<gen.PasskeySummary> passkeys;
  final String? error;

  PasskeysState copyWith({
    bool? supported,
    bool? loading,
    bool? busy,
    List<gen.PasskeySummary>? passkeys,
    String? error,
    bool clearError = false,
  }) {
    return PasskeysState(
      supported: supported ?? this.supported,
      loading: loading ?? this.loading,
      busy: busy ?? this.busy,
      passkeys: passkeys ?? this.passkeys,
      error: clearError ? null : (error ?? this.error),
    );
  }

  static const initial = PasskeysState(
    supported: false,
    loading: false,
    busy: false,
    passkeys: <gen.PasskeySummary>[],
    error: null,
  );
}

class PasskeyController extends Notifier<PasskeysState> {
  late final PasskeyService _service = ref.read(passkeyServiceProvider);
  late final PasskeyPlatform _platform = ref.read(passkeyPlatformProvider);

  @override
  PasskeysState build() {
    // Capability probe runs lazily — call detectSupport() from a screen's
    // init to populate the state asynchronously.
    return PasskeysState.initial;
  }

  Future<void> detectSupport() async {
    final supported = await _platform.isSupported();
    state = state.copyWith(supported: supported);
  }

  Future<void> refresh() async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final passkeys = await _service.list();
      state = state.copyWith(loading: false, passkeys: passkeys);
    } on ApiException catch (e) {
      state = state.copyWith(loading: false, error: e.message);
    }
  }

  Future<void> addPasskey({String? label}) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      final summary = await _service.addPasskey(label: label);
      state = state.copyWith(
        busy: false,
        passkeys: <gen.PasskeySummary>[summary, ...state.passkeys],
      );
    } on PasskeyPlatformException catch (e) {
      state = state.copyWith(busy: false, error: _platformMessage(e));
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Returns true if sign-in succeeded.
  Future<bool> signIn() async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      await _service.signIn();
      state = state.copyWith(busy: false);
      return true;
    } on PasskeyPlatformException catch (e) {
      state = state.copyWith(busy: false, error: _platformMessage(e));
      return false;
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
      return false;
    }
  }

  Future<void> delete(String id) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      await _service.delete(id);
      state = state.copyWith(
        busy: false,
        passkeys: state.passkeys.where((p) => p.id != id).toList(),
      );
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  String _platformMessage(PasskeyPlatformException e) {
    if (e.isCancelled) return 'Passkey prompt was cancelled.';
    if (e.isNoCredential) return 'No matching passkey on this device.';
    if (e.isUnsupported) return 'Passkeys aren\'t supported on this Android version.';
    return e.message;
  }
}

final passkeyControllerProvider =
    NotifierProvider<PasskeyController, PasskeysState>(PasskeyController.new);
