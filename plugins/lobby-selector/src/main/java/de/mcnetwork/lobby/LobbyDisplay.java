package de.mcnetwork.lobby;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scoreboard, Bossbar und Hologramm – jeweils in der Sprache des Spielers.
 * Es gibt je ein deutsches und ein englisches Hologramm; jeder Spieler sieht nur seins.
 * Läuft auf dem Hauptthread.
 */
final class LobbyDisplay {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAX_LINES = 15;

    private final LobbySelector plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final BossBar bossBarDe = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private final BossBar bossBarEn = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private TextDisplay hologramDe;
    private TextDisplay hologramEn;

    LobbyDisplay(LobbySelector plugin) {
        this.plugin = plugin;
    }

    void add(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("lobby", Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        update(player);
    }

    void remove(Player player) {
        boards.remove(player.getUniqueId());
        player.hideBossBar(bossBarDe);
        player.hideBossBar(bossBarEn);
    }

    /** Hologramm an neuer Stelle erzeugen (nach /sethologram). */
    void resetHologram() {
        if (hologramDe != null) hologramDe.remove();
        if (hologramEn != null) hologramEn.remove();
        hologramDe = null;
        hologramEn = null;
    }

    void tick() {
        bossBarDe.name(MM.deserialize(plugin.dashboard().settings(true).bossBar(), resolver(true)));
        bossBarEn.name(MM.deserialize(plugin.dashboard().settings(false).bossBar(), resolver(false)));
        hologramDe = updateHologram(hologramDe, true);
        hologramEn = updateHologram(hologramEn, false);
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
        plugin.hub().world().setTime(6000); // immer Mittag
    }

    /** Platzhalter für alle Lobby-Texte; <players:id> und <status:id> zeigen einzelne Server. */
    TagResolver resolver(boolean german) {
        LobbySettings settings = plugin.dashboard().settings(german);
        int lobbyOnline = Bukkit.getOnlinePlayers().size();
        long serversOnline = plugin.dashboard().servers().stream().filter(ServerEntry::online).count();
        return TagResolver.resolver(
                Placeholder.parsed("network", settings.networkName()),
                Placeholder.unparsed("online", String.valueOf(Math.max(plugin.dashboard().totalPlayers(), lobbyOnline))),
                Placeholder.unparsed("lobby_online", String.valueOf(lobbyOnline)),
                Placeholder.unparsed("servers", String.valueOf(serversOnline)),
                TagResolver.resolver("players", (args, ctx) -> {
                    ServerEntry server = server(args.popOr("<players:server-id>").value());
                    return Tag.preProcessParsed(server == null ? "?" : String.valueOf(server.players()));
                }),
                TagResolver.resolver("status", (args, ctx) -> {
                    ServerEntry server = server(args.popOr("<status:server-id>").value());
                    return Tag.preProcessParsed(statusText(server, german));
                }));
    }

    private ServerEntry server(String id) {
        return plugin.dashboard().servers().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** Kurzer Status eines Servers als MiniMessage, z. B. für NPCs und Hologramme. */
    static String statusText(ServerEntry server, boolean german) {
        if (server == null) return german ? "<gray>unbekannt" : "<gray>unknown";
        if (server.online()) return "<green>● " + server.players() + (german ? " Spieler" : " players");
        if ("starting".equals(server.status())) return german ? "<gold>● startet ..." : "<gold>● starting ...";
        return "<red>● Offline";
    }

    private void update(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;
        boolean german = Lang.german(player.locale());
        LobbySettings settings = plugin.dashboard().settings(german);
        TagResolver resolver = TagResolver.resolver(resolver(german),
                Placeholder.unparsed("player", player.getName()),
                Placeholder.component("rank", plugin.rank(player)));

        Objective objective = board.getObjective("lobby");
        if (objective != null) {
            objective.displayName(MM.deserialize(settings.scoreboardTitle(), resolver));
            List<String> lines = settings.scoreboardLines();
            for (int i = 0; i < MAX_LINES; i++) {
                String entry = "line" + i;
                if (i < lines.size()) {
                    Score score = objective.getScore(entry);
                    score.setScore(lines.size() - i);
                    score.customName(MM.deserialize(lines.get(i), resolver));
                } else {
                    board.resetScores(entry);
                }
            }
        }

        // Kopf-/Fußzeile der Tab-Liste setzt der Proxy für das ganze Netzwerk
        BossBar mine = german ? bossBarDe : bossBarEn;
        player.hideBossBar(german ? bossBarEn : bossBarDe);
        if (settings.bossBar().isBlank()) player.hideBossBar(mine);
        else player.showBossBar(mine);

        // Nur das Hologramm in der eigenen Sprache zeigen
        TextDisplay show = german ? hologramDe : hologramEn;
        TextDisplay hide = german ? hologramEn : hologramDe;
        if (show != null) player.showEntity(plugin, show);
        if (hide != null) player.hideEntity(plugin, hide);
    }

    private TextDisplay updateHologram(TextDisplay current, boolean german) {
        LobbySettings settings = plugin.dashboard().settings(german);
        if (settings.hologram().isBlank()) {
            if (current != null) current.remove();
            return null;
        }
        Location location = plugin.hub().hologram();
        TextDisplay display = current;
        if (display == null || !display.isValid()) {
            if (!location.isChunkLoaded()) return null;
            // Nicht gespeichert: wird bei jedem Start neu erzeugt, so gibt es nie doppelte Hologramme
            display = location.getWorld().spawn(location, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setShadowed(true);
            });
        }
        display.text(MM.deserialize(settings.hologram(), resolver(german)));
        return display;
    }

    void shutdown() {
        resetHologram();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bossBarDe);
            player.hideBossBar(bossBarEn);
        }
    }
}
