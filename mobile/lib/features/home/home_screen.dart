import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:vaultdoc_api/vaultdoc_api.dart' as gen;

import '../../core/api/api_client.dart';
import '../../core/auth/auth_controller.dart';
import 'passkey_list_widget.dart';

final healthProvider = FutureProvider<gen.HealthResponse>((ref) async {
  final api = ref.watch(vaultdocApiProvider).getSystemApi();
  final res = await api.getHealth();
  return res.data!;
});

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final health = ref.watch(healthProvider);
    final auth = ref.watch(authControllerProvider);

    final user = auth.value is Authenticated ? (auth.value as Authenticated).session.user : null;

    return Scaffold(
      appBar: AppBar(
        title: const Text('VaultDoc'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'Settings',
            onPressed: () => context.go('/settings'),
          ),
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Sign out',
            onPressed: () async {
              await ref.read(authControllerProvider.notifier).logout();
              if (!context.mounted) return;
              context.go('/login');
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async => ref.refresh(healthProvider.future),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (user != null)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Signed in as', style: Theme.of(context).textTheme.bodySmall),
                      const SizedBox(height: 4),
                      Text(user.email, style: Theme.of(context).textTheme.titleMedium),
                      if (user.displayName != null) ...[
                        const SizedBox(height: 2),
                        Text(user.displayName!),
                      ],
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: health.when(
                  loading: () => const Center(child: CircularProgressIndicator()),
                  error: (e, _) => Text('Backend unreachable: $e'),
                  data: (h) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Backend health', style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 8),
                        Text('Overall: ${h.status.name}'),
                        const SizedBox(height: 8),
                        ...h.components.asMap.entries.map((e) => Text('${e.key}: ${e.value}')),
                      ],
                    );
                  },
                ),
              ),
            ),
            const SizedBox(height: 12),
            const PasskeyListWidget(),
          ],
        ),
      ),
    );
  }
}
