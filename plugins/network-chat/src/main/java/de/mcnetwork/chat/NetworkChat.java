package de.mcnetwork.chat;

import de.mcnetwork.chat.NetworkClient.Mute;
import de.mcnetwork.chat.NetworkClient.NetworkPlayer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Netzwerk-Funktionen auf jedem Server:
 * Prefixe in Chat und Tab, Netzwerk-Chat, netzwerkweiter Vanish, Mutes,
 * Admin-GUI (/admin) und Moderations-Befehle (/kick, /ban, /mute ...).
 */
public class NetworkChat extends JavaPlugin implements Listener, PluginMessageListener {

    static final String CHAT_CHANNEL = "mcnetwork:chat";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    // Prefixe aus dem Dashboard nutzen &-Farbcodes, auch &#RRGGBB
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private boolean luckPerms;
    private String chatFormat;
    private boolean tabList;
    private NetworkClient client;
    private AdminMenus menus;
    private ServerMenus serverMenus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chatFormat = getConfig().getString("chat-format", "<player><dark_gray> » </dark_gray><white><message></white>");
        tabList = getConfig().getBoolean("tab-list", true);
        client = new NetworkClient(getLogger(), env("DASHBOARD_URL", "http://dashboard:8080"),
                env("DASHBOARD_API_KEY", ""), env("NETWORK_SERVER_ID", "server"));
        menus = new AdminMenus(this);
        serverMenus = new ServerMenus(this);

