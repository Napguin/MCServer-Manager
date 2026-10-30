package de.mcnetwork.chat;

import de.mcnetwork.chat.NetworkClient.NetworkPlayer;
import de.mcnetwork.chat.NetworkClient.NetworkServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Server-Verwaltung im Admin-GUI (/admin server): Server starten, stoppen, neu starten,
 * hinspringen, schützen, ausblenden und den Wartungsmodus umschalten.
 * Braucht das Recht network.servers.
 */
final class ServerMenus {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NetworkChat plugin;

    ServerMenus(NetworkChat plugin) {
        this.plugin = plugin;
    }

    private static String t(boolean de, String german, String english) {
        return de ? german : english;
    }

    private static Material material(String icon) {
        Material m = Material.matchMaterial(icon == null ? "" : icon);
        return m == null || !m.isItem() || m.isAir() ? Material.GRASS_BLOCK : m;
    }

    private static String status(NetworkServer s, boolean de) {
        return switch (s.status()) {
            case "online" -> "<green>● Online";
            case "starting" -> t(de, "<gold>● Startet …", "<gold>● Starting …");
            case "setup" -> t(de, "<gold>● Wird eingerichtet …", "<gold>● Setting up …");
            case "stopped" -> t(de, "<red>● Gestoppt", "<red>● Stopped");
            case "missing" -> t(de, "<gold>● Wird erstellt …", "<gold>● Being created …");
            default -> t(de, "<gray>● Unbekannt", "<gray>● Unknown");
        };
    }

    private static boolean running(NetworkServer s) {
        return s.status().equals("online") || s.status().equals("starting") || s.status().equals("setup");
    }

    private ItemStack icon(NetworkServer s, boolean de, String... extra) {
        String[] lore = new String[6 + extra.length];
        lore[0] = status(s, de);
        lore[1] = t(de, "<gray>Spieler: <white>", "<gray>Players: <white>") + s.players();
        lore[2] = "<gray>" + MM.escapeTags(s.type() + " " + s.version()) + " · " + MM.escapeTags(s.memory()) + " RAM";
        lore[3] = s.restricted() ? t(de, "<red>🔒 Geschützt", "<red>🔒 Protected") : "";
        lore[4] = s.hidden() && !s.id().equals("lobby") ? t(de, "<dark_gray>Im Kompass ausgeblendet", "<dark_gray>Hidden in the compass") : "";
        lore[5] = "";
        System.arraycopy(extra, 0, lore, 6, extra.length);
        return Menu.item(material(s.icon()), "<white><bold>" + MM.escapeTags(s.name()), lore);
    }

    // ---------- Übersicht ----------

