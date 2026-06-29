import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_exception.dart';
import '../../core/auth/auth_controller.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _emailCtrl = TextEditingController();
  final _displayNameCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();

  String? _localError;
  bool _busy = false;
  bool _registered = false;
  String? _resendNotice;
  bool _resendBusy = false;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _displayNameCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_passwordCtrl.text.length < 12) {
      setState(() => _localError = 'Password must be at least 12 characters.');
      return;
    }
    setState(() {
      _busy = true;
      _localError = null;
    });
    try {
      await ref.read(authControllerProvider.notifier).register(
            email: _emailCtrl.text.trim(),
            password: _passwordCtrl.text,
            displayName: _displayNameCtrl.text.trim().isEmpty
                ? null
                : _displayNameCtrl.text.trim(),
          );
      if (!mounted) return;
      setState(() => _registered = true);
    } on ApiException catch (e) {
      setState(() => _localError = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    setState(() {
      _resendBusy = true;
      _resendNotice = null;
    });
    try {
      await ref.read(authControllerProvider.notifier).resendVerification(_emailCtrl.text.trim());
      if (mounted) setState(() => _resendNotice = 'Verification email sent.');
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _resendNotice = e.isType(problemVerificationRateLimited)
              ? 'Hold on — try again in a minute.'
              : (e.message);
        });
      }
    } finally {
      if (mounted) setState(() => _resendBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Create account'),
        leading: BackButton(onPressed: () => context.go('/login')),
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Card(
                elevation: 0,
                color: colors.surfaceContainerHigh,
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: _registered ? _buildCheckEmail(context, colors) : _buildForm(context, colors),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildForm(BuildContext context, ColorScheme colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Create your vault', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        Text(
          'Your documents, encrypted, intelligent, one search away.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 24),
        TextField(
          controller: _emailCtrl,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
          decoration: const InputDecoration(labelText: 'Email'),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _displayNameCtrl,
          autofillHints: const [AutofillHints.name],
          decoration: const InputDecoration(labelText: 'Display name (optional)'),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _passwordCtrl,
          obscureText: true,
          autofillHints: const [AutofillHints.newPassword],
          decoration: const InputDecoration(
            labelText: 'Password',
            helperText: 'At least 12 characters. Use a passphrase.',
          ),
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: 16),
        if (_localError != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(_localError!, style: TextStyle(color: colors.error)),
          ),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Create account'),
        ),
      ],
    );
  }

  Widget _buildCheckEmail(BuildContext context, ColorScheme colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Check your email', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 8),
        Text(
          'We sent a verification link to ${_emailCtrl.text.trim()}. Tap it to activate your vault.',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 24),
        if (_resendNotice != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(_resendNotice!, style: TextStyle(color: colors.primary)),
          ),
        OutlinedButton(
          onPressed: _resendBusy ? null : _resend,
          child: Text(_resendBusy ? 'Resending…' : 'Resend email'),
        ),
        const SizedBox(height: 12),
        TextButton(
          onPressed: () => setState(() {
            _registered = false;
            _resendNotice = null;
          }),
          child: const Text('Use a different email'),
        ),
      ],
    );
  }
}
