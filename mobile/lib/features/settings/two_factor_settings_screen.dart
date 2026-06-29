import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;
import 'package:dio/dio.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

enum _Phase { idle, setup, showCodes, regenerating, confirming }

class TwoFactorSettingsScreen extends ConsumerStatefulWidget {
  const TwoFactorSettingsScreen({super.key});

  @override
  ConsumerState<TwoFactorSettingsScreen> createState() =>
      _TwoFactorSettingsScreenState();
}

class _TwoFactorSettingsScreenState extends ConsumerState<TwoFactorSettingsScreen> {
  _Phase _phase = _Phase.idle;
  bool _enabled = false;
  bool _busy = false;
  String? _error;
  String _otpauthUri = '';
  List<String> _backupCodes = [];
  final _codeCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadStatus();
  }

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<gen.TwoFactorApi> get _api async =>
      ref.read(vaultdocApiProvider).getTwoFactorApi();

  Future<void> _loadStatus() async {
    try {
      final api = await _api;
      await api.getBackupCodesStatus();
      setState(() => _enabled = true);
    } catch (_) {
      setState(() => _enabled = false);
    }
  }

  Future<void> _beginSetup() async {
    setState(() { _busy = true; _error = null; });
    try {
      final api = await _api;
      final res = await api.begin2faSetup();
      setState(() {
        _otpauthUri = res.data!.otpauthUri;
        _phase = _Phase.setup;
      });
    } on DioException catch (e) {
      setState(() => _error = ApiException.fromDio(e).message);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _finishSetup() async {
    setState(() { _busy = true; _error = null; });
    try {
      final api = await _api;
      final res = await api.finish2faSetup(
        twoFactorCodeRequest:
            gen.TwoFactorCodeRequest((b) => b..code = _codeCtrl.text.trim()),
      );
      setState(() {
        _enabled = true;
        _backupCodes = res.data!.codes.toList();
        _codeCtrl.clear();
        _phase = _Phase.showCodes;
      });
    } on DioException catch (e) {
      setState(() => _error = ApiException.fromDio(e).message);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _disable() async {
    setState(() { _busy = true; _error = null; });
    try {
      final api = await _api;
      await api.disable2fa(
        twoFactorCodeRequest:
            gen.TwoFactorCodeRequest((b) => b..code = _codeCtrl.text.trim()),
      );
      setState(() {
        _enabled = false;
        _codeCtrl.clear();
        _phase = _Phase.idle;
      });
    } on DioException catch (e) {
      setState(() => _error = ApiException.fromDio(e).message);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _regenerate() async {
    setState(() { _busy = true; _error = null; });
    try {
      final api = await _api;
      final res = await api.regenerateBackupCodes(
        twoFactorCodeRequest:
            gen.TwoFactorCodeRequest((b) => b..code = _codeCtrl.text.trim()),
      );
      setState(() {
        _backupCodes = res.data!.codes.toList();
        _codeCtrl.clear();
        _phase = _Phase.showCodes;
      });
    } on DioException catch (e) {
      setState(() => _error = ApiException.fromDio(e).message);
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Two-factor authentication')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(_error!, style: TextStyle(color: colors.error)),
                ),
              if (_phase == _Phase.idle) _buildIdle(colors),
              if (_phase == _Phase.setup) _buildSetup(colors),
              if (_phase == _Phase.showCodes) _buildShowCodes(),
              if (_phase == _Phase.regenerating || _phase == _Phase.confirming)
                _buildCodeEntry(colors),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildIdle(ColorScheme colors) {
    if (!_enabled) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('2FA is not enabled.', style: Theme.of(context).textTheme.bodyLarge),
          const SizedBox(height: 16),
          FilledButton(onPressed: _busy ? null : _beginSetup, child: const Text('Set up 2FA')),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('2FA is enabled.', style: Theme.of(context).textTheme.bodyLarge),
        const SizedBox(height: 16),
        OutlinedButton(
          onPressed: () => setState(() { _phase = _Phase.regenerating; _error = null; }),
          child: const Text('Regenerate backup codes'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => setState(() { _phase = _Phase.confirming; _error = null; }),
          child: Text('Disable 2FA', style: TextStyle(color: colors.error)),
        ),
      ],
    );
  }

  Widget _buildSetup(ColorScheme colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Scan this QR code with your authenticator app.',
            style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 16),
        if (_otpauthUri.isNotEmpty)
          Center(
            child: QrImageView(
              data: _otpauthUri,
              version: QrVersions.auto,
              size: 220,
              backgroundColor: Colors.white,
            ),
          ),
        const SizedBox(height: 16),
        TextField(
          controller: _codeCtrl,
          keyboardType: TextInputType.number,
          maxLength: 6,
          decoration: const InputDecoration(labelText: '6-digit verification code', counterText: ''),
        ),
        const SizedBox(height: 16),
        if (_error != null) Text(_error!, style: TextStyle(color: colors.error)),
        Row(children: [
          Expanded(child: OutlinedButton(
            onPressed: () => setState(() { _phase = _Phase.idle; _codeCtrl.clear(); _error = null; }),
            child: const Text('Cancel'),
          )),
          const SizedBox(width: 8),
          Expanded(child: FilledButton(
            onPressed: _busy ? null : _finishSetup,
            child: Text(_busy ? 'Activating…' : 'Activate 2FA'),
          )),
        ]),
      ],
    );
  }

  Widget _buildShowCodes() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Save your backup codes — they\'re shown once.',
            style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        ...List.generate(_backupCodes.length, (i) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Text('${i+1}. ${_backupCodes[i]}', style: const TextStyle(fontFamily: 'monospace', fontSize: 16)),
        )),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: () => setState(() { _phase = _Phase.idle; _backupCodes = []; }),
          child: const Text("I've saved them"),
        ),
      ],
    );
  }

  Widget _buildCodeEntry(ColorScheme colors) {
    final isDisabling = _phase == _Phase.confirming;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(isDisabling ? 'Enter your TOTP code to disable 2FA' : 'Enter your TOTP code to regenerate'),
        const SizedBox(height: 12),
        TextField(
          controller: _codeCtrl,
          keyboardType: TextInputType.number,
          maxLength: 6,
          decoration: const InputDecoration(labelText: '6-digit code', counterText: ''),
        ),
        const SizedBox(height: 16),
        if (_error != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(_error!, style: TextStyle(color: colors.error))),
        Row(children: [
          Expanded(child: OutlinedButton(
            onPressed: () => setState(() { _phase = _Phase.idle; _codeCtrl.clear(); _error = null; }),
            child: const Text('Cancel'),
          )),
          const SizedBox(width: 8),
          Expanded(child: FilledButton(
            onPressed: _busy ? null : (isDisabling ? _disable : _regenerate),
            style: isDisabling ? FilledButton.styleFrom(backgroundColor: colors.error) : null,
            child: Text(_busy ? 'Processing…' : (isDisabling ? 'Disable 2FA' : 'Generate new codes')),
          )),
        ]),
      ],
    );
  }
}