    void openServers(Player viewer) {
        boolean de = Lang.german(viewer.locale());
        List<NetworkServer> servers = plugin.client().servers();
        Menu menu = new Menu(Math.min(6, Math.max(3, (servers.size() + 8) / 9 + 2)), t(de, "<dark_gray>Admin · Server", "<dark_gray>Admin · Servers"));
        for (int i = 0; i < servers.size() && i < 36; i++) {
            NetworkServer s = servers.get(i);
            menu.set(i, icon(s, de, t(de, "<yellow>Klicken zum Verwalten", "<yellow>Click to manage")), p -> openServer(p, s.id()));
        }
        int size = menu.getInventory().getSize();
        boolean maintenance = plugin.client().maintenance();
        menu.set(size - 6, Menu.item(maintenance ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                maintenance ? t(de, "<red><bold>Wartungsmodus: AN", "<red><bold>Maintenance: ON")
                        : t(de, "<green><bold>Wartungsmodus: AUS", "<green><bold>Maintenance: OFF"),
                t(de, "<gray>Im Wartungsmodus kommt nur das Team ins Netzwerk", "<gray>During maintenance only staff can join"),
                "", t(de, "<yellow>Klicken zum Umschalten", "<yellow>Click to toggle")),
                p -> confirm(p, maintenance
                                ? t(de, "Wartungsmodus ausschalten?", "Turn maintenance off?")
                                : t(de, "Wartungsmodus einschalten?", "Turn maintenance on?"),
                        () -> run(p, plugin.client().setMaintenance(!maintenance, p.getName(), de),
                                maintenance ? t(de, "<green>Wartungsmodus ist aus.", "<green>Maintenance is off.")
                                        : t(de, "<red>Wartungsmodus ist an.", "<red>Maintenance is on."), this::openServers),
                        this::openServers));
        menu.set(size - 4, Menu.item(Material.CLOCK, t(de, "<aqua>Aktualisieren", "<aqua>Refresh")), this::openServers);
        menu.set(size - 5, Menu.item(Material.ARROW, t(de, "<yellow>← Spielerliste", "<yellow>← Player list")),
                p -> plugin.menus().openPlayers(p, 0));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    // ---------- Einzelner Server ----------

    void openServer(Player viewer, String id) {
        boolean de = Lang.german(viewer.locale());
        Optional<NetworkServer> found = plugin.client().servers().stream().filter(s -> s.id().equals(id)).findFirst();
        if (found.isEmpty()) {
            openServers(viewer);
            return;
        }
        NetworkServer s = found.get();
        String name = MM.escapeTags(s.name());
        boolean lobby = s.id().equals("lobby");
        Menu menu = new Menu(5, "<dark_gray>Admin · " + name);
        menu.set(4, icon(s, de), null);

        if (running(s)) {
            menu.set(19, Menu.item(Material.ORANGE_CONCRETE, t(de, "<gold>Neustarten", "<gold>Restart"),
                    t(de, "<gray>Spieler landen kurz in der Lobby", "<gray>Players are sent to the lobby meanwhile")),
                    p -> confirm(p, t(de, name + " neu starten?", "Restart " + name + "?"),
                            () -> run(p, plugin.client().serverAction(s.id(), "restart", p.getName(), de),
                                    t(de, "<gold>" + name + " wird neu gestartet …", "<gold>" + name + " is restarting …"), q -> openServer(q, id)),
                            q -> openServer(q, id)));
            menu.set(20, Menu.item(Material.RED_CONCRETE, t(de, "<red>Stoppen", "<red>Stop"),
                    lobby ? t(de, "<red>Achtung: ohne Lobby kommt niemand rein!", "<red>Careful: without the lobby nobody can join!")
                            : t(de, "<gray>Spieler werden in die Lobby geschickt", "<gray>Players are sent to the lobby")),
                    p -> confirm(p, t(de, name + " stoppen?", "Stop " + name + "?"),
                            () -> run(p, plugin.client().serverAction(s.id(), "stop", p.getName(), de),
                                    t(de, "<red>" + name + " wird gestoppt …", "<red>" + name + " is stopping …"), q -> openServer(q, id)),
                            q -> openServer(q, id)));
        } else {
            menu.set(19, Menu.item(Material.LIME_CONCRETE, t(de, "<green>Starten", "<green>Start"),
                    t(de, "<gray>Der Start dauert meist unter einer Minute", "<gray>Starting usually takes less than a minute")),
                    p -> run(p, plugin.client().serverAction(s.id(), "start", p.getName(), de),
                            t(de, "<green>" + name + " wird gestartet …", "<green>" + name + " is starting …"), q -> openServer(q, id)));
        }

        boolean here = s.id().equals(plugin.client().serverId());
        menu.set(21, Menu.item(Material.ENDER_PEARL, t(de, "<aqua>Hinspringen", "<aqua>Go there"),
                here ? t(de, "<gray>Du bist schon hier", "<gray>You are already here") : t(de, "<gray>Auf diesen Server wechseln", "<gray>Switch to this server")),
                here ? null : p -> {
                    p.closeInventory();
                    plugin.connect(p, s.id());
                });

        if (!lobby) {
            menu.set(23, Menu.item(s.restricted() ? Material.IRON_BARS : Material.OAK_FENCE_GATE,
                    s.restricted() ? t(de, "<red>Geschützt: AN", "<red>Protected: ON") : t(de, "<green>Geschützt: AUS", "<green>Protected: OFF"),
                    t(de, "<gray>Betreten nur mit Recht", "<gray>Joining requires the permission"),
                    "<white>network.server." + MM.escapeTags(s.id()), "", t(de, "<yellow>Klicken zum Umschalten", "<yellow>Click to toggle")),
                    p -> run(p, plugin.client().serverSettings(s.id(), "restricted", !s.restricted(), p.getName(), de),
                            t(de, "<green>Gespeichert.", "<green>Saved."), q -> openServer(q, id)));
            menu.set(24, Menu.item(s.hidden() ? Material.ENDER_PEARL : Material.ENDER_EYE,
                    s.hidden() ? t(de, "<gray>Im Kompass: ausgeblendet", "<gray>In the compass: hidden")
                            : t(de, "<green>Im Kompass: sichtbar", "<green>In the compass: visible"),
                    "", t(de, "<yellow>Klicken zum Umschalten", "<yellow>Click to toggle")),
                    p -> run(p, plugin.client().serverSettings(s.id(), "hidden", !s.hidden(), p.getName(), de),
                            t(de, "<green>Gespeichert.", "<green>Saved."), q -> openServer(q, id)));
        }

        // Spieler auf diesem Server
        List<NetworkPlayer> players = plugin.client().players().stream()
                .filter(p -> p.server().equals(s.id()))
                .filter(p -> !p.vanished() || viewer.hasPermission("network.vanish.see"))
                .limit(7).toList();
        for (int i = 0; i < players.size(); i++) {
            NetworkPlayer target = players.get(i);
            menu.set(28 + i, Menu.item(Material.PLAYER_HEAD, "<white>" + MM.escapeTags(target.name()),
                    t(de, "<yellow>Klicken zum Verwalten", "<yellow>Click to manage")), p -> plugin.menus().openPlayer(p, target));
        }

        menu.set(40, Menu.item(Material.ARROW, t(de, "<yellow>← Alle Server", "<yellow>← All servers")), this::openServers);
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    // ---------- Hilfen ----------

    /** Sicherheitsabfrage mit Ja/Nein. */
    private void confirm(Player viewer, String question, Runnable yes, java.util.function.Consumer<Player> no) {
        boolean de = Lang.german(viewer.locale());
        Menu menu = new Menu(3, "<dark_gray>" + question);
        menu.set(11, Menu.item(Material.LIME_CONCRETE, t(de, "<green><bold>Ja", "<green><bold>Yes")), p -> yes.run());
        menu.set(15, Menu.item(Material.RED_CONCRETE, t(de, "<red><bold>Nein", "<red><bold>No")), no::accept);
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        viewer.openInventory(menu.getInventory());
    }

    /** Aktion ausführen, Ergebnis melden und das Menü kurz danach neu öffnen (mit frischem Status). */
    private void run(Player viewer, CompletableFuture<String> action, String success, java.util.function.Consumer<Player> reopen) {
        viewer.closeInventory();
        action.thenAccept(error -> {
            viewer.sendMessage(MM.deserialize(error == null ? success : "<red>" + MM.escapeTags(error)));
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                if (viewer.isOnline()) viewer.getScheduler().run(plugin, t2 -> reopen.accept(viewer), null);
            }, 40L);
        });
    }
}
