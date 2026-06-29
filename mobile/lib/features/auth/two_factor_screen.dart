import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/auth/auth_controller.dart';
import '../../core/auth/auth_models.dart';

class TwoFactorScreen extends ConsumerStatefulWidget {
  const TwoFactorScreen({super.key});

  @override
  ConsumerState<TwoFactorScreen> createState() => _TwoFactorScreenState();
}

class _TwoFactorScreenState extends ConsumerState<TwoFactorScreen> {
  final _codeCtrl = TextEditingController();
  bool _useBackup = false;
  bool _busy = false;
  String? _error;

  String get _twoFactorToken {
    final s = ref.read(authControllerProvider).value;
    if (s is TwoFactorRequired) return s.twoFactorToken;
    return '';
  }

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final token = _twoFactorToken;
    if (token.isEmpty) {
      setState(() => _error = 'Session expired. Please sign in again.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final twoFaApi = ref.read(vaultdocApiProvider).getTwoFactorApi();
      gen.Response<gen.AuthResponse> res;
      if (_useBackup) {
        res = await twoFaApi.verify2faBackup(
          twoFactorBackupRequest: gen.TwoFactorBackupRequest((b) => b
            ..twoFactorToken = token
            ..backupCode = _codeCtrl.text.trim()),
        );
      } else {
        res = await twoFaApi.verify2fa(
          twoFactorVerifyRequest: gen.TwoFactorVerifyRequest((b) => b
            ..twoFactorToken = token
            ..code = _codeCtrl.text.trim()),
        );
      }
      final session = AuthSession.fromGenerated(res.data!);
      await ref.read(authControllerProvider.notifier).adoptSession(session);
      if (!mounted) return;
      context.go('/');
    } on DioException catch (e) {
      setState(() => _error = ApiException.fromDio(e).message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Two-factor authentication')),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _useBackup ? 'Enter a backup code' : 'Enter your authenticator code',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: _codeCtrl,
                    keyboardType: _useBackup ? TextInputType.text : TextInputType.number,
                    maxLength: _useBackup ? 11 : 6,
                    decoration: InputDecoration(
                      labelText: _useBackup ? 'Backup code (XXX-XXX-XXX)' : '6-digit code',
                      counterText: '',
                    ),
                    onSubmitted: (_) => _submit(),
                  ),
                  const SizedBox(height: 16),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: TextStyle(color: colors.error)),
                    ),
                  FilledButton(
                    onPressed: _busy ? null : _submit,
                    child: _busy
                        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Verify'),
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: () => setState(() {
                      _useBackup = !_useBackup;
                      _codeCtrl.clear();
                      _error = null;
                    }),
                    child: Text(_useBackup ? '← Use TOTP code' : 'Use a backup code instead'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
