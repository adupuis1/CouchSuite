import 'dart:ui';

import 'package:flutter/material.dart';

void main() {
  // Bootstraps the prototype app. Flutter's hot reload will refresh this tree instantly.
  runApp(const CouchLauncherApp());
}

/// Root widget that wires basic theming and the navigation scaffold.
class CouchLauncherApp extends StatelessWidget {
  const CouchLauncherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Couch Launcher (Flutter Prototype)',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1A1A1A)),
        scaffoldBackgroundColor: const Color(0xFFF9F9FC),
        useMaterial3: true,
      ),
      home: const LauncherHome(),
    );
  }
}

/// UI state machine for the minimal build.
/// Connect → User Select → Hub (with Home / Gaming / TV tabs).
class LauncherHome extends StatefulWidget {
  const LauncherHome({super.key});

  @override
  State<LauncherHome> createState() => _LauncherHomeState();
}

enum LauncherScreen { connect, userSelect, hub }

enum HubTab { home, gaming, tv }

class _LauncherHomeState extends State<LauncherHome>
    with SingleTickerProviderStateMixin {
  LauncherScreen _screen = LauncherScreen.connect;
  HubTab _hubTab = HubTab.home;

  // Synthetic demo data so you can see tiles; replace with API payload later on.
  final List<String> _demoTiles = const [
    'SteamWorld Build',
    'Cyberpunk 2077',
    'Baldur\'s Gate 3',
    'Dave the Diver',
    'Elden Ring',
    'Forza Horizon 5',
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: AnimatedSwitcher(
        duration: const Duration(milliseconds: 300),
        switchInCurve: Curves.easeOut,
        switchOutCurve: Curves.easeIn,
        child: _buildBody(),
      ),
    );
  }

  /// Decides which screen to show.
  Widget _buildBody() {
    switch (_screen) {
      case LauncherScreen.connect:
        return _ConnectScreen(
          onContinue: () => setState(() {
            _screen = LauncherScreen.userSelect;
          }),
        );
      case LauncherScreen.userSelect:
        return _UserSelectScreen(
          onBack: () => setState(() {
            _screen = LauncherScreen.connect;
          }),
          onContinue: () => setState(() {
            _screen = LauncherScreen.hub;
          }),
        );
      case LauncherScreen.hub:
        return _HubScreen(
          activeTab: _hubTab,
          onTabSelected: (tab) => setState(() {
            _hubTab = tab;
          }),
          onOpenSettings: _showPlaceholderSnack,
          onOpenAccount: _showPlaceholderSnack,
          onCheckController: _showPlaceholderSnack,
          onRefresh: _showPlaceholderSnack,
          tiles: _demoTiles,
        );
    }
  }

  /// Temporary action handler that lets you verify button wiring quickly.
  void _showPlaceholderSnack(String label) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('TODO: $label'),
        duration: const Duration(seconds: 1),
      ),
    );
  }
}

