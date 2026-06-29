import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_exception.dart';
import '../../core/auth/auth_controller.dart';
import '../../core/passkey/passkey_controller.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  String? _localError;
  bool _busy = false;
  bool _unverified = false;
  String? _resendNotice;
  bool _resendBusy = false;

  @override
  void initState() {
    super.initState();
    // Probe for Credential Manager once on screen entry. The button shows
    // only after this completes positively.
    Future.microtask(() => ref.read(passkeyControllerProvider.notifier).detectSupport());
  }

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _signInWithPasskey() async {
    final controller = ref.read(passkeyControllerProvider.notifier);
    final ok = await controller.signIn();
    if (!mounted) return;
    if (ok) {
      context.go('/');
    }
    // Errors are surfaced via the controller's state.error and rendered below.
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _localError = null;
      _unverified = false;
      _resendNotice = null;
    });
    try {
      await ref.read(authControllerProvider.notifier).login(
            email: _emailCtrl.text.trim(),
            password: _passwordCtrl.text,
          );
      if (!mounted) return;
      context.go('/');
    } on ApiException catch (e) {
      setState(() {
        if (e.isType(problemUnverifiedAccount)) {
          _unverified = true;
        } else {
          _localError = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    if (_emailCtrl.text.trim().isEmpty) {
      setState(() => _localError = 'Enter your email first.');
      return;
    }
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
              ? 'Try again in a minute.'
              : e.message;
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
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Card(
                elevation: 0,
                color: colors.surfaceContainerHigh,
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('VaultDoc', style: Theme.of(context).textTheme.headlineMedium),
                      const SizedBox(height: 4),
                      Text('Sign in to your vault.', style: Theme.of(context).textTheme.bodySmall),
                      const SizedBox(height: 24),
                      TextField(
                        controller: _emailCtrl,
                        keyboardType: TextInputType.emailAddress,
                        autofillHints: const [AutofillHints.email],
                        decoration: const InputDecoration(labelText: 'Email'),
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: _passwordCtrl,
                        obscureText: true,
                        autofillHints: const [AutofillHints.password],
                        decoration: const InputDecoration(labelText: 'Password'),
                        onSubmitted: (_) => _submit(),
                      ),
                      const SizedBox(height: 16),
                      if (_unverified)
                        Container(
                          margin: const EdgeInsets.only(bottom: 12),
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: colors.surface,
                            border: Border.all(color: colors.outlineVariant),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                "Your email isn't verified yet.",
                                style: Theme.of(context).textTheme.bodyMedium,
                              ),
                              const SizedBox(height: 8),
                              TextButton(
                                onPressed: _resendBusy ? null : _resend,
                                child: Text(_resendBusy ? 'Resending…' : 'Send a new link'),
                              ),
                              if (_resendNotice != null)
                                Text(
                                  _resendNotice!,
                                  style: TextStyle(color: colors.primary, fontSize: 12),
                                ),
                            ],
                          ),
                        ),
                      if (_localError != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: Text(_localError!, style: TextStyle(color: colors.error)),
                        ),
                      FilledButton(
                        onPressed: _busy ? null : _submit,
                        child: _busy
                            ? const SizedBox(
                                width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Text('Sign in'),
                      ),
                      _PasskeySignInBlock(onPressed: _signInWithPasskey),
                      const SizedBox(height: 8),
                      TextButton(
                        onPressed: _busy ? null : () => context.go('/register'),
                        child: const Text('Create account'),
                      ),
                      TextButton(
                        onPressed: _busy ? null : () => context.go('/forgot-password'),
                        child: const Text('Forgot password?'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PasskeySignInBlock extends ConsumerWidget {
  const _PasskeySignInBlock({required this.onPressed});

  final Future<void> Function() onPressed;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(passkeyControllerProvider);
    final colors = Theme.of(context).colorScheme;
    if (!state.supported) {
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 16),
        Row(
          children: [
            const Expanded(child: Divider()),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text('or', style: Theme.of(context).textTheme.labelSmall),
            ),
            const Expanded(child: Divider()),
          ],
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          icon: const Icon(Icons.fingerprint),
          onPressed: state.busy ? null : () => onPressed(),
          label: Text(state.busy ? 'Waiting for passkey…' : 'Sign in with a passkey'),
        ),
        if (state.error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(state.error!, style: TextStyle(color: colors.error, fontSize: 12)),
          ),
      ],
    );
  }
}
