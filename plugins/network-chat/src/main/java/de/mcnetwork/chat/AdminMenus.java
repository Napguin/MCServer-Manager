package de.mcnetwork.chat;

import de.mcnetwork.chat.NetworkClient.NetworkPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * Admin-GUI (/admin): alle Spieler im Netzwerk, pro Spieler Aktionen wie
 * Hinspringen, Gamemode, Inventar, Rang, Kick, Mute und Bann.
 * Texte auf Deutsch oder Englisch, je nach Sprache des Admins.
 */
final class AdminMenus {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;

    private record Duration(String de, String en, long seconds, Material icon) {
    }

    private static final List<Duration> DURATIONS = List.of(
            new Duration("1 Stunde", "1 hour", 3600, Material.LIME_DYE),
            new Duration("1 Tag", "1 day", 86400, Material.YELLOW_DYE),
            new Duration("7 Tage", "7 days", 7 * 86400L, Material.ORANGE_DYE),
            new Duration("30 Tage", "30 days", 30 * 86400L, Material.RED_DYE),
            new Duration("Dauerhaft", "Permanent", 0, Material.BLACK_DYE));

    private final NetworkChat plugin;

    AdminMenus(NetworkChat plugin) {
        this.plugin = plugin;
    }

    private boolean sameServer(NetworkPlayer target) {
        return target.server().equals(plugin.client().serverId());
    }

    private static String t(boolean de, String german, String english) {
        return de ? german : english;
    }

    // ---------- Spielerliste ----------