/// Connect controller step.
class _ConnectScreen extends StatelessWidget {
  const _ConnectScreen({
    required this.onContinue,
  });

  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFFFDFDFF), Color(0xFFE4E9FB)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Card(
            elevation: 8,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(32),
            ),
            color: Colors.white.withOpacity(0.18),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(32),
              child: Stack(
                children: [
                  // BackdropFilter + translucent container creates a subtle "liquid glass" surface.
                  BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                    child: const SizedBox.expand(),
                  ),
                  DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          Colors.white.withOpacity(0.32),
                          Colors.white.withOpacity(0.12),
                        ],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(32),
                      border: Border.all(
                        color: Colors.white.withOpacity(0.45),
                        width: 1.0,
                      ),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(32),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Text(
                            'Connect your controller',
                            style: theme.textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.w800,
                            ),
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: 24),
                          // Reuse the existing controller art from CouchLauncherFX/resources.
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.center,
                            children: [
                              Image.asset(
                                'assets/icons/icons8-game-controller-100.png',
                                height: 120,
                                filterQuality: FilterQuality.high,
                              ),
                              Image.asset(
                                'assets/icons/icons8-joy-con-100.png',
                                height: 120,
                                filterQuality: FilterQuality.high,
                              ),
                            ],
                          ),
                          const SizedBox(height: 24),
                          Text(
                            'Power on and pair your Bluetooth gamepad to continue.',
                            style: theme.textTheme.bodyLarge,
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: 32),
                          FilledButton.icon(
                            onPressed: onContinue,
                            icon: const Icon(Icons.arrow_forward),
                            label: const Text('Continue'),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// User selection screen with placeholder avatars.
class _UserSelectScreen extends StatelessWidget {
  const _UserSelectScreen({
    required this.onBack,
    required this.onContinue,
  });

  final VoidCallback onBack;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        children: [
          Row(
            children: [
              IconButton(
                onPressed: onBack,
                icon: const Icon(Icons.arrow_back_ios_new),
              ),
              const SizedBox(width: 8),
              Text(
                'Select a user',
                style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 32),
          Expanded(
            child: GridView.count(
              crossAxisCount: 3,
              mainAxisSpacing: 24,
              crossAxisSpacing: 24,
              children: [
                _UserTile(
                  label: 'Player One',
                  asset: 'assets/icons/icons8-user-100.png',
                  onTap: onContinue,
                ),
                _UserTile(
                  label: 'Player Two',
                  asset: 'assets/icons/icons8-user-100.png',
                  onTap: onContinue,
                ),
                _UserTile(
                  label: 'Add user',
                  asset: 'assets/icons/icons8-add-user-male-100.png',
                  onTap: () => _showCreationSheet(context),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _showCreationSheet(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (context) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Create account',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            const Text('Hook this sheet to the real signup flow later.'),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Close'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _UserTile extends StatelessWidget {
  const _UserTile({
    required this.label,
    required this.asset,
    required this.onTap,
  });

  final String label;
  final String asset;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(32),
      onTap: onTap,
      child: Ink(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(32),
          boxShadow: const [
            BoxShadow(
              color: Color(0x15000000),
              blurRadius: 16,
              offset: Offset(0, 8),
            ),
          ],
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Image.asset(
              asset,
              height: 86,
              filterQuality: FilterQuality.medium,
            ),
            const SizedBox(height: 16),
            Text(
              label,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ],
        ),
      ),
    );
  }
}

/// Hub landing page with Home/Gaming/TV tabs.
class _HubScreen extends StatelessWidget {
  const _HubScreen({
    required this.activeTab,
    required this.onTabSelected,
    required this.onOpenSettings,
    required this.onOpenAccount,
    required this.onCheckController,
    required this.onRefresh,
    required this.tiles,
  });

  final HubTab activeTab;
  final ValueChanged<HubTab> onTabSelected;
  final ValueChanged<String> onOpenSettings;
  final ValueChanged<String> onOpenAccount;
  final ValueChanged<String> onCheckController;
  final ValueChanged<String> onRefresh;
  final List<String> tiles;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _HubHeader(
              onOpenSettings: () => onOpenSettings('settings'),
              onOpenAccount: () => onOpenAccount('account'),
              onCheckController: () => onCheckController('controller'),
              onRefresh: () => onRefresh('refresh'),
            ),
            const SizedBox(height: 32),
            _HubTabs(
              activeTab: activeTab,
              onTabSelected: onTabSelected,
            ),
            const SizedBox(height: 24),
            Expanded(
              child: _HubContent(
                tab: activeTab,
                tiles: tiles,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HubHeader extends StatelessWidget {
  const _HubHeader({
    required this.onOpenSettings,
    required this.onOpenAccount,
    required this.onCheckController,
    required this.onRefresh,
  });

  final VoidCallback onOpenSettings;
  final VoidCallback onOpenAccount;
  final VoidCallback onCheckController;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _IconAction(
          assetPath: 'assets/icons/icons8-game-controller-100.png',
          tooltip: 'Controller status',
          onPressed: onCheckController,
        ),
        const SizedBox(width: 12),
        _IconAction(
          assetPath: 'assets/icons/icons8-user-100.png',
          tooltip: 'Account',
          onPressed: onOpenAccount,
        ),
        const SizedBox(width: 12),
        _IconAction(
          assetPath: 'assets/icons/icons8-wi-fi-100.png',
          tooltip: 'Network',
          onPressed: onRefresh,
        ),
        const Spacer(),
        FilledButton.tonal(
          onPressed: onOpenSettings,
          child: const Text('Settings'),
        ),
      ],
    );
  }
}

class _IconAction extends StatelessWidget {
  const _IconAction({
    required this.assetPath,
    required this.tooltip,
    required this.onPressed,
  });

  final String assetPath;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkResponse(
        onTap: onPressed,
        radius: 32,
        child: CircleAvatar(
          radius: 24,
          backgroundColor: Colors.white,
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: Image.asset(assetPath, height: 32),
          ),
        ),
      ),
    );
  }
}

class _HubTabs extends StatelessWidget {
  const _HubTabs({
    required this.activeTab,
    required this.onTabSelected,
  });

  final HubTab activeTab;
  final ValueChanged<HubTab> onTabSelected;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 16,
      children: HubTab.values.map((tab) {
        final isActive = tab == activeTab;
        return ChoiceChip(
          label: Text(tab.name),
          selected: isActive,
          onSelected: (_) => onTabSelected(tab),
        );
      }).toList(),
    );
  }
}

class _HubContent extends StatelessWidget {
  const _HubContent({
    required this.tab,
    required this.tiles,
  });

  final HubTab tab;
  final List<String> tiles;

  @override
  Widget build(BuildContext context) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 200),
      child: switch (tab) {
        HubTab.home => _buildHome(context),
        HubTab.gaming => _buildGaming(context),
        HubTab.tv => _buildTv(context),
      },
    );
  }

  Widget _buildHome(BuildContext context) {
    return _TileGrid(
      key: const ValueKey('home'),
      title: 'Top charts',
      subtitle: 'Replace with /charts/top10 feed',
      tiles: tiles,
    );
  }

  Widget _buildGaming(BuildContext context) {
    return _TileGrid(
      key: const ValueKey('gaming'),
      title: 'Gaming',
      subtitle: 'Reserved for owned + installed titles.',
      tiles: tiles.take(4).toList(),
    );
  }

  Widget _buildTv(BuildContext context) {
    return Center(
      key: const ValueKey('tv'),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Image.asset(
            'assets/icons/icons8-plus-math-100.png',
            height: 96,
          ),
          const SizedBox(height: 16),
          Text(
            'Link TV apps',
            style: Theme.of(context)
                .textTheme
                .headlineSmall
                ?.copyWith(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text('Placeholder for streaming integrations.'),
        ],
      ),
    );
  }
}

