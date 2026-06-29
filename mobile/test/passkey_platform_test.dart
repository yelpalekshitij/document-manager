import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vaultdoc_mobile/core/passkey/passkey_platform.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('app.vaultdoc/passkey');
  late List<MethodCall> calls;

  setUp(() {
    calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'isSupported':
          return true;
        case 'createCredential':
          return '{"id":"credId","response":{"attestationObject":"AA","clientDataJSON":"BB"}}';
        case 'getCredential':
          return '{"id":"credId","response":{"authenticatorData":"AA","clientDataJSON":"BB","signature":"CC"}}';
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('isSupported returns the bool from the platform', () async {
    final platform = PasskeyPlatform();
    expect(await platform.isSupported(), isTrue);
    expect(calls.single.method, 'isSupported');
  });

  test('createCredential forwards the requestJson string', () async {
    final platform = PasskeyPlatform();
    final result = await platform.createCredential('{"challenge":"abc","rp":{"id":"localhost"}}');
    expect(calls.single.method, 'createCredential');
    expect(calls.single.arguments['requestJson'], '{"challenge":"abc","rp":{"id":"localhost"}}');
    expect(result, contains('"attestationObject":"AA"'));
  });

  test('getCredential forwards the requestJson string', () async {
    final platform = PasskeyPlatform();
    final result = await platform.getCredential('{"challenge":"xyz"}');
    expect(calls.single.method, 'getCredential');
    expect(calls.single.arguments['requestJson'], '{"challenge":"xyz"}');
    expect(result, contains('"signature":"CC"'));
  });

  test('CANCELLED PlatformException maps to a typed PasskeyPlatformException', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'CANCELLED', message: 'User dismissed');
    });
    final platform = PasskeyPlatform();
    try {
      await platform.createCredential('{}');
      fail('expected PasskeyPlatformException');
    } on PasskeyPlatformException catch (e) {
      expect(e.isCancelled, isTrue);
      expect(e.code, 'CANCELLED');
    }
  });

  test('NO_CREDENTIAL maps to isNoCredential=true', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'NO_CREDENTIAL', message: 'None on device');
    });
    final platform = PasskeyPlatform();
    try {
      await platform.getCredential('{}');
      fail('expected PasskeyPlatformException');
    } on PasskeyPlatformException catch (e) {
      expect(e.isNoCredential, isTrue);
    }
  });

  test('MissingPluginException maps to UNSUPPORTED', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw MissingPluginException('channel not registered');
    });
    final platform = PasskeyPlatform();
    expect(await platform.isSupported(), isFalse);
    try {
      await platform.createCredential('{}');
      fail('expected PasskeyPlatformException');
    } on PasskeyPlatformException catch (e) {
      expect(e.isUnsupported, isTrue);
    }
  });

  test('isSupported swallows PlatformException and returns false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'UNKNOWN');
    });
    final platform = PasskeyPlatform();
    expect(await platform.isSupported(), isFalse);
  });
}
