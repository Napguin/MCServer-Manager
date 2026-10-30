package de.mcnetwork.chat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Spielerliste, Mutes und Einstellungen vom Dashboard; schickt Moderations-Aktionen zurück. */
final class NetworkClient {

    record NetworkPlayer(UUID uuid, String name, String server, boolean vanished, int ping) {
    }

    /** Ein Server aus dem Dashboard, für die Server-Verwaltung im Admin-GUI. */
    record NetworkServer(String id, String name, String icon, String status, boolean online, int players,
                         boolean restricted, boolean hidden, String type, String version, String memory) {
    }

    record Mute(long expires, String reason) {
        boolean active() {
            return expires <= 0 || expires > System.currentTimeMillis();
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    private final Logger logger;
    private final String url;
    private final String apiKey;
    private final String serverId;

    private volatile List<NetworkPlayer> players = List.of();
    private volatile Map<UUID, Mute> mutes = Map.of();
    private volatile Map<String, String> serverNames = Map.of();
    private volatile boolean globalChat = true;
    private volatile List<NetworkServer> servers = List.of();
    private volatile boolean maintenance;
    private boolean warned;

    NetworkClient(Logger logger, String url, String apiKey, String serverId) {
        this.logger = logger;
        this.url = url;
        this.apiKey = apiKey;
        this.serverId = serverId;
    }

    String serverId() {
        return serverId;
    }

    String serverName(String id) {
        return serverNames.getOrDefault(id, id);
    }

    List<NetworkPlayer> players() {
        return players;
    }

    Mute mute(UUID uuid) {
        Mute mute = mutes.get(uuid);
        return mute != null && mute.active() ? mute : null;
    }

    boolean globalChat() {
        return globalChat;
    }

    List<NetworkServer> servers() {
        return servers;
    }

    boolean maintenance() {
        return maintenance;
    }

    boolean configured() {
        return !apiKey.isEmpty();
    }

    void refresh() {
        if (!configured()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/internal/servers"))
                    .header("X-Api-Key", apiKey).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonObject body = gson.fromJson(response.body(), JsonObject.class);

            Map<String, String> names = new HashMap<>();
            List<NetworkServer> serverList = new ArrayList<>();
            for (JsonElement e : body.getAsJsonArray("servers")) {
                JsonObject s = e.getAsJsonObject();
                names.put(s.get("id").getAsString(), s.get("name").getAsString());
                serverList.add(new NetworkServer(s.get("id").getAsString(), s.get("name").getAsString(),
                        str(s, "icon", "GRASS_BLOCK"), str(s, "status", "unknown"),
                        s.has("online") && s.get("online").getAsBoolean(), s.has("players") ? s.get("players").getAsInt() : 0,
                        s.has("restricted") && s.get("restricted").getAsBoolean(), s.has("hidden") && s.get("hidden").getAsBoolean(),
                        str(s, "typeLabel", "Paper"), str(s, "version", "?"), str(s, "memory", "?")));
            }
            servers = List.copyOf(serverList);
            maintenance = body.has("maintenance") && body.get("maintenance").getAsBoolean();
            List<NetworkPlayer> list = new ArrayList<>();
            if (body.has("players")) {
                for (JsonElement e : body.getAsJsonArray("players")) {
                    JsonObject p = e.getAsJsonObject();
                    list.add(new NetworkPlayer(UUID.fromString(p.get("uuid").getAsString()), p.get("name").getAsString(),
                            p.get("server").getAsString(), p.get("vanished").getAsBoolean(), p.get("ping").getAsInt()));
                }
            }
            list.sort(Comparator.comparing(p -> p.name().toLowerCase()));
            Map<UUID, Mute> newMutes = new HashMap<>();
            if (body.has("mutes")) {
                for (JsonElement e : body.getAsJsonArray("mutes")) {
                    JsonObject m = e.getAsJsonObject();
                    long expires = m.get("expires").isJsonNull() ? 0 : m.get("expires").getAsLong();
                    newMutes.put(UUID.fromString(m.get("uuid").getAsString()), new Mute(expires, m.get("reason").getAsString()));
                }
            }
            if (body.has("lobby") && body.get("lobby").isJsonObject()) {
                JsonElement chat = body.getAsJsonObject("lobby").get("globalChat");
                globalChat = chat == null || chat.isJsonNull() || chat.getAsBoolean();
            }
            serverNames = Map.copyOf(names);
            players = List.copyOf(list);
            mutes = Map.copyOf(newMutes);
            warned = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!warned) logger.warning("Dashboard nicht erreichbar: " + e.getMessage());
            warned = true;
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    /** Server starten/stoppen/neu starten ("start", "stop", "restart"). Liefert null bei Erfolg. */
    CompletableFuture<String> serverAction(String serverId, String action, String by, boolean german) {
        JsonObject body = new JsonObject();
        body.addProperty("by", by);
        body.addProperty("lang", german ? "de" : "en");
        return post("/api/internal/servers/" + serverId + "/" + action, body, german);
    }

    /** "Geschützt" bzw. "im Kompass ausblenden" umschalten. */
    CompletableFuture<String> serverSettings(String serverId, String key, boolean value, String by, boolean german) {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        body.addProperty("by", by);
        body.addProperty("lang", german ? "de" : "en");
        return post("/api/internal/servers/" + serverId + "/settings", body, german);
    }

    CompletableFuture<String> setMaintenance(boolean enabled, String by, boolean german) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", enabled);
        body.addProperty("by", by);
        return post("/api/internal/maintenance", body, german);
    }

    private CompletableFuture<String> post(String path, JsonObject body, boolean german) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + path))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 200) {
                refresh();
                return (String) null;
            }
            try {
                return gson.fromJson(response.body(), JsonObject.class).get("error").getAsString();
            } catch (RuntimeException e) {
                return (german ? "Fehler " : "Error ") + response.statusCode();
            }
        }).exceptionally(error -> german ? "Dashboard nicht erreichbar" : "Dashboard not reachable");
    }

    /**
     * Kick/Bann/Mute über das Dashboard (gilt im ganzen Netzwerk).
     * Liefert null bei Erfolg, sonst eine Fehlermeldung.
     */
    CompletableFuture<String> moderate(String action, String target, long durationSeconds, String reason, String by, boolean german) {
        JsonObject body = new JsonObject();
        body.addProperty("lang", german ? "de" : "en"); // Sprache der Fehlermeldungen
        body.addProperty("action", action);
        body.addProperty("target", target);
        body.addProperty("duration", durationSeconds);
        body.addProperty("reason", reason);
        body.addProperty("by", by);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/internal/moderation"))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 200) {
                refresh();
                return null;
            }
            try {
                return gson.fromJson(response.body(), JsonObject.class).get("error").getAsString();
            } catch (RuntimeException e) {
                return (german ? "Fehler " : "Error ") + response.statusCode();
            }
        }).exceptionally(error -> german ? "Dashboard nicht erreichbar" : "Dashboard not reachable");
    }
}