class _TileGrid extends StatelessWidget {
  const _TileGrid({
    super.key,
    required this.title,
    required this.subtitle,
    required this.tiles,
  });

  final String title;
  final String subtitle;
  final List<String> tiles;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 4),
        Text(subtitle, style: theme.textTheme.bodyMedium),
        const SizedBox(height: 24),
        Expanded(
          child: GridView.builder(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              mainAxisSpacing: 18,
              crossAxisSpacing: 18,
              childAspectRatio: 16 / 9,
            ),
            itemCount: tiles.length,
            itemBuilder: (context, index) {
              final title = tiles[index];
              return _GameTile(title: title);
            },
          ),
        ),
      ],
    );
  }
}

class _GameTile extends StatelessWidget {
  const _GameTile({
    required this.title,
  });

  final String title;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Launch $title (stubbed)')),
      ),
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          gradient: const LinearGradient(
            colors: [Color(0xFF202642), Color(0xFF354A6E)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: Theme.of(context)
                  .textTheme
                  .titleMedium
                  ?.copyWith(color: Colors.white, fontWeight: FontWeight.bold),
            ),
            const Spacer(),
            const Text(
              'Press to play',
              style: TextStyle(color: Colors.white70),
            ),
          ],
        ),
      ),
    );
  }
}
