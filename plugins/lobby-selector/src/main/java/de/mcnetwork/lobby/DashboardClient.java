package de.mcnetwork.lobby;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Holt Serverliste, Lobby-Einstellungen und Spielerzahl vom Dashboard (läuft asynchron). */
final class DashboardClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    private final Logger logger;
    private final String url;
    private final String apiKey;

    // Werden komplett ersetzt, nie verändert
    private volatile List<ServerEntry> servers = List.of();
    private volatile LobbySettings settingsDe = LobbySettings.defaults(true);
    private volatile LobbySettings settingsEn = LobbySettings.defaults(false);
    private volatile int totalPlayers;
    private volatile JsonObject extras = new JsonObject();
    private boolean warned;

    DashboardClient(Logger logger, String url, String apiKey) {
        this.logger = logger;
        this.url = url;
        this.apiKey = apiKey;
    }

    List<ServerEntry> servers() {
        return servers;
    }

    LobbySettings settings(boolean german) {
        return german ? settingsDe : settingsEn;
    }

    int totalPlayers() {
        return totalPlayers;
    }

    /** Hologramme, NPCs, Schilder, Jump & Runs, Cosmetics und Bestenlisten (nie verändern). */
    JsonObject extras() {
        return extras;
    }

    /**
     * Eintrag der Lobby-Extras speichern (data = null löscht ihn). Blockiert – nur asynchron aufrufen.
     * Wirft eine IOException mit der Fehlermeldung des Dashboards.
     */
    void saveExtra(String kind, String id, JsonObject data, String by) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("id", id);
        body.add("data", data);
        body.addProperty("by", by);
        JsonObject answer = post("/api/internal/extras", body);
        if (answer.has("extras")) extras = answer.getAsJsonObject("extras");
    }

    /** Zeit eines Jump & Runs melden. Antwort: improved, previous, best, rank, record, leaderboard. */
    JsonObject submitTime(String parkour, UUID uuid, String name, long ms) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("parkour", parkour);
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        body.addProperty("ms", ms);
        JsonObject answer = post("/api/internal/parkour/time", body);
        // Neue Bestenliste sofort übernehmen, nicht erst beim nächsten Abruf
        if (answer.has("leaderboard") && extras.has("leaderboards")) {
            JsonObject copy = extras.deepCopy();
            copy.getAsJsonObject("leaderboards").add(parkour, answer.get("leaderboard"));
            extras = copy;
        }
        return answer;
    }

    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + path))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject answer;
        try {
            answer = gson.fromJson(response.body(), JsonObject.class);
        } catch (RuntimeException e) {
            answer = null;
        }
        if (response.statusCode() != 200) {
            String error = answer != null && answer.has("error") ? answer.get("error").getAsString() : "HTTP " + response.statusCode();
            throw new IOException(error);
        }
        return answer == null ? new JsonObject() : answer;
    }

    void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/internal/servers"))
                    .header("X-Api-Key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                warn("Dashboard antwortet mit HTTP " + response.statusCode());
                return;
            }
            JsonObject body = gson.fromJson(response.body(), JsonObject.class);

            List<ServerEntry> list = new ArrayList<>();
            for (JsonElement element : body.getAsJsonArray("servers")) {
                JsonObject s = element.getAsJsonObject();
                if (s.has("hidden") && s.get("hidden").getAsBoolean()) continue;
                list.add(new ServerEntry(
                        s.get("id").getAsString(),
                        s.get("name").getAsString(),
                        s.has("description") ? s.get("description").getAsString() : "",
                        s.has("icon") ? s.get("icon").getAsString() : "GRASS_BLOCK",
                        s.has("slot") ? s.get("slot").getAsInt() : -1,
                        s.has("online") && s.get("online").getAsBoolean(),
                        s.has("players") ? s.get("players").getAsInt() : 0,
                        s.has("status") ? s.get("status").getAsString() : "unknown",
                        s.has("restricted") && s.get("restricted").getAsBoolean()));
            }
            servers = List.copyOf(list);
            if (body.has("lobby") && body.get("lobby").isJsonObject()) {
                JsonObject lobby = body.getAsJsonObject("lobby");
                settingsDe = LobbySettings.fromJson(lobby, true);
                settingsEn = LobbySettings.fromJson(lobby, false);
            }
            if (body.has("extras") && body.get("extras").isJsonObject()) extras = body.getAsJsonObject("extras");
            totalPlayers = body.has("totalPlayers") ? body.get("totalPlayers").getAsInt() : 0;
            warned = false;
        } catch (IOException e) {
            warn("Dashboard nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            warn("Ungültige Antwort vom Dashboard: " + e.getMessage());
        }
    }

    // Nur einmal warnen, bis es wieder klappt – sonst läuft das Log alle 3 Sekunden voll
    private void warn(String message) {
        if (!warned) logger.warning(message);
        warned = true;
    }
}
