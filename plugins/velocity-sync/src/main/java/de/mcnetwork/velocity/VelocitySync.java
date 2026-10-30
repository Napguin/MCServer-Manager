package de.mcnetwork.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Verbindet den Proxy mit dem Dashboard:
 * - registriert neue Server zur Laufzeit und entfernt gelöschte
 * - meldet Online-Status, Spieler und Spielerzahlen
 * - setzt netzwerkweite Banns durch und führt Kicks aus
 * - netzwerkweite Tab-Liste und Chat über alle Server
 */
@Plugin(id = "velocity-sync", name = "VelocitySync", version = "1.1.0", authors = {"mcnetwork"},
        dependencies = {@Dependency(id = "luckperms", optional = true)})
public class VelocitySync {

    static final MinecraftChannelIdentifier CHAT = MinecraftChannelIdentifier.create("mcnetwork", "chat");

    private record Ban(long expires, String message, String messageEn) {
        /** Sprache beim Login oft noch unbekannt: dann beide Sprachen. */
        String text(Boolean german) {
            if (german == null) return message + "\n\n" + messageEn;
            return german ? message : messageEn;
        }

        boolean active() {
            return expires <= 0 || expires > System.currentTimeMillis();
        }
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    private final String dashboardUrl;
    private final String apiKey;
    private final GlobalTab tab;
    private final Motd motd;
    private boolean luckPerms;

    /** Server, die dieses Plugin selbst registriert hat (nur die werden auch wieder entfernt). */
    private final Set<String> managed = ConcurrentHashMap.newKeySet();
    private volatile Map<String, String> serverNames = Map.of();
    private volatile List<String> serverOrder = List.of();
    /** Geschützte Server: Beitritt nur mit dem Recht network.server.<id>. */
    private volatile Set<String> restricted = Set.of();
    private volatile Map<UUID, Ban> bans = Map.of();
    private volatile JsonObject settings = new JsonObject();
    private volatile boolean warned;

    @Inject
    public VelocitySync(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        this.dashboardUrl = env("DASHBOARD_URL", "http://dashboard:8080");
        this.apiKey = env("DASHBOARD_API_KEY", "");
        this.tab = new GlobalTab(proxy, this);
        this.motd = new Motd(this);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        if (apiKey.isEmpty()) {
            logger.error("DASHBOARD_API_KEY ist nicht gesetzt, Synchronisierung deaktiviert.");
            return;
        }
        luckPerms = proxy.getPluginManager().isLoaded("luckperms");
        proxy.getChannelRegistrar().register(CHAT);
        registerHubCommand();
        proxy.getScheduler().buildTask(this, this::sync).delay(1, TimeUnit.SECONDS).repeat(3, TimeUnit.SECONDS).schedule();
        proxy.getScheduler().buildTask(this, this::safeTabTick).delay(3, TimeUnit.SECONDS).repeat(2, TimeUnit.SECONDS).schedule();
        logger.info("VelocitySync aktiv, Dashboard: {}, LuckPerms: {}", dashboardUrl, luckPerms ? "ja" : "nein");
    }

    // ---------- /hub und /lobby ----------

    private static final String LOBBY = "lobby";
    private static final Set<String> HUB_ALIASES = Set.of("hub", "lobby", "l");

