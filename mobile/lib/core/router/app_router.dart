import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/forgot_password_screen.dart';
import '../../features/auth/login_screen.dart';
import '../../features/auth/register_screen.dart';
import '../../features/auth/reset_password_screen.dart';
import '../../features/auth/two_factor_screen.dart';
import '../../features/auth/verify_email_screen.dart';
import '../../features/home/home_screen.dart';
import '../../features/settings/two_factor_settings_screen.dart';
import '../auth/auth_controller.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/',
    refreshListenable: _AuthRefreshListenable(ref),
    redirect: (context, state) {
      if (state.matchedLocation == '/verify') return null;
      if (state.matchedLocation == '/2fa') return null;
      if (state.matchedLocation == '/settings') return null;
      if (state.matchedLocation == '/forgot-password') return null;
      if (state.matchedLocation == '/reset-password') return null;

      final authState = ref.read(authControllerProvider);
      if (authState.isLoading) return null;

      final value = authState.value;
      if (value is TwoFactorRequired) return '/2fa';

      final authed = value is Authenticated;
      final goingToAuth = state.matchedLocation == '/login' || state.matchedLocation == '/register';

      if (!authed && !goingToAuth) return '/login';
      if (authed && goingToAuth) return '/';
      return null;
    },
    routes: [
      GoRoute(path: '/', builder: (_, __) => const HomeScreen()),
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      GoRoute(path: '/register', builder: (_, __) => const RegisterScreen()),
      // The twoFactorToken is read directly from the TwoFactorRequired auth state
      // inside TwoFactorScreen — no need to pass it as a route parameter.
      GoRoute(path: '/2fa', builder: (_, __) => const TwoFactorScreen()),
      GoRoute(
        path: '/settings',
        builder: (_, __) => const TwoFactorSettingsScreen(),
      ),
      GoRoute(path: '/forgot-password', builder: (_, __) => const ForgotPasswordScreen()),
      GoRoute(
        path: '/reset-password',
        builder: (context, state) => ResetPasswordScreen(
          token: state.uri.queryParameters['token'],
        ),
      ),
      GoRoute(
        path: '/verify',
        builder: (context, state) => VerifyEmailScreen(
          token: state.uri.queryParameters['token'],
        ),
      ),
    ],
  );
});

/// Bridges Riverpod's auth state into go_router's [Listenable]-based refresh.
class _AuthRefreshListenable extends ChangeNotifier {
  _AuthRefreshListenable(this._ref) {
    _subscription = _ref.listen(
      authControllerProvider,
      (_, __) => notifyListeners(),
    );
  }

  final Ref _ref;
  late final ProviderSubscription<dynamic> _subscription;

  @override
  void dispose() {
    _subscription.close();
    super.dispose();
  }
}
