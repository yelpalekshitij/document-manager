import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vaultdoc_mobile/features/auth/login_screen.dart';
import 'package:vaultdoc_mobile/features/auth/register_screen.dart';
import 'package:vaultdoc_mobile/features/auth/verify_email_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    // Default the passkey channel to "unsupported" so the login screen doesn't
    // render the passkey CTA in screens that don't care about it.
    const channel = MethodChannel('app.vaultdoc/passkey');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'isSupported') return false;
      return null;
    });
  });

  tearDown(() {
    const channel = MethodChannel('app.vaultdoc/passkey');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('Login screen renders brand, fields, and submit button', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: LoginScreen())));
    expect(find.text('VaultDoc'), findsOneWidget);
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.byType(TextField), findsNWidgets(2));
  });

  testWidgets('Passkey button hidden when Credential Manager unsupported', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: LoginScreen())));
    await tester.pumpAndSettle();
    expect(find.text('Sign in with a passkey'), findsNothing);
  });

  testWidgets('Passkey button visible when supported=true', (tester) async {
    const channel = MethodChannel('app.vaultdoc/passkey');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'isSupported') return true;
      return null;
    });

    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: LoginScreen())));
    await tester.pumpAndSettle();
    expect(find.text('Sign in with a passkey'), findsOneWidget);
  });

  testWidgets('Register screen renders three fields and CTA', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: RegisterScreen())));
    expect(find.text('Create your vault'), findsOneWidget);
    expect(find.text('Create account'), findsOneWidget);
    expect(find.byType(TextField), findsNWidgets(3));
  });

  testWidgets('Verify-email shows failure state when no token is provided', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: VerifyEmailScreen(token: null))),
    );
    await tester.pump();
    expect(find.text("That link didn't work"), findsOneWidget);
    expect(find.text('Back to sign-in'), findsOneWidget);
  });
}