    /** Bringt Spieler von jedem Server zurück in die Lobby. */
    private void registerHubCommand() {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("hub").aliases("lobby", "l").plugin(this).build();
        proxy.getCommandManager().register(meta, (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Only in game."));
                return;
            }
            Optional<RegisteredServer> lobby = proxy.getServer(LOBBY);
            if (lobby.isEmpty()) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(Lang.t(player, "<red>Die Lobby ist gerade nicht erreichbar.", "<red>The lobby is not reachable right now.")));
                return;
            }
            player.sendMessage(MiniMessage.miniMessage().deserialize(Lang.t(player, "<gray>Du wirst in die Lobby gebracht ...", "<gray>Sending you to the lobby ...")));
            player.createConnectionRequest(lobby.get()).fireAndForget();
        });
    }

    /** Wer schon in der Lobby ist, landet mit /hub am Spawn (das übernimmt das Lobby-Plugin). */
    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) return;
        String root = event.getCommand().split(" ", 2)[0].toLowerCase();
        if (!HUB_ALIASES.contains(root)) return;
        boolean inLobby = player.getCurrentServer().map(c -> c.getServerInfo().getName().equals(LOBBY)).orElse(false);
        if (inLobby) event.setResult(CommandExecuteEvent.CommandResult.forwardToServer("spawn"));
    }

    // ---------- Zugriff für die Tab-Liste ----------

    JsonObject settings() {
        return settings;
    }

    String serverName(String id) {
        return serverNames.getOrDefault(id, id);
    }

    List<String> serverOrder() {
        return serverOrder;
    }

    boolean isVanished(Player player) {
        try {
            return luckPerms && Ranks.vanished(player);
        } catch (Throwable e) {
            return false;
        }
    }

    String prefix(Player player) {
        try {
            return luckPerms ? Ranks.prefix(player) : "";
        } catch (Throwable e) {
            return "";
        }
    }

    String suffix(Player player) {
        try {
            return luckPerms ? Ranks.suffix(player) : "";
        } catch (Throwable e) {
            return "";
        }
    }

    int weight(Player player) {
        try {
            return luckPerms ? Ranks.weight(player) : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private void safeTabTick() {
        try {
            tab.tick();
        } catch (Exception e) {
            logger.warn("Tab-Liste konnte nicht aktualisiert werden: {}", e.toString());
        }
    }

    // ---------- Abgleich mit dem Dashboard ----------

    private void sync() {
        try {
            syncServers();
            warned = false;
        } catch (Exception e) {
            if (!warned) logger.warn("Dashboard nicht erreichbar: {}", e.getMessage());
            warned = true;
        }
        try {
            reportStatus();
        } catch (Exception e) {
            if (!warned) logger.warn("Status konnte nicht gemeldet werden: {}", e.getMessage());
        }
    }

    private void syncServers() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(dashboardUrl + "/api/internal/servers"))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        JsonObject body = gson.fromJson(response.body(), JsonObject.class);

        Set<String> wanted = new HashSet<>();
        Set<String> locked = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("servers")) {
            JsonObject server = element.getAsJsonObject();
            String id = server.get("id").getAsString();
            String host = server.get("host").getAsString();
            int port = server.get("port").getAsInt();
            wanted.add(id);
            names.put(id, server.get("name").getAsString());
            order.add(id);
            if (server.has("restricted") && server.get("restricted").getAsBoolean()) locked.add(id);

            Optional<RegisteredServer> existing = proxy.getServer(id);
            if (existing.isPresent()) {
                ServerInfo info = existing.get().getServerInfo();
                if (info.getAddress().getHostString().equals(host) && info.getAddress().getPort() == port) continue;
                proxy.unregisterServer(info);
            }
            proxy.registerServer(new ServerInfo(id, InetSocketAddress.createUnresolved(host, port)));
            managed.add(id);
            logger.info("Server registriert: {} -> {}:{}", id, host, port);
        }
        for (String id : Set.copyOf(managed)) {
            if (!wanted.contains(id)) {
                proxy.getServer(id).ifPresent(server -> proxy.unregisterServer(server.getServerInfo()));
                managed.remove(id);
                logger.info("Server entfernt: {}", id);
            }
        }
        serverNames = Map.copyOf(names);
        serverOrder = List.copyOf(order);
        restricted = Set.copyOf(locked);

        if (body.has("lobby") && body.get("lobby").isJsonObject()) settings = body.getAsJsonObject("lobby");

        Map<UUID, Ban> newBans = new HashMap<>();
        if (body.has("bans")) {
            for (JsonElement element : body.getAsJsonArray("bans")) {
                JsonObject ban = element.getAsJsonObject();
                long expires = ban.get("expires").isJsonNull() ? 0 : ban.get("expires").getAsLong();
                newBans.put(UUID.fromString(ban.get("uuid").getAsString()), new Ban(expires, ban.get("message").getAsString(),
                        ban.has("message_en") ? ban.get("message_en").getAsString() : ban.get("message").getAsString()));
            }
        }
        bans = Map.copyOf(newBans);

        // MOTD (mit Server-Icon) nur neu laden, wenn sie sich im Dashboard geändert hat
        String motdVersion = str(body, "motdVersion", "");
        if (!motdVersion.isEmpty() && !motdVersion.equals(motd.version())) {
            HttpRequest motdRequest = HttpRequest.newBuilder(URI.create(dashboardUrl + "/api/internal/motd"))
                    .header("X-Api-Key", apiKey).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> motdResponse = http.send(motdRequest, HttpResponse.BodyHandlers.ofString());
            if (motdResponse.statusCode() == 200) {
                motd.update(gson.fromJson(motdResponse.body(), JsonObject.class));
                logger.info("MOTD aktualisiert{}", motd.maintenance() ? " (Wartungsmodus aktiv)" : "");
            }
        }
    }

    private List<Player> visiblePlayers() {
        return proxy.getAllPlayers().stream().filter(p -> !isVanished(p)).toList();
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        motd.onPing(event, visiblePlayers());
    }

    private void reportStatus() {
        Map<String, CompletableFuture<Boolean>> pings = new HashMap<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            pings.put(server.getServerInfo().getName(), server.ping()
                    .orTimeout(2, TimeUnit.SECONDS)
                    .thenApply(ping -> true)
                    .exceptionally(error -> false));
        }

        CompletableFuture.allOf(pings.values().toArray(CompletableFuture[]::new)).thenRun(() -> {
            Map<String, Integer> counts = new HashMap<>();
            JsonArray players = new JsonArray();
            int visible = 0;
            for (Player player : proxy.getAllPlayers()) {
                String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
                boolean vanished = isVanished(player);
                JsonObject p = new JsonObject();
                p.addProperty("uuid", player.getUniqueId().toString());
                p.addProperty("name", player.getUsername());
                p.addProperty("server", server);
                p.addProperty("vanished", vanished);
                p.addProperty("ping", player.getPing());
                players.add(p);
                if (!vanished) {
                    counts.merge(server, 1, Integer::sum);
                    visible++;
                }
            }

            JsonObject result = new JsonObject();
            pings.forEach((name, future) -> {
                JsonObject status = new JsonObject();
                status.addProperty("online", future.join());
                status.addProperty("players", counts.getOrDefault(name, 0));
                result.add(name, status);
            });
            JsonObject body = new JsonObject();
            body.add("servers", result);
            body.add("players", players);
            body.addProperty("totalPlayers", visible);

            HttpRequest request = HttpRequest.newBuilder(URI.create(dashboardUrl + "/api/internal/status"))
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(this::handleStatusResponse);
        });
    }

    /** Die Antwort enthält Kicks, die im Dashboard oder Admin-GUI ausgelöst wurden. */
    private void handleStatusResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) return;
        try {
            JsonObject body = gson.fromJson(response.body(), JsonObject.class);
            if (!body.has("kicks")) return;
            for (JsonElement element : body.getAsJsonArray("kicks")) {
                JsonObject kick = element.getAsJsonObject();
                UUID uuid = UUID.fromString(kick.get("uuid").getAsString());
                String reasonDe = kick.get("reason").getAsString();
                String reasonEn = kick.has("reason_en") ? kick.get("reason_en").getAsString() : reasonDe;
                proxy.getPlayer(uuid).ifPresent(player -> {
                    String reason = Lang.german(player) ? reasonDe : reasonEn;
                    player.disconnect(Component.text(reason));
                    logger.info("{} wurde gekickt: {}", player.getUsername(), reason);
                });
            }
        } catch (RuntimeException e) {
            logger.warn("Ungültige Antwort vom Dashboard: {}", e.getMessage());
        }
    }

    // ---------- Ereignisse ----------

    @Subscribe
    public void onLogin(LoginEvent event) {
        Ban ban = bans.get(event.getPlayer().getUniqueId());
        if (ban != null && ban.active()) {
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(ban.text(Lang.germanOrNull(event.getPlayer())))));
            return;
        }
        motd.onLogin(event);
    }

    /** Geschützte Server nur mit dem Recht network.server.<id> betreten (gilt für Kompass, /server, Admin-Menü). */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String id = event.getOriginalServer().getServerInfo().getName();
        Player player = event.getPlayer();
        if (!restricted.contains(id) || player.hasPermission("network.server." + id)) return;
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                Lang.t(player, "<red>Du hast keinen Zugang zu <white><server></white>.", "<red>You don't have access to <white><server></white>."),
                Placeholder.unparsed("server", serverName(id))));
        logger.info("{} wurde der Zugang zu {} verweigert (fehlendes Recht network.server.{})", player.getUsername(), id, id);
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        tab.reset(event.getPlayer());
        proxy.getScheduler().buildTask(this, this::safeTabTick).delay(500, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        tab.reset(event.getPlayer());
        proxy.getScheduler().buildTask(this, this::safeTabTick).delay(200, TimeUnit.MILLISECONDS).schedule();
    }

    /** Netzwerk-Chat: Nachricht von einem Server an alle anderen Server weiterreichen. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHAT)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        JsonElement enabled = settings.get("globalChat");
        if (enabled != null && !enabled.isJsonNull() && !enabled.getAsBoolean()) return;

        byte[] data = event.getData();
        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.equals(source.getServer()) || server.getPlayersConnected().isEmpty()) continue;
            server.sendPluginMessage(CHAT, data);
        }
    }
}
