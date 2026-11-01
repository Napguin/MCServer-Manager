package de.mcnetwork.lobby;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Einrichten von Hologrammen (/holo), NPCs (/npc) und Server-Schildern (/serversign) im Spiel. */
final class ExtrasCommands {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LobbySelector plugin;

    ExtrasCommands(LobbySelector plugin) {
        this.plugin = plugin;
    }

    private JsonObject entry(String kind, String id) {
        JsonObject o = Json.obj(Json.obj(plugin.dashboard().extras(), kind), id);
        return o == null ? null : o.deepCopy();
    }

    List<String> ids(String kind) {
        JsonObject list = Json.obj(plugin.dashboard().extras(), kind);
        return list == null ? List.of() : new ArrayList<>(list.keySet());
    }

    private static String rest(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length)).replace("|", "\n");
    }

    private static void msg(CommandSender sender, String de, String en) {
        sender.sendMessage(MM.deserialize(Lang.t(sender, de, en)));
    }

    private static boolean notFound(Player player, String what, String id) {
        msg(player, "<red>" + what + " <white>" + id + "<red> gibt es nicht.", "<red>" + what + " <white>" + id + "<red> doesn't exist.");
        return true;
    }

    // ---------- /holo ----------

    boolean holo(Player player, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            List<String> ids = ids("holograms");
            player.sendMessage(MM.deserialize(ids.isEmpty() ? Lang.t(player, "<gray>Noch keine Hologramme.", "<gray>No holograms yet.")
                    : "<gold>Hologramme: <white>" + String.join(", ", ids)));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(MM.deserialize(Lang.t(player,
                    "<gold>Hologramme:\n<yellow>/holo create <name> [Text] <gray>– hier erstellen\n<yellow>/holo text <name> <Text> <gray>– deutscher Text\n<yellow>/holo text_en <name> <Text> <gray>– englischer Text\n<yellow>/holo addline <name> <Text>\n<yellow>/holo movehere <name> · /holo scale <name> <0.2–5>\n<yellow>/holo background <name> on|off · /holo delete <name> · /holo list\n<gray>Mit <white>|<gray> trennst du Zeilen. Platzhalter: <white><online>, <players:survival>, <status:survival><gray> …\n<gray>Bequemer bearbeiten: im Dashboard unter „Lobby-Extras“.",
                    "<gold>Holograms:\n<yellow>/holo create <name> [text] <gray>– create here\n<yellow>/holo text <name> <text> <gray>– German text\n<yellow>/holo text_en <name> <text> <gray>– English text\n<yellow>/holo addline <name> <text>\n<yellow>/holo movehere <name> · /holo scale <name> <0.2–5>\n<yellow>/holo background <name> on|off · /holo delete <name> · /holo list\n<gray>Use <white>|<gray> to separate lines. Placeholders: <white><online>, <players:survival>, <status:survival><gray> …\n<gray>Easier to edit in the dashboard under “Lobby extras”.")));
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        JsonObject data = entry("holograms", id);
        if (sub.equals("create")) {
            if (data != null) {
                msg(player, "<red>Den Namen gibt es schon.", "<red>That name already exists.");
                return true;
            }
            data = new JsonObject();
            data.add("location", Json.loc(player.getEyeLocation().add(0, 0.5, 0)));
            data.addProperty("text", args.length > 2 ? rest(args, 2) : "<gold><bold>" + id);
            data.addProperty("text_en", "");
            data.addProperty("scale", 1);
            data.addProperty("background", true);
            plugin.saveExtra(player, "holograms", id, data, "Hologramm erstellt.", "Hologram created.");
            return true;
        }
        if (data == null) return notFound(player, "Hologramm", id);
        switch (sub) {
            case "text", "text_en" -> {
                if (args.length < 3) return false;
                data.addProperty(sub, rest(args, 2));
                plugin.saveExtra(player, "holograms", id, data, "Text geändert.", "Text changed.");
            }
            case "addline" -> {
                if (args.length < 3) return false;
                String text = Json.str(data, "text", "");
                data.addProperty("text", text.isEmpty() ? rest(args, 2) : text + "\n" + rest(args, 2));
                plugin.saveExtra(player, "holograms", id, data, "Zeile hinzugefügt.", "Line added.");
            }
            case "movehere" -> {
                data.add("location", Json.loc(player.getEyeLocation().add(0, 0.5, 0)));
                plugin.saveExtra(player, "holograms", id, data, "Hologramm verschoben.", "Hologram moved.");
            }
            case "scale" -> {
                if (args.length < 3) return false;
                try {
                    data.addProperty("scale", Math.max(0.2, Math.min(5, Double.parseDouble(args[2].replace(',', '.')))));
                } catch (NumberFormatException e) {
                    return false;
                }
                plugin.saveExtra(player, "holograms", id, data, "Größe geändert.", "Size changed.");
            }
            case "background" -> {
                if (args.length < 3) return false;
                data.addProperty("background", args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("an"));
                plugin.saveExtra(player, "holograms", id, data, "Hintergrund geändert.", "Background changed.");
            }
            case "delete" -> plugin.saveExtra(player, "holograms", id, null, "Hologramm gelöscht.", "Hologram deleted.");
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------- /npc ----------

    static boolean livingType(String name) {
        try {
            EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return type.getEntityClass() != null && LivingEntity.class.isAssignableFrom(type.getEntityClass())
                    && type.isSpawnable() && type != EntityType.PLAYER && type != EntityType.ARMOR_STAND;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static List<String> livingTypes() {
        List<String> list = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (livingType(type.name())) list.add(type.name().toLowerCase(Locale.ROOT));
        }
        return list;
    }

    boolean npc(Player player, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            List<String> ids = ids("npcs");
            player.sendMessage(MM.deserialize(ids.isEmpty() ? Lang.t(player, "<gray>Noch keine NPCs.", "<gray>No NPCs yet.")
                    : "<gold>NPCs: <white>" + String.join(", ", ids)));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(MM.deserialize(Lang.t(player,
                    "<gold>NPCs:\n<yellow>/npc create <name> <Spielername|Mob> <gray>– z. B. <white>Notch<gray> oder <white>villager\n<yellow>/npc name <name> <Text> · /npc name_en <name> <Text>\n<yellow>/npc action <name> server <id> <gray>| <yellow>menu <gray>| <yellow>cosmetics <gray>| <yellow>parkour <id> <gray>| <yellow>command <Befehl> <gray>| <yellow>none\n<yellow>/npc skin <name> <Spielername> · /npc type <name> <Mob|player>\n<yellow>/npc look <name> on|off · /npc movehere <name> · /npc delete <name> · /npc list\n<gray>Mit <white>|<gray> trennst du Zeilen im Namen.",
                    "<gold>NPCs:\n<yellow>/npc create <name> <player name|mob> <gray>– e.g. <white>Notch<gray> or <white>villager\n<yellow>/npc name <name> <text> · /npc name_en <name> <text>\n<yellow>/npc action <name> server <id> <gray>| <yellow>menu <gray>| <yellow>cosmetics <gray>| <yellow>parkour <id> <gray>| <yellow>command <command> <gray>| <yellow>none\n<yellow>/npc skin <name> <player name> · /npc type <name> <mob|player>\n<yellow>/npc look <name> on|off · /npc movehere <name> · /npc delete <name> · /npc list\n<gray>Use <white>|<gray> to separate lines in the name.")));
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        JsonObject data = entry("npcs", id);
        if (sub.equals("create")) {
            if (data != null) {
                msg(player, "<red>Den Namen gibt es schon.", "<red>That name already exists.");
                return true;
            }
            String what = args.length > 2 ? args[2] : player.getName();
            data = new JsonObject();
            Location here = player.getLocation();
            here.setPitch(0);
            data.add("location", Json.loc(here));
            if (livingType(what)) {
                data.addProperty("type", what.toUpperCase(Locale.ROOT));
                data.addProperty("skin", "");
            } else {
                data.addProperty("type", "PLAYER");
                data.addProperty("skin", what);
            }
            data.addProperty("name", "<yellow><bold>" + (livingType(what) ? id : what));
            data.addProperty("name_en", "");
            data.addProperty("action", "menu");
            data.addProperty("target", "");
            data.addProperty("look", true);
            plugin.saveExtra(player, "npcs", id, data, "NPC erstellt. Aktion: Serverauswahl öffnen (ändern mit /npc action).",
                    "NPC created. Action: open server selector (change with /npc action).");
            return true;
        }
        if (data == null) return notFound(player, "NPC", id);
        switch (sub) {
            case "name", "name_en" -> {
                if (args.length < 3) return false;
                data.addProperty(sub, rest(args, 2));
                plugin.saveExtra(player, "npcs", id, data, "Name geändert.", "Name changed.");
            }
            case "action" -> {
                if (args.length < 3) return false;
                String action = args[2].toLowerCase(Locale.ROOT);
                String target = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
                if (!List.of("none", "menu", "cosmetics", "server", "parkour", "command").contains(action)) return false;
                if ((action.equals("server") || action.equals("parkour") || action.equals("command")) && target.isBlank()) {
                    msg(player, "<red>Bitte noch angeben, wohin: z. B. <white>/npc action " + id + " server survival",
                            "<red>Please add a target, e.g. <white>/npc action " + id + " server survival");
                    return true;
                }
                if (action.equals("server")) target = target.toLowerCase(Locale.ROOT);
                data.addProperty("action", action);
                data.addProperty("target", target);
                plugin.saveExtra(player, "npcs", id, data, "Aktion geändert.", "Action changed.");
            }
            case "skin" -> {
                if (args.length < 3 || !args[2].matches("[A-Za-z0-9_]{1,16}")) return false;
                data.addProperty("type", "PLAYER");
                data.addProperty("skin", args[2]);
                plugin.saveExtra(player, "npcs", id, data, "Skin geändert.", "Skin changed.");
            }
            case "type" -> {
                if (args.length < 3) return false;
                String type = args[2].equalsIgnoreCase("player") ? "PLAYER" : args[2].toUpperCase(Locale.ROOT);
                if (!type.equals("PLAYER") && !livingType(type)) {
                    msg(player, "<red>Unbekannter Mob.", "<red>Unknown mob.");
                    return true;
                }
                data.addProperty("type", type);
                plugin.saveExtra(player, "npcs", id, data, "Typ geändert.", "Type changed.");
            }
            case "look" -> {
                if (args.length < 3) return false;
                data.addProperty("look", args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("an"));
                plugin.saveExtra(player, "npcs", id, data, "Gespeichert.", "Saved.");
            }
            case "movehere" -> {
                Location here = player.getLocation();
                here.setPitch(0);
                data.add("location", Json.loc(here));
                plugin.saveExtra(player, "npcs", id, data, "NPC verschoben.", "NPC moved.");
            }
            case "delete" -> plugin.saveExtra(player, "npcs", id, null, "NPC gelöscht.", "NPC deleted.");
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------- /serversign ----------

    boolean serverSign(Player player, String[] args) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Sign)) {
            msg(player, "<red>Schau ein Schild an (höchstens 6 Blöcke entfernt).", "<red>Look at a sign (at most 6 blocks away).");
            return true;
        }
        if (args.length == 0) {
            msg(player, "<yellow>/serversign <server-id> <gray>– Schild wird zum Server-Schild\n<yellow>/serversign remove",
                    "<yellow>/serversign <server-id> <gray>– turns the sign into a server sign\n<yellow>/serversign remove");
            return true;
        }
        String id = LobbyObjects.signId(block.getLocation());
        if (args[0].equalsIgnoreCase("remove")) {
            if (plugin.objects().signAt(block) == null) {
                msg(player, "<gray>Das ist kein Server-Schild.", "<gray>That's not a server sign.");
                return true;
            }
            plugin.saveExtra(player, "signs", id, null, "Server-Schild entfernt.", "Server sign removed.");
            return true;
        }
        String server = args[0].toLowerCase(Locale.ROOT);
        if (plugin.dashboard().servers().stream().noneMatch(s -> s.id().equals(server))) {
            msg(player, "<yellow>Hinweis: Einen Server <white>" + server + "<yellow> gibt es (noch) nicht oder er ist versteckt.",
                    "<yellow>Note: there is no (visible) server <white>" + server + "<yellow> yet.");
        }
        JsonObject data = new JsonObject();
        data.add("location", Json.block(block.getLocation()));
        data.addProperty("server", server);
        plugin.saveExtra(player, "signs", id, data, "Server-Schild für <white>" + server + "<green> eingerichtet.",
                "Server sign for <white>" + server + "<green> set up.");
        return true;
    }

    /** Vorschläge für die Tab-Taste. */
    List<String> complete(String command, String[] args) {
        List<String> options = switch (command) {
            case "holo" -> switch (args.length) {
                case 1 -> List.of("create", "text", "text_en", "addline", "movehere", "scale", "background", "delete", "list");
                case 2 -> ids("holograms");
                case 3 -> args[0].equalsIgnoreCase("background") ? List.of("on", "off") : List.of();
                default -> List.of();
            };
            case "npc" -> switch (args.length) {
                case 1 -> List.of("create", "name", "name_en", "action", "skin", "type", "look", "movehere", "delete", "list");
                case 2 -> ids("npcs");
                case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "action" -> List.of("server", "menu", "cosmetics", "parkour", "command", "none");
                    case "type", "create" -> livingTypes();
                    case "look" -> List.of("on", "off");
                    default -> List.of();
                };
                case 4 -> switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "server" -> plugin.dashboard().servers().stream().map(ServerEntry::id).toList();
                    case "parkour" -> plugin.parkour().ids();
                    default -> List.of();
                };
                default -> List.of();
            };
            case "serversign" -> args.length == 1
                    ? concat(plugin.dashboard().servers().stream().map(ServerEntry::id).toList(), List.of("remove")) : List.of();
            case "parkour" -> switch (args.length) {
                case 1 -> List.of("leave", "list", "tp", "create", "checkpoint", "undo", "finish", "leaderboard", "name", "delete");
                case 2 -> plugin.parkour().ids();
                default -> List.of();
            };
            default -> List.of();
        };
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> list = new ArrayList<>(a);
        list.addAll(b);
        return list;
    }
}