        luckPerms = getServer().getPluginManager().isPluginEnabled("LuckPerms");
        if (luckPerms) {
            LuckPermsHook.onRecalculate(this, uuid -> {
                Player player = getServer().getPlayer(uuid);
                if (player != null) player.getScheduler().run(this, task -> {
                    updateTabList(player);
                    applyVisibility(player);
                }, null);
            });
        } else {
            getLogger().warning("LuckPerms nicht gefunden – Prefixe und Vanish funktionieren nicht.");
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHAT_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHAT_CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getAsyncScheduler().runAtFixedRate(this, task -> client.refresh(), 1, 3, TimeUnit.SECONDS);
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            for (Player player : getServer().getOnlinePlayers()) {
                if (isVanished(player)) player.sendActionBar(MM.deserialize(Lang.t(player,
                        "<light_purple>Du bist unsichtbar <gray>(/vanish)", "<light_purple>You are vanished <gray>(/vanish)")));
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    AdminMenus menus() {
        return menus;
    }

    ServerMenus serverMenus() {
        return serverMenus;
    }

    NetworkClient client() {
        return client;
    }

    boolean hasLuckPerms() {
        return luckPerms;
    }

    // ---------- LuckPerms-Werte (sicher, auch ohne LuckPerms) ----------

    private String prefix(Player player) {
        try {
            return luckPerms ? LuckPermsHook.prefix(player) : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private String suffix(Player player) {
        try {
            return luckPerms ? LuckPermsHook.suffix(player) : "";
        } catch (Throwable e) {
            return "";
        }
    }

    boolean isVanished(Player player) {
        try {
            return luckPerms && LuckPermsHook.vanished(player);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------- Chat ----------

    private Component format(Player player, Component message) {
        String prefix = prefix(player);
        String suffix = suffix(player);
        return MM.deserialize(chatFormat,
                Placeholder.component("player", LEGACY.deserialize(prefix + player.getName() + suffix)),
                Placeholder.component("prefix", LEGACY.deserialize(prefix)),
                Placeholder.component("suffix", LEGACY.deserialize(suffix)),
                Placeholder.unparsed("name", player.getName()),
                Placeholder.component("message", message));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Mute mute = client.mute(player.getUniqueId());
        if (mute != null) {
            event.setCancelled(true);
            boolean de = Lang.german(player.locale());
            String date = new SimpleDateFormat(de ? "dd.MM. HH:mm" : "MMM d, HH:mm", de ? Locale.GERMANY : Locale.ENGLISH).format(new Date(mute.expires()));
            String reason = MM.escapeTags(mute.reason());
            player.sendMessage(MM.deserialize(de
                    ? "<red>Du bist " + (mute.expires() <= 0 ? "dauerhaft" : "bis " + date) + " gemutet. <gray>Grund: <white>" + reason
                    : "<red>You are muted " + (mute.expires() <= 0 ? "permanently" : "until " + date) + ". <gray>Reason: <white>" + reason));
            return;
        }

        Component formatted = format(player, event.message());
        event.renderer((source, displayName, message, viewer) -> formatted);

        // Netzwerk-Chat: über den Proxy an alle anderen Server
        if (client.globalChat() && client.configured()) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(client.serverId());
                out.writeUTF(GsonComponentSerializer.gson().serialize(formatted));
                byte[] data = bytes.toByteArray();
                player.getScheduler().run(this, task -> player.sendPluginMessage(this, CHAT_CHANNEL, data), null);
            } catch (IOException e) {
                getLogger().warning("Chat konnte nicht weitergeleitet werden: " + e.getMessage());
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] data) {
        if (!CHAT_CHANNEL.equals(channel)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String server = in.readUTF();
            Component message = GsonComponentSerializer.gson().deserialize(in.readUTF());
            Component line = MM.deserialize("<dark_gray>[<gray><server></gray>]</dark_gray> ",
                    Placeholder.unparsed("server", client.serverName(server))).append(message);
            getServer().getGlobalRegionScheduler().run(this, task -> getServer().sendMessage(line));
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Ungültige Chat-Nachricht vom Proxy: " + e.getMessage());
        }
    }

    // ---------- Tab-Liste & Vanish ----------

    private void updateTabList(Player player) {
        if (!tabList || !player.isOnline() || !luckPerms) return;
        try {
            player.playerListName(LEGACY.deserialize(prefix(player) + player.getName() + suffix(player)));
            // Höheres Rollen-Gewicht steht in der Tab-Liste weiter oben
            player.setPlayerListOrder(LuckPermsHook.weight(player));
        } catch (Throwable ignored) {
            // LuckPerms noch nicht bereit
        }
    }

    /** Versteckt einen unsichtbaren Spieler vor allen, die das Recht network.vanish.see nicht haben. */
    private void applyVisibility(Player target) {
        boolean vanished = isVanished(target);
        for (Player other : getServer().getOnlinePlayers()) {
            if (other == target) continue;
            boolean hide = vanished && !other.hasPermission("network.vanish.see");
            other.getScheduler().run(this, task -> {
                if (hide) other.hidePlayer(this, target);
                else other.showPlayer(this, target);
            }, null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isVanished(player)) {
            event.joinMessage(null);
            player.sendMessage(MM.deserialize(Lang.t(player,
                    "<light_purple>Du bist unsichtbar. <gray>Mit /vanish wieder sichtbar werden.",
                    "<light_purple>You are vanished. <gray>Use /vanish to become visible again.")));
        }
        updateTabList(player);
        applyVisibility(player);
        if (!player.hasPermission("network.vanish.see")) {
            for (Player other : getServer().getOnlinePlayers()) {
                if (other != player && isVanished(other)) player.hidePlayer(this, other);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isVanished(event.getPlayer())) event.quitMessage(null);
    }

    // ---------- Admin-GUI ----------

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getWhoClicked() instanceof Player player) menu.click(player, event.getSlot());
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    void connect(Player player, String serverId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(serverId);
            player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
        } catch (IOException e) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Serverwechsel fehlgeschlagen.", "<red>Could not switch servers.")));
        }
    }

    void moderate(CommandSender sender, String action, String target, long seconds, String reason) {
        boolean de = Lang.german(sender);
        client.moderate(action, target, seconds, reason, sender.getName(), de).thenAccept(error -> {
            if (error != null) {
                sender.sendMessage(MM.deserialize("<red>" + MM.escapeTags(error)));
                return;
            }
            String who = "<white>" + MM.escapeTags(target) + "</white>";
            String time = de
                    ? (seconds <= 0 ? "dauerhaft" : "für " + describe(seconds, true))
                    : (seconds <= 0 ? "permanently" : "for " + describe(seconds, false));
            sender.sendMessage(MM.deserialize("<green>" + (de ? switch (action) {
                case "kick" -> who + " wurde gekickt.";
                case "ban" -> who + " wurde " + time + " gebannt.";
                case "unban" -> who + " wurde entbannt.";
                case "mute" -> who + " wurde " + time + " gemutet.";
                case "unmute" -> who + " wurde entmutet.";
                default -> "Erledigt.";
            } : switch (action) {
                case "kick" -> who + " was kicked.";
                case "ban" -> who + " was banned " + time + ".";
                case "unban" -> who + " was unbanned.";
                case "mute" -> who + " was muted " + time + ".";
                case "unmute" -> who + " was unmuted.";
                default -> "Done.";
            })));
        });
    }

    // ---------- Befehle ----------

    /** "30m", "2h", "7d", "1w" in Sekunden; "perm" = 0; -1 = keine Dauer. */
    private static long parseDuration(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("dauerhaft")) return 0;
        if (!value.matches("\\d+[mhdw]")) return -1;
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return amount * switch (value.charAt(value.length() - 1)) {
            case 'm' -> 60L;
            case 'h' -> 3600L;
            case 'd' -> 86400L;
            default -> 604800L;
        };
    }

    private static String describe(long seconds, boolean de) {
        if (seconds % 604800 == 0) return seconds / 604800 + (de ? " Woche(n)" : " week(s)");
        if (seconds % 86400 == 0) return seconds / 86400 + (de ? " Tag(e)" : " day(s)");
        if (seconds % 3600 == 0) return seconds / 3600 + (de ? " Stunde(n)" : " hour(s)");
        return seconds / 60 + (de ? " Minute(n)" : " minute(s)");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "vanish" -> {
                if (!(sender instanceof Player player)) return true;
                if (!luckPerms) {
                    sender.sendMessage(MM.deserialize(Lang.t(sender, "<red>Vanish braucht LuckPerms.", "<red>Vanish requires LuckPerms.")));
                    return true;
                }
                boolean now = !isVanished(player);
                LuckPermsHook.setVanished(player.getUniqueId(), now).thenRun(() -> player.sendMessage(MM.deserialize(now
                        ? Lang.t(player, "<light_purple>Du bist jetzt im ganzen Netzwerk unsichtbar.", "<light_purple>You are now vanished across the whole network.")
                        : Lang.t(player, "<green>Du bist wieder sichtbar.", "<green>You are visible again."))));
            }
            case "admin" -> {
                if (!(sender instanceof Player player)) return true;
                if (args.length > 0 && (args[0].equalsIgnoreCase("server") || args[0].equalsIgnoreCase("servers"))) {
                    if (!player.hasPermission("network.servers")) {
                        sender.sendMessage(MM.deserialize(Lang.t(sender, "<red>Dafür fehlt dir das Recht network.servers.", "<red>You need the permission network.servers.")));
                    } else {
                        serverMenus.openServers(player);
                    }
                    return true;
                }
                if (args.length > 0) {
                    Optional<NetworkPlayer> target = client.players().stream()
                            .filter(p -> p.name().equalsIgnoreCase(args[0])).findFirst();
                    if (target.isPresent()) {
                        menus.openPlayer(player, target.get());
                        return true;
                    }
                    sender.sendMessage(MM.deserialize("<red>" + MM.escapeTags(args[0]) + Lang.t(sender, " ist nicht online.", " is not online.")));
                }
                menus.openPlayers(player, 0);
            }
            case "kick", "unban", "unmute" -> {
                if (args.length < 1) return false;
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : Lang.t(sender, "Vom Team gekickt", "Kicked by staff");
                moderate(sender, name, args[0], 0, reason);
            }
            case "ban", "mute" -> {
                if (args.length < 1) return false;
                long seconds = 0;
                int reasonStart = 1;
                if (args.length > 1 && parseDuration(args[1]) >= 0) {
                    seconds = parseDuration(args[1]);
                    reasonStart = 2;
                }
                String reason = args.length > reasonStart
                        ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length))
                        : (name.equals("ban") ? Lang.t(sender, "Vom Team gebannt", "Banned by staff") : Lang.t(sender, "Vom Team gemutet", "Muted by staff"));
                moderate(sender, name, args[0], seconds, reason);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && !name.equals("vanish")) {
            String start = args[0].toLowerCase(Locale.ROOT);
            java.util.stream.Stream<String> names = client.players().stream().map(NetworkPlayer::name);
            if (name.equals("admin") && sender.hasPermission("network.servers")) {
                names = java.util.stream.Stream.concat(java.util.stream.Stream.of("server"), names);
            }
            return names.filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        if (args.length == 2 && (name.equals("ban") || name.equals("mute"))) {
            return List.of("1h", "1d", "7d", "30d", "perm");
        }
        return List.of();
    }
}
