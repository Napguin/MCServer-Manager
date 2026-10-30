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
