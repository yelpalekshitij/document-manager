import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

class ResetPasswordScreen extends ConsumerStatefulWidget {
  const ResetPasswordScreen({super.key, required this.token});

  final String? token;

  @override
  ConsumerState<ResetPasswordScreen> createState() => _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends ConsumerState<ResetPasswordScreen> {
  final _pwCtrl = TextEditingController();
  bool _busy = false;
  bool _done = false;
  String? _error;

  @override
  void dispose() {
    _pwCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final token = widget.token;
    if (token == null || token.isEmpty) return;
    if (_pwCtrl.text.length < 12) {
      setState(() => _error = 'Password must be at least 12 characters.');
      return;
    }
    setState(() { _busy = true; _error = null; });
    try {
      final dio = ref.read(apiDioProvider);
      await dio.post<void>('/api/v1/auth/reset-password',
          data: {'token': token, 'newPassword': _pwCtrl.text});
      if (mounted) setState(() => _done = true);
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
      appBar: AppBar(title: const Text('Set new password')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (widget.token == null || widget.token!.isEmpty) ...[
                Text('Invalid link', style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Text('No reset token found.', style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 16),
                OutlinedButton(
                    onPressed: () => context.go('/forgot-password'),
                    child: const Text('Request a new link')),
              ] else if (_done) ...[
                Text('Password updated', style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Text('Sign in with your new password.',
                    style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 16),
                FilledButton(onPressed: () => context.go('/login'),
                    child: const Text('Go to sign in')),
              ] else ...[
                TextField(
                  controller: _pwCtrl,
                  obscureText: true,
                  autofillHints: const [AutofillHints.newPassword],
                  decoration: const InputDecoration(
                    labelText: 'New password',
                    helperText: 'At least 12 characters.',
                  ),
                  onSubmitted: (_) => _submit(),
                ),
                const SizedBox(height: 16),
                if (_error != null)
                  Padding(padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: TextStyle(color: colors.error))),
                FilledButton(
                  onPressed: _busy ? null : _submit,
                  child: _busy
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Update password'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
