import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/passkey/passkey_controller.dart';

class PasskeyListWidget extends ConsumerStatefulWidget {
  const PasskeyListWidget({super.key});

  @override
  ConsumerState<PasskeyListWidget> createState() => _PasskeyListWidgetState();
}

class _PasskeyListWidgetState extends ConsumerState<PasskeyListWidget> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      final controller = ref.read(passkeyControllerProvider.notifier);
      await controller.detectSupport();
      if (!mounted) return;
      if (ref.read(passkeyControllerProvider).supported) {
        await controller.refresh();
      }
    });
  }

  Future<String?> _promptLabel() async {
    final controller = TextEditingController(text: 'This device');
    final label = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Name this passkey'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'e.g. Pixel 9'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    return label?.isEmpty == true ? null : label;
  }

  Future<void> _add() async {
    final label = await _promptLabel();
    if (label == null) return;
    await ref.read(passkeyControllerProvider.notifier).addPasskey(label: label);
  }

  Future<void> _delete(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove passkey?'),
        content: const Text('You can register a new passkey at any time.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton.tonal(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await ref.read(passkeyControllerProvider.notifier).delete(id);
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(passkeyControllerProvider);
    final colors = Theme.of(context).colorScheme;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Passkeys', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 2),
                      Text(
                        'Sign in without a password from any of your devices.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                if (state.supported)
                  FilledButton.icon(
                    icon: const Icon(Icons.add, size: 18),
                    onPressed: state.busy ? null : _add,
                    label: const Text('Add'),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            if (!state.supported)
              Text(
                "This Android version doesn't support passkeys (requires API 28+).",
                style: TextStyle(color: colors.onSurfaceVariant),
              )
            else if (state.loading)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (state.passkeys.isEmpty)
              Text(
                'No passkeys yet. Tap Add to register this device.',
                style: TextStyle(color: colors.onSurfaceVariant),
              )
            else
              ...state.passkeys.map(_passkeyTile),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(state.error!, style: TextStyle(color: colors.error, fontSize: 12)),
              ),
          ],
        ),
      ),
    );
  }

  Widget _passkeyTile(dynamic pk) {
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.key_outlined),
      title: Text(pk.label as String),
      subtitle: Text('Added ${(pk.createdAt as DateTime).toLocal().toString().substring(0, 10)}'),
      trailing: IconButton(
        icon: const Icon(Icons.delete_outline),
        tooltip: 'Remove',
        onPressed: () => _delete(pk.id as String),
      ),
    );
  }
}
