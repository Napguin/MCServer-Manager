package de.mcnetwork.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jump & Runs: Start-, Checkpoint- und Ziel-Druckplatten, Timer in der Actionbar,
 * Bestzeiten im Dashboard und eine Bestenliste als Hologramm. Läuft auf dem Hauptthread.
 */
final class Parkour implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    static final String ITEM_CHECKPOINT = "pk_checkpoint";
    static final String ITEM_RESTART = "pk_restart";
    static final String ITEM_LEAVE = "pk_leave";
    private static final Sound START = Sound.sound(Key.key("block.note_block.pling"), Sound.Source.PLAYER, 1f, 1.2f);
    private static final Sound CHECKPOINT = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1f, 1.1f);
    private static final Sound FINISH = Sound.sound(Key.key("ui.toast.challenge_complete"), Sound.Source.PLAYER, 0.8f, 1f);
    private static final Sound FAIL = Sound.sound(Key.key("entity.enderman.teleport"), Sound.Source.PLAYER, 0.6f, 1.2f);

    private final LobbySelector plugin;
    private final Map<String, Course> courses = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    private final List<DualText> labels = new ArrayList<>();

    record Course(String id, String name, String nameEn, Location start, List<Location> checkpoints,
                  Location finish, DualText leaderboard, double lowestY) {
        String name(boolean german) {
            return german || nameEn.isBlank() ? name : nameEn;
        }
    }

    private static final class Run {
        final Course course;
        long startNanos = System.nanoTime();
        int checkpoint; // 0 = noch keiner erreicht
        Location respawn;

        Run(Course course, Location respawn) {
            this.course = course;
            this.respawn = respawn;
        }

        long millis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    Parkour(LobbySelector plugin) {
        this.plugin = plugin;
    }

    static String format(long ms) {
        return String.format("%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    // ---------- Aufbau (von LobbyObjects bei neuer Version aufgerufen) ----------

    void rebuild(JsonObject extras) {
        labels.forEach(DualText::remove);
        labels.clear();
        courses.clear();
        JsonObject list = Json.obj(extras, "parkours");
        if (list != null) {
            for (Map.Entry<String, JsonElement> e : list.entrySet()) {
                Course course = parse(e.getKey(), e.getValue().getAsJsonObject());
                if (course != null) courses.put(course.id(), course);
            }
        }
        // Laufende Runs auf die neue Strecke umstellen oder beenden
        for (Map.Entry<UUID, Run> e : new HashMap<>(runs).entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null && !courses.containsKey(e.getValue().course.id())) stop(player, false);
        }
    }

    private Course parse(String id, JsonObject o) {
        Location start = Json.loc(Json.obj(o, "start"));
        if (start == null) return null;
        List<Location> checkpoints = new ArrayList<>();
        JsonElement cps = o.get("checkpoints");
        if (cps != null && cps.isJsonArray()) {
            for (JsonElement cp : cps.getAsJsonArray()) {
                Location l = Json.loc(cp.getAsJsonObject());
                if (l != null) checkpoints.add(l);
            }
        }
        Location finish = Json.loc(Json.obj(o, "finish"));
        Location board = Json.loc(Json.obj(o, "leaderboard"));
        String name = Json.str(o, "name", id);
        String nameEn = Json.str(o, "name_en", "");

        double lowest = start.getY();
        for (Location cp : checkpoints) lowest = Math.min(lowest, cp.getY());
        if (finish != null) lowest = Math.min(lowest, finish.getY());

        label(start, "<green><bold>Start</bold>\n<white>" + name, "<green><bold>Start</bold>\n<white>" + (nameEn.isBlank() ? name : nameEn));
        for (int i = 0; i < checkpoints.size(); i++) {
            label(checkpoints.get(i), "<aqua>Checkpoint <white>" + (i + 1), "<aqua>Checkpoint <white>" + (i + 1));
        }
        if (finish != null) label(finish, "<gold><bold>Ziel", "<gold><bold>Finish");
        DualText leaderboard = null;
        if (board != null) {
            leaderboard = new DualText(board, 1f, true);
            labels.add(leaderboard);
        }
        return new Course(id, name, nameEn, start, checkpoints, finish, leaderboard, lowest);
    }

    private void label(Location block, String de, String en) {
        DualText text = new DualText(center(block).add(0, 1.4, 0), 0.8f, false);
        text.text(MM.deserialize(de), MM.deserialize(en));
        labels.add(text);
    }

    private static Location center(Location block) {
        return new Location(block.getWorld(), block.getBlockX() + 0.5, block.getBlockY(), block.getBlockZ() + 0.5);
    }

    /** Alle 2 Sekunden: Bestenlisten aktualisieren und Texte erzeugen. */
    void tick() {
        JsonObject boards = Json.obj(plugin.dashboard().extras(), "leaderboards");
        for (Course course : courses.values()) {
            if (course.leaderboard() == null) continue;
            JsonElement times = boards == null ? null : boards.get(course.id());
            course.leaderboard().text(MM.deserialize(leaderboard(course, times, true)), MM.deserialize(leaderboard(course, times, false)));
        }
        labels.forEach(DualText::ensureSpawned);
    }

    private static String leaderboard(Course course, JsonElement times, boolean german) {
        StringBuilder sb = new StringBuilder("<gold><bold>").append(MM.escapeTags(course.name(german))).append("</bold>\n")
                .append(german ? "<gray>Bestenliste\n" : "<gray>Leaderboard\n");
        JsonArray list = times != null && times.isJsonArray() ? times.getAsJsonArray() : new JsonArray();
        if (list.isEmpty()) sb.append(german ? "\n<gray>Noch keine Zeiten – sei der Erste!" : "\n<gray>No times yet – be the first!");
        String[] colors = {"<gold>", "<white>", "<#cd7f32>"};
        for (int i = 0; i < list.size(); i++) {
            JsonObject t = list.get(i).getAsJsonObject();
            sb.append('\n').append(i < 3 ? colors[i] : "<gray>").append(i + 1).append(". <white>")
                    .append(MM.escapeTags(Json.str(t, "name", "?"))).append(" <dark_gray>- <aqua>")
                    .append(format((long) Json.num(t, "ms", 0)));
        }
        return sb.toString();
    }

    void apply(Player player) {
        for (DualText label : labels) label.apply(player, plugin);
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(runs.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) stop(player, false);
        }
        labels.forEach(DualText::remove);
        labels.clear();
    }

    // ---------- Laufen ----------

    boolean isRunning(Player player) {
        return runs.containsKey(player.getUniqueId());
    }

    /** Liegt hier eine Druckplatte eines Jump & Runs (dann ist sie kein Launchpad)? */
    boolean isPoint(Block block) {
        Location l = block.getLocation();
        for (Course c : courses.values()) {
            if (Json.sameBlock(c.start(), l) || Json.sameBlock(c.finish(), l)) return true;
            for (Location cp : c.checkpoints()) if (Json.sameBlock(cp, l)) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (plugin.isBuilding(player) || player.getGameMode() == GameMode.SPECTATOR) return;
        Location l = event.getClickedBlock().getLocation();
        Run run = runs.get(player.getUniqueId());

        for (Course course : courses.values()) {
            if (Json.sameBlock(course.start(), l)) {
                // Wer auf der Startplatte steht, löst sie ständig aus: nur nach einer kurzen Pause neu starten
                if (run != null && run.course == course && run.checkpoint == 0 && run.millis() < 2000) return;
                start(player, course);
                return;
            }
        }
        if (run == null) return;
        Course course = run.course;
        for (int i = 0; i < course.checkpoints().size(); i++) {
            if (!Json.sameBlock(course.checkpoints().get(i), l)) continue;
            if (i == run.checkpoint) {
                run.checkpoint = i + 1;
                run.respawn = center(course.checkpoints().get(i)).add(0, 0.1, 0);
                run.respawn.setYaw(player.getLocation().getYaw());
                player.playSound(CHECKPOINT);
                player.sendActionBar(MM.deserialize("<aqua>Checkpoint " + run.checkpoint
                        + "<gray>/" + course.checkpoints().size() + " <dark_gray>· <white>" + format(run.millis())));
            } else if (i + 1 > run.checkpoint) {
                player.sendActionBar(MM.deserialize(Lang.t(player, "<red>Du hast einen Checkpoint ausgelassen!", "<red>You skipped a checkpoint!")));
            }
            return;
        }
        if (Json.sameBlock(course.finish(), l)) {
            if (run.checkpoint < course.checkpoints().size()) {
                player.sendActionBar(MM.deserialize(Lang.t(player, "<red>Du hast nicht alle Checkpoints erreicht!", "<red>You haven't reached all checkpoints!")));
                return;
            }
            finish(player, run);
        }
    }

    private void start(Player player, Course course) {
        boolean wasRunning = runs.containsKey(player.getUniqueId());
        Location respawn = center(course.start()).add(0, 0.1, 0);
        respawn.setYaw(player.getLocation().getYaw());
        runs.put(player.getUniqueId(), new Run(course, respawn));
        player.setFlying(false);
        player.setAllowFlight(false);
        if (!wasRunning) giveItems(player);
        player.playSound(START);
        boolean de = Lang.german(player.locale());
        player.sendActionBar(MM.deserialize((de ? "<green>Los geht's! " : "<green>Go! ") + "<white>" + MM.escapeTags(course.name(de))));
    }

    private void giveItems(Player player) {
        player.getInventory().clear();
        boolean de = Lang.german(player.locale());
        player.getInventory().setItem(3, plugin.item(ITEM_CHECKPOINT, Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
                de ? "<aqua>Zum Checkpoint <gray>(Rechtsklick)" : "<aqua>Back to checkpoint <gray>(Right-click)"));
        player.getInventory().setItem(4, plugin.item(ITEM_RESTART, Material.SLIME_BALL,
                de ? "<yellow>Neu starten <gray>(Rechtsklick)" : "<yellow>Restart <gray>(Right-click)"));
        player.getInventory().setItem(5, plugin.item(ITEM_LEAVE, Material.BARRIER,
                de ? "<red>Abbrechen <gray>(Rechtsklick)" : "<red>Quit <gray>(Right-click)"));
        plugin.cosmetics().applyHat(player);
    }

    private void finish(Player player, Run run) {
        long ms = run.millis();
        Course course = run.course;
        runs.remove(player.getUniqueId());
        plugin.restoreLobby(player);
        player.playSound(FINISH);
        boolean de = Lang.german(player.locale());
        player.showTitle(Title.title(MM.deserialize(de ? "<gold><bold>Geschafft!" : "<gold><bold>Finished!"),
                MM.deserialize("<white>" + format(ms)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject result = plugin.dashboard().submitTime(course.id(), uuid, name, ms);
                Bukkit.getScheduler().runTask(plugin, () -> announce(uuid, name, course, ms, result));
            } catch (Exception e) {
                plugin.getLogger().warning("Zeit konnte nicht gespeichert werden: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.sendMessage(MM.deserialize(Lang.t(p, "<red>Deine Zeit konnte nicht gespeichert werden.", "<red>Your time could not be saved.")));
                });
            }
        });
    }

    private void announce(UUID uuid, String name, Course course, long ms, JsonObject result) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            boolean improved = Json.bool(result, "improved", false);
            long best = (long) Json.num(result, "best", ms);
            int rank = (int) Json.num(result, "rank", 0);
            player.sendMessage(MM.deserialize(improved
                    ? Lang.t(player, "<green>Neue Bestzeit: <white>" + format(ms) + " <gray>(Platz <yellow>#" + rank + "<gray>)",
                            "<green>New personal best: <white>" + format(ms) + " <gray>(rank <yellow>#" + rank + "<gray>)")
                    : Lang.t(player, "<gray>Deine Zeit: <white>" + format(ms) + " <dark_gray>· <gray>Bestzeit: <white>" + format(best),
                            "<gray>Your time: <white>" + format(ms) + " <dark_gray>· <gray>Best: <white>" + format(best))));
        }
        if (Json.bool(result, "record", false)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean de = Lang.german(p.locale());
                p.sendMessage(MM.deserialize("<gold>★ <yellow>" + MM.escapeTags(name)
                        + (de ? " <gold>hat den Rekord in <yellow>" : " <gold>set a new record in <yellow>")
                        + MM.escapeTags(course.name(de)) + (de ? " <gold>aufgestellt: <white>" : "<gold>: <white>") + format(ms)));
            }
        }
    }

    /** Lauf beenden; restore = Lobby-Items zurückgeben. */
    void stop(Player player, boolean restore) {
        if (runs.remove(player.getUniqueId()) != null && restore) plugin.restoreLobby(player);
    }

    void backToCheckpoint(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) return;
        player.setFallDistance(0);
        player.setVelocity(new org.bukkit.util.Vector());
        player.teleport(run.respawn);
        player.playSound(FAIL);
    }

    void restart(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) return;
        Course course = run.course;
        teleport(player, course);
        start(player, course);
    }

    private void teleport(Player player, Course course) {
        Location target = center(course.start()).add(0, 0.1, 0);
        target.setYaw(player.getLocation().getYaw());
        player.setFallDistance(0);
        player.teleport(target);
    }

    void teleportToStart(Player player, String id) {
        Course course = courses.get(id);
        if (course == null) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Dieses Jump & Run gibt es nicht.", "<red>This parkour doesn't exist.")));
            return;
        }
        stop(player, true);
        teleport(player, course);
    }

    /** Alle 2 Ticks: Timer anzeigen, Herunterfallen erkennen. */
    void timerTick() {
        for (Map.Entry<UUID, Run> e : new ArrayList<>(runs.entrySet())) {
            Player player = Bukkit.getPlayer(e.getKey());
            Run run = e.getValue();
            if (player == null) {
                runs.remove(e.getKey());
                continue;
            }
            if (player.isFlying() || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || !player.getWorld().equals(run.course.start().getWorld())) {
                stop(player, true);
                player.sendMessage(MM.deserialize(Lang.t(player, "<red>Jump & Run abgebrochen.", "<red>Parkour cancelled.")));
                continue;
            }
            if (player.getLocation().getY() < run.course.lowestY() - 3) {
                backToCheckpoint(player);
                continue;
            }
            player.sendActionBar(MM.deserialize("<yellow>⏱ <white>" + format(run.millis()) + " <dark_gray>· <aqua>Checkpoint "
                    + run.checkpoint + "<gray>/" + run.course.checkpoints().size()));
        }
    }

    void forget(Player player) {
        runs.remove(player.getUniqueId());
    }

    // ---------- Einrichten (Befehl /parkour) ----------

    private JsonObject data(String id) {
        JsonObject list = Json.obj(plugin.dashboard().extras(), "parkours");
        JsonObject o = Json.obj(list, id);
        return o == null ? null : o.deepCopy();
    }

    /** Druckplatte unter dem Spieler finden oder hinlegen; null, wenn dort kein Platz ist. */
    private static Block plate(Player player, Material material) {
        Block block = player.getLocation().getBlock();
        if (Tag.PRESSURE_PLATES.isTagged(block.getType())) return block;
        if (!block.getType().isAir() || !block.getRelative(0, -1, 0).getType().isSolid()) return null;
        block.setType(material);
        return block;
    }

    boolean command(Player player, String[] args) {
        boolean de = Lang.german(player.locale());
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String id = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "leave", "verlassen" -> {
                if (!isRunning(player)) msg(player, "<gray>Du bist in keinem Jump & Run.", "<gray>You're not in a parkour.");
                else {
                    stop(player, true);
                    msg(player, "<yellow>Jump & Run verlassen.", "<yellow>Left the parkour.");
                }
                return true;
            }
            case "list" -> {
                if (courses.isEmpty()) msg(player, "<gray>Noch keine Jump & Runs.", "<gray>No parkours yet.");
                for (Course c : courses.values()) {
                    player.sendMessage(MM.deserialize("<gold>" + c.id() + " <gray>– <white>" + MM.escapeTags(c.name(de))
                            + " <gray>(" + c.checkpoints().size() + " Checkpoints" + (c.finish() == null ? (de ? ", <red>kein Ziel" : ", <red>no finish") : "") + "<gray>)"));
                }
                return true;
            }
            case "tp" -> {
                teleportToStart(player, id);
                return true;
            }
            default -> {
            }
        }
        if (!player.hasPermission("lobby.admin")) {
            player.sendMessage(MM.deserialize("<gray>/parkour leave · /parkour list · /parkour tp <name>"));
            return true;
        }
        if (sub.equals("help") || id.isEmpty()) {
            player.sendMessage(MM.deserialize(de
                    ? "<gold>Jump & Run einrichten:\n<yellow>/parkour create <name> [Anzeigename] <gray>– Start hier\n<yellow>/parkour checkpoint <name> <gray>– Checkpoint hier\n<yellow>/parkour undo <name> <gray>– letzten Checkpoint löschen\n<yellow>/parkour finish <name> <gray>– Ziel hier\n<yellow>/parkour leaderboard <name> <gray>– Bestenliste hier\n<yellow>/parkour delete <name>\n<gray>Druckplatten werden automatisch gelegt. Bestzeiten im Dashboard zurücksetzen."
                    : "<gold>Set up a parkour:\n<yellow>/parkour create <name> [display name] <gray>– start here\n<yellow>/parkour checkpoint <name> <gray>– checkpoint here\n<yellow>/parkour undo <name> <gray>– remove last checkpoint\n<yellow>/parkour finish <name> <gray>– finish here\n<yellow>/parkour leaderboard <name> <gray>– leaderboard here\n<yellow>/parkour delete <name>\n<gray>Pressure plates are placed automatically. Reset times in the dashboard."));
            return true;
        }
        JsonObject data = data(id);
        if (!sub.equals("create") && data == null) {
            msg(player, "<red>Jump & Run <white>" + id + "<red> gibt es nicht. <gray>/parkour list", "<red>Parkour <white>" + id + "<red> doesn't exist. <gray>/parkour list");
            return true;
        }
        switch (sub) {
            case "create" -> {
                if (data != null) {
                    msg(player, "<red>Den Namen gibt es schon.", "<red>That name already exists.");
                    return true;
                }
                Block block = plate(player, Material.STONE_PRESSURE_PLATE);
                if (block == null) return noPlate(player);
                data = new JsonObject();
                String name = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : id;
                data.addProperty("name", name);
                data.add("start", Json.block(block.getLocation()));
                data.add("checkpoints", new JsonArray());
                plugin.saveExtra(player, "parkours", id, data, "Start gesetzt. Jetzt Checkpoints und Ziel setzen.", "Start set. Now add checkpoints and a finish.");
            }
            case "checkpoint", "cp" -> {
                Block block = plate(player, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
                if (block == null) return noPlate(player);
                data.getAsJsonArray("checkpoints").add(Json.block(block.getLocation()));
                int n = data.getAsJsonArray("checkpoints").size();
                plugin.saveExtra(player, "parkours", id, data, "Checkpoint " + n + " gesetzt.", "Checkpoint " + n + " set.");
            }
            case "undo" -> {
                JsonArray cps = data.getAsJsonArray("checkpoints");
                if (cps == null || cps.isEmpty()) {
                    msg(player, "<gray>Keine Checkpoints.", "<gray>No checkpoints.");
                    return true;
                }
                cps.remove(cps.size() - 1);
                plugin.saveExtra(player, "parkours", id, data, "Letzten Checkpoint entfernt.", "Removed the last checkpoint.");
            }
            case "finish", "ziel" -> {
                Block block = plate(player, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
                if (block == null) return noPlate(player);
                data.add("finish", Json.block(block.getLocation()));
                plugin.saveExtra(player, "parkours", id, data, "Ziel gesetzt – das Jump & Run ist fertig!", "Finish set – the parkour is ready!");
            }
            case "leaderboard", "top" -> {
                data.add("leaderboard", Json.loc(player.getLocation().add(0, 2.2, 0)));
                plugin.saveExtra(player, "parkours", id, data, "Bestenliste gesetzt.", "Leaderboard set.");
            }
            case "name" -> {
                if (args.length < 3) return false;
                data.addProperty("name", String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                plugin.saveExtra(player, "parkours", id, data, "Name geändert.", "Name changed.");
            }
            case "delete" -> plugin.saveExtra(player, "parkours", id, null, "Jump & Run gelöscht (Druckplatten bitte im Baumodus entfernen).", "Parkour deleted (remove the pressure plates in build mode).");
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean noPlate(Player player) {
        msg(player, "<red>Stell dich auf eine Druckplatte oder auf einen freien Platz über festem Boden.",
                "<red>Stand on a pressure plate or on a free spot above solid ground.");
        return true;
    }

    private static void msg(CommandSender sender, String de, String en) {
        sender.sendMessage(MM.deserialize(Lang.t(sender, de, en)));
    }

    List<String> ids() {
        return new ArrayList<>(courses.keySet());
    }
}
