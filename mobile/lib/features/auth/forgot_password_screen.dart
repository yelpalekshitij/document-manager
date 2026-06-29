import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  ConsumerState<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _emailCtrl = TextEditingController();
  bool _busy = false;
  bool _sent = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() { _busy = true; _error = null; });
    try {
      final dio = ref.read(apiDioProvider);
      await dio.post<void>('/api/v1/auth/forgot-password',
          data: {'email': _emailCtrl.text.trim()});
      if (mounted) setState(() { _sent = true; });
    } on DioException catch (e) {
      final ex = ApiException.fromDio(e);
      setState(() => _error = ex.status == 429
          ? 'Too many requests — wait a minute and try again.'
          : ex.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Forgot password')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: _sent ? _buildSent() : _buildForm(colors),
        ),
      ),
    );
  }

  Widget _buildForm(ColorScheme colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Enter your email and we\'ll send a reset link.',
            style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 20),
        TextField(
          controller: _emailCtrl,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
          decoration: const InputDecoration(labelText: 'Email'),
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
              : const Text('Send reset link'),
        ),
        const SizedBox(height: 8),
        TextButton(onPressed: () => context.go('/login'), child: const Text('← Back to sign in')),
      ],
    );
  }

  Widget _buildSent() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Check your email', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        Text('If ${_emailCtrl.text.trim()} has an account, a reset link is on its way.',
            style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 24),
        OutlinedButton(onPressed: () => context.go('/login'), child: const Text('Back to sign in')),
      ],
    );
  }
}
