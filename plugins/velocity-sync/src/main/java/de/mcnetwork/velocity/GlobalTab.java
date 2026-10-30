package de.mcnetwork.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netzwerkweite Tab-Liste: Jeder sieht alle Spieler im Netzwerk mit dem Server, auf dem sie sind.
 * Spieler auf dem eigenen Server trägt der Server selbst ein; wir ergänzen nur die von anderen Servern.
 * Kopf- und Fußzeile (mit Spielerzahlen pro Server) setzt ebenfalls der Proxy.
 */
final class GlobalTab {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private final ProxyServer proxy;
    private final VelocitySync plugin;
    /** Pro Zuschauer: Einträge, die wir (nicht der Server) angelegt haben. */
    private final Map<UUID, Set<UUID>> tracked = new ConcurrentHashMap<>();

    GlobalTab(ProxyServer proxy, VelocitySync plugin) {
        this.proxy = proxy;
        this.plugin = plugin;
    }

    /** Nach einem Serverwechsel leert Velocity die Tab-Liste; wir fangen neu an. */
    void reset(Player viewer) {
        tracked.remove(viewer.getUniqueId());
    }

    private static String serverId(Player player) {
        return player.getCurrentServer().map(ServerConnection::getServerInfo).map(info -> info.getName()).orElse(null);
    }

    synchronized void tick() {
        JsonObject settings = plugin.settings();
        String network = VelocitySync.str(settings, "networkName", "Netzwerk");

        List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        Map<UUID, Boolean> vanished = new ConcurrentHashMap<>();
        for (Player p : players) vanished.put(p.getUniqueId(), plugin.isVanished(p));

        // Spielerzahlen pro Server (ohne Unsichtbare)
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        int visibleTotal = 0;
        for (Player p : players) {
            String id = serverId(p);
            if (id == null || vanished.get(p.getUniqueId())) continue;
            counts.merge(id, 1, Integer::sum);
            visibleTotal++;
        }
        Component serversList = serversList(counts);

        for (Player viewer : players) {
            String viewerServer = serverId(viewer);
            if (viewerServer == null) continue;
            boolean canSeeVanished = viewer.hasPermission("network.vanish.see");
            // Texte in der Sprache des Zuschauers
            boolean german = Lang.german(viewer);
            String entryFormat = Lang.text(settings, "tabEntry", "<player_display> <dark_gray>[<gray><server></gray>]", german);
            String header = Lang.text(settings, "tabHeader", "", german);
            String footer = Lang.text(settings, "tabFooter", "", german);
            TabList tabList = viewer.getTabList();
            Set<UUID> mine = tracked.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            Set<UUID> wanted = new HashSet<>();

            for (Player target : players) {
                if (target == viewer) continue;
                String targetServer = serverId(target);
                if (targetServer == null) continue;
                if (targetServer.equals(viewerServer)) {
                    // Gleicher Server: der Server pflegt den Eintrag selbst
                    mine.remove(target.getUniqueId());
                    continue;
                }
                boolean isVanished = vanished.get(target.getUniqueId());
                if (isVanished && !canSeeVanished) continue;
                wanted.add(target.getUniqueId());

                Component display = entry(entryFormat, target, plugin.serverName(targetServer), isVanished, german);
                int ping = (int) Math.min(Integer.MAX_VALUE, target.getPing());
                Optional<TabListEntry> existing = tabList.getEntry(target.getUniqueId());
                if (existing.isPresent()) {
                    existing.get().setDisplayName(display);
                    existing.get().setLatency(ping);
                } else {
                    tabList.addEntry(TabListEntry.builder()
                            .tabList(tabList)
                            .profile(target.getGameProfile())
                            .displayName(display)
                            .latency(ping)
                            .gameMode(0)
                            .listed(true)
                            .listOrder(plugin.weight(target))
                            .build());
                }
                mine.add(target.getUniqueId());
            }

            for (UUID uuid : Set.copyOf(mine)) {
                if (!wanted.contains(uuid)) {
                    tabList.removeEntry(uuid);
                    mine.remove(uuid);
                }
            }

            TagResolver resolver = TagResolver.resolver(
                    Placeholder.parsed("network", network),
                    Placeholder.unparsed("player", viewer.getUsername()),
                    Placeholder.unparsed("server", plugin.serverName(viewerServer)),
                    Placeholder.unparsed("online", String.valueOf(visibleTotal)),
                    Placeholder.unparsed("server_online", String.valueOf(counts.getOrDefault(viewerServer, 0))),
                    Placeholder.unparsed("ping", String.valueOf(viewer.getPing())),
                    Placeholder.component("servers_list", serversList));
            viewer.sendPlayerListHeaderAndFooter(MM.deserialize(header, resolver), MM.deserialize(footer, resolver));
        }

        tracked.keySet().removeIf(uuid -> proxy.getPlayer(uuid).isEmpty());
    }

    private Component entry(String format, Player target, String serverName, boolean isVanished, boolean german) {
        String prefix = plugin.prefix(target);
        String suffix = plugin.suffix(target);
        Component display = MM.deserialize(format,
                Placeholder.component("player_display", LEGACY.deserialize(prefix + target.getUsername() + suffix)),
                Placeholder.unparsed("player", target.getUsername()),
                Placeholder.unparsed("server", serverName));
        return isVanished ? display.append(MM.deserialize(german ? " <gray><italic>(unsichtbar)" : " <gray><italic>(vanished)")) : display;
    }

    /** "Lobby: 3 · Survival: 5 · ..." – drei Server pro Zeile. */
    private Component serversList(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (String id : plugin.serverOrder()) {
            parts.add("<gray>" + MM.escapeTags(plugin.serverName(id)) + ": <white>" + counts.getOrDefault(id, 0));
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) text.append(i % 3 == 0 ? "<newline>" : " <dark_gray>·</dark_gray> ");
            text.append(parts.get(i));
        }
        return MM.deserialize(text.toString());
    }
}
