package de.mcnetwork.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.List;

/**
 * Einstellungen der Lobby aus dem Dashboard (Seite "Lobby & Tab").
 * Texte gibt es auf Deutsch und Englisch ("<feld>_en"); fehlt ein englischer Text, gilt der deutsche.
 */
record LobbySettings(
        String networkName,
        String welcomeTitle,
        String welcomeSubtitle,
        String hologram,
        String scoreboardTitle,
        List<String> scoreboardLines,
        String bossBar,
        boolean doubleJump,
        boolean playerHider,
        boolean launchPads,
        boolean hideJoinMessages,
        boolean voidTeleport
) {

    /** Gilt, solange das Dashboard noch nicht erreicht wurde. */
    static LobbySettings defaults(boolean german) {
        return german
                ? new LobbySettings("Mein Netzwerk",
                        "<gold><bold>Willkommen!",
                        "<gray>Wähle mit dem Kompass einen Server",
                        "<gold><bold><network></bold></gold>\n<gray>Rechtsklick auf den Kompass, um einen Server zu wählen",
                        "<gold><bold><network>",
                        List.of(" ", "<white>Rang: <rank>", "<white>Online: <green><online>", "  ", "<white>Server: <aqua><servers>"),
                        "", true, true, true, true, true)
                : new LobbySettings("My Network",
                        "<gold><bold>Welcome!",
                        "<gray>Use the compass to pick a server",
                        "<gold><bold><network></bold></gold>\n<gray>Right-click the compass to choose a server",
                        "<gold><bold><network>",
                        List.of(" ", "<white>Rank: <rank>", "<white>Online: <green><online>", "  ", "<white>Servers: <aqua><servers>"),
                        "", true, true, true, true, true);
    }

    static LobbySettings fromJson(JsonObject o, boolean german) {
        LobbySettings d = defaults(german);
        String lines = text(o, "scoreboardLines", String.join("\n", d.scoreboardLines()), german);
        return new LobbySettings(
                str(o, "networkName", d.networkName()),
                text(o, "welcomeTitle", d.welcomeTitle(), german),
                text(o, "welcomeSubtitle", d.welcomeSubtitle(), german),
                text(o, "hologram", d.hologram(), german),
                text(o, "scoreboardTitle", d.scoreboardTitle(), german),
                lines.isEmpty() ? List.of() : Arrays.stream(lines.split("\n", -1)).limit(15).toList(),
                text(o, "bossBar", d.bossBar(), german),
                bool(o, "doubleJump", d.doubleJump()),
                bool(o, "playerHider", d.playerHider()),
                bool(o, "launchPads", d.launchPads()),
                bool(o, "hideJoinMessages", d.hideJoinMessages()),
                bool(o, "voidTeleport", d.voidTeleport()));
    }

    /** Englische Variante "<key>_en", wenn vorhanden; sonst der deutsche Text. */
    private static String text(JsonObject o, String key, String fallback, boolean german) {
        if (!german) {
            String en = str(o, key + "_en", "");
            if (!en.isBlank()) return en;
        }
        return str(o, key, fallback);
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }
}