    void openPlayers(Player viewer, int page) {
        boolean de = Lang.german(viewer.locale());
        boolean seeVanished = viewer.hasPermission("network.vanish.see");
        List<NetworkPlayer> players = plugin.client().players().stream()
                .filter(p -> !p.vanished() || seeVanished)
                .toList();
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));

        Menu menu = new Menu(6, t(de, "<dark_gray>Admin · Spieler (", "<dark_gray>Admin · Players (") + players.size() + ")");
        List<NetworkPlayer> slice = players.subList(current * PAGE_SIZE, Math.min(players.size(), (current + 1) * PAGE_SIZE));
        for (int i = 0; i < slice.size(); i++) {
            NetworkPlayer target = slice.get(i);
            menu.set(i, head(target, de), p -> openPlayer(p, target));
        }
        if (players.isEmpty()) {
            menu.set(22, Menu.item(Material.BARRIER, t(de, "<red>Keine Spieler gefunden", "<red>No players found"),
                    t(de, "<gray>Die Liste kommt vom Dashboard –", "<gray>The list comes from the dashboard –"),
                    t(de, "<gray>läuft der Proxy?", "<gray>is the proxy running?")), null);
        }
        if (current > 0) menu.set(45, Menu.item(Material.ARROW, t(de, "<yellow>← Zurück", "<yellow>← Back")), p -> openPlayers(p, current - 1));
        menu.set(49, Menu.item(Material.CLOCK, t(de, "<aqua>Aktualisieren", "<aqua>Refresh"),
                t(de, "<gray>Seite ", "<gray>Page ") + (current + 1) + t(de, " von ", " of ") + pages), p -> openPlayers(p, current));
        if (current < pages - 1) menu.set(53, Menu.item(Material.ARROW, t(de, "<yellow>Weiter →", "<yellow>Next →")), p -> openPlayers(p, current + 1));
        if (viewer.hasPermission("network.servers")) {
            menu.set(47, Menu.item(Material.COMMAND_BLOCK, t(de, "<gold><bold>Server verwalten", "<gold><bold>Manage servers"),
                    t(de, "<gray>Starten, stoppen, Wartungsmodus …", "<gray>Start, stop, maintenance …")),
                    p -> plugin.serverMenus().openServers(p));
        }
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    private ItemStack head(NetworkPlayer target, boolean de) {
        ItemStack item = Menu.item(Material.PLAYER_HEAD,
                "<white><bold>" + MM.escapeTags(target.name()),
                "<gray>Server: <white>" + MM.escapeTags(plugin.client().serverName(target.server())),
                "<gray>Ping: <white>" + target.ping() + " ms",
                target.vanished() ? t(de, "<light_purple>Unsichtbar", "<light_purple>Vanished") : "",
                plugin.client().mute(target.uuid()) != null ? t(de, "<red>Gemutet", "<red>Muted") : "",
                "",
                t(de, "<yellow>Klicken zum Verwalten", "<yellow>Click to manage"));
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        Player online = Bukkit.getPlayer(target.uuid());
        meta.setPlayerProfile(online != null ? online.getPlayerProfile() : Bukkit.createProfile(target.uuid(), target.name()));
        item.setItemMeta(meta);
        return item;
    }

    // ---------- Einzelner Spieler ----------

    void openPlayer(Player viewer, NetworkPlayer target) {
        boolean de = Lang.german(viewer.locale());
        String name = MM.escapeTags(target.name());
        String serverName = MM.escapeTags(plugin.client().serverName(target.server()));
        Menu menu = new Menu(4, "<dark_gray>Admin · " + name);
        Player local = sameServer(target) ? Bukkit.getPlayer(target.uuid()) : null;
        menu.set(4, head(target, de), null);

        menu.set(19, Menu.item(Material.ENDER_PEARL, t(de, "<aqua>Hinspringen", "<aqua>Go to player"),
                local != null ? t(de, "<gray>Zu " + name + " teleportieren", "<gray>Teleport to " + name)
                        : t(de, "<gray>Auf den Server <white>" + serverName + " <gray>wechseln", "<gray>Switch to server <white>" + serverName)),
                p -> {
                    p.closeInventory();
                    if (local != null) p.teleportAsync(local.getLocation());
                    else plugin.connect(p, target.server());
                });

        if (local != null) {
            menu.set(20, Menu.item(Material.GRASS_BLOCK, t(de, "<green>Spielmodus", "<green>Game mode"),
                    t(de, "<gray>Aktuell: <white>", "<gray>Current: <white>") + local.getGameMode().name().toLowerCase(),
                    t(de, "<yellow>Klicken zum Wechseln", "<yellow>Click to change")),
                    p -> {
                        GameMode next = switch (local.getGameMode()) {
                            case SURVIVAL -> GameMode.CREATIVE;
                            case CREATIVE -> GameMode.SPECTATOR;
                            case SPECTATOR -> GameMode.ADVENTURE;
                            case ADVENTURE -> GameMode.SURVIVAL;
                        };
                        local.getScheduler().run(plugin, task -> local.setGameMode(next), null);
                        p.sendMessage(MM.deserialize(t(de, "<gray>Spielmodus von <white>", "<gray>Game mode of <white>") + name
                                + "<gray> → <white>" + next.name().toLowerCase()));
                        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task ->
                                p.getScheduler().run(plugin, t2 -> openPlayer(p, target), null), 2L);
                    });
            menu.set(21, Menu.item(Material.CHEST, t(de, "<gold>Inventar ansehen", "<gold>View inventory"),
                    t(de, "<gray>Inventar öffnen und bearbeiten", "<gray>Open and edit the inventory")),
                    p -> p.openInventory(local.getInventory()));
        } else {
            menu.set(20, Menu.item(Material.GRAY_DYE, t(de, "<dark_gray>Spielmodus / Inventar", "<dark_gray>Game mode / inventory"),
                    t(de, "<gray>Nur auf demselben Server möglich", "<gray>Only possible on the same server")), null);
        }

        if (viewer.hasPermission("network.rank")) {
            menu.set(22, Menu.item(Material.NAME_TAG, t(de, "<light_purple>Rang setzen", "<light_purple>Set rank"),
                    t(de, "<gray>Rolle aus dem Dashboard wählen", "<gray>Pick a role from the dashboard")),
                    p -> openRanks(p, target));
        }
        if (viewer.hasPermission("network.kick")) {
            menu.set(23, Menu.item(Material.IRON_DOOR, t(de, "<yellow>Kicken", "<yellow>Kick"),
                    t(de, "<gray>Vom Netzwerk werfen", "<gray>Remove from the network")),
                    p -> {
                        p.closeInventory();
                        plugin.moderate(p, "kick", target.name(), 0, t(de, "Vom Team gekickt", "Kicked by staff"));
                    });
        }
        if (viewer.hasPermission("network.mute")) {
            if (plugin.client().mute(target.uuid()) != null) {
                menu.set(24, Menu.item(Material.WRITABLE_BOOK, t(de, "<green>Entmuten", "<green>Unmute")),
                        p -> {
                            p.closeInventory();
                            plugin.moderate(p, "unmute", target.name(), 0, "");
                        });
            } else {
                menu.set(24, Menu.item(Material.BOOK, t(de, "<gold>Muten", "<gold>Mute"),
                        t(de, "<gray>Darf nicht mehr chatten", "<gray>Can no longer chat")),
                        p -> openDuration(p, target, "mute"));
            }
        }
        if (viewer.hasPermission("network.ban")) {
            menu.set(25, Menu.item(Material.BARRIER, t(de, "<red>Bannen", "<red>Ban"),
                    t(de, "<gray>Vom ganzen Netzwerk aussperren", "<gray>Lock out of the whole network")),
                    p -> openDuration(p, target, "ban"));
        }

        menu.set(31, Menu.item(Material.ARROW, t(de, "<yellow>← Zurück zur Liste", "<yellow>← Back to the list")), p -> openPlayers(p, 0));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    // ---------- Dauer für Mute/Bann ----------

    private void openDuration(Player viewer, NetworkPlayer target, String action) {
        boolean de = Lang.german(viewer.locale());
        boolean ban = action.equals("ban");
        String name = MM.escapeTags(target.name());
        Menu menu = new Menu(3, de
                ? "<dark_gray>" + name + (ban ? " bannen" : " muten") + " – wie lange?"
                : "<dark_gray>" + (ban ? "Ban " : "Mute ") + name + " – how long?");
        for (int i = 0; i < DURATIONS.size(); i++) {
            Duration d = DURATIONS.get(i);
            menu.set(11 + i, Menu.item(d.icon(), "<white>" + (de ? d.de() : d.en())), p -> {
                p.closeInventory();
                plugin.moderate(p, action, target.name(), d.seconds(), ban
                        ? t(de, "Vom Team gebannt", "Banned by staff")
                        : t(de, "Vom Team gemutet", "Muted by staff"));
            });
        }
        menu.set(22, Menu.item(Material.ARROW, t(de, "<yellow>← Zurück", "<yellow>← Back")), p -> openPlayer(p, target));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    // ---------- Rang ----------

    private void openRanks(Player viewer, NetworkPlayer target) {
        boolean de = Lang.german(viewer.locale());
        if (!plugin.hasLuckPerms()) {
            viewer.sendMessage(MM.deserialize(t(de, "<red>LuckPerms ist nicht installiert.", "<red>LuckPerms is not installed.")));
            return;
        }
        String name = MM.escapeTags(target.name());
        List<LuckPermsHook.Rank> ranks = LuckPermsHook.ranks();
        Menu menu = new Menu(Math.min(6, Math.max(2, (ranks.size() + 9) / 9 + 1)), t(de, "<dark_gray>Rang für ", "<dark_gray>Rank for ") + name);
        for (int i = 0; i < ranks.size() && i < 45; i++) {
            LuckPermsHook.Rank rank = ranks.get(i);
            String rankName = MM.escapeTags(rank.displayName());
            menu.set(i, Menu.item(Material.NAME_TAG, "<white>" + rankName,
                    t(de, "<gray>Gewicht: <white>", "<gray>Weight: <white>") + rank.weight(), "",
                    t(de, "<yellow>Klicken: als einzige Rolle setzen", "<yellow>Click: set as the only role")),
                    p -> {
                        p.closeInventory();
                        LuckPermsHook.setRank(target.uuid(), rank.name()).thenRun(() -> p.sendMessage(MM.deserialize(de
                                ? "<green>" + name + " hat jetzt den Rang <white>" + rankName
                                : "<green>" + name + " now has the rank <white>" + rankName)));
                    });
        }
        int back = menu.getInventory().getSize() - 5;
        menu.set(back, Menu.item(Material.ARROW, t(de, "<yellow>← Zurück", "<yellow>← Back")), p -> openPlayer(p, target));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }
}
