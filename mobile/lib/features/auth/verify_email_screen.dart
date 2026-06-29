import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_exception.dart';
import '../../core/auth/auth_controller.dart';

class VerifyEmailScreen extends ConsumerStatefulWidget {
  const VerifyEmailScreen({super.key, required this.token});

  final String? token;

  @override
  ConsumerState<VerifyEmailScreen> createState() => _VerifyEmailScreenState();
}

enum _Phase { verifying, done, failed }

class _VerifyEmailScreenState extends ConsumerState<VerifyEmailScreen> {
  _Phase _phase = _Phase.verifying;
  String _message = 'Activating your vault…';

  @override
  void initState() {
    super.initState();
    final token = widget.token;
    if (token == null || token.isEmpty) {
      _phase = _Phase.failed;
      _message = 'No verification token was provided.';
      return;
    }
    Future.microtask(_run);
  }

  Future<void> _run() async {
    try {
      await ref.read(authControllerProvider.notifier).verifyEmail(widget.token!);
      if (!mounted) return;
      setState(() {
        _phase = _Phase.done;
        _message = 'Verified — taking you to your vault.';
      });
      await Future<void>.delayed(const Duration(milliseconds: 600));
      if (!mounted) return;
      context.go('/');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _phase = _Phase.failed;
        _message = e.message;
      });
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
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Card(
                elevation: 0,
                color: colors.surfaceContainerHigh,
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        switch (_phase) {
                          _Phase.verifying => Icons.mark_email_read_outlined,
                          _Phase.done => Icons.verified_outlined,
                          _Phase.failed => Icons.error_outline,
                        },
                        size: 48,
                        color: _phase == _Phase.failed ? colors.error : colors.primary,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        switch (_phase) {
                          _Phase.verifying => 'Verifying your email…',
                          _Phase.done => 'Email verified',
                          _Phase.failed => "That link didn't work",
                        },
                        style: Theme.of(context).textTheme.headlineSmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),
                      Text(_message, style: Theme.of(context).textTheme.bodyMedium, textAlign: TextAlign.center),
                      if (_phase == _Phase.failed) ...[
                        const SizedBox(height: 24),
                        FilledButton(
                          onPressed: () => context.go('/login'),
                          child: const Text('Back to sign-in'),
                        ),
                      ],
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
