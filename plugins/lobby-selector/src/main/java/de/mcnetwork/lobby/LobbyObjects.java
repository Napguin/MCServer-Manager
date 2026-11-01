package de.mcnetwork.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hologramme, NPCs und Server-Schilder aus den Lobby-Extras des Dashboards.
 * Ändert sich die Version im Dashboard, wird alles neu aufgebaut. Läuft auf dem Hauptthread.
 */
final class LobbyObjects implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LobbySelector plugin;
    private long version = Long.MIN_VALUE;

    private final Map<String, Holo> holograms = new HashMap<>();
    private final Map<String, Npc> npcs = new HashMap<>();
    private final Map<UUID, Npc> npcByEntity = new HashMap<>();
    private final Map<String, ServerSign> signs = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();

    private record Holo(DualText display, String text, String textEn) {
    }

    private static final class Npc {
        final String id;
        final JsonObject data;
        final Location location;
        DualText label; // entsteht mit dem Mob, damit es zu seiner Größe passt
        LivingEntity entity;

        Npc(String id, JsonObject data, Location location) {
            this.id = id;
            this.data = data;
            this.location = location;
        }

        String action() {
            return Json.str(data, "action", "none");
        }

        String target() {
            return Json.str(data, "target", "");
        }
    }

    private static final class ServerSign {
        final Location location;
        final String server;
        String lastText = "";

        ServerSign(Location location, String server) {
            this.location = location;
            this.server = server;
        }
    }

    LobbyObjects(LobbySelector plugin) {
        this.plugin = plugin;
    }

    static String blockKey(Location l) {
        return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    // ---------- Aufbau ----------

    /** Alle 2 Sekunden: bei neuer Version neu aufbauen, dann Texte, Schilder und Sichtbarkeit aktualisieren. */
    void tick() {
        JsonObject extras = plugin.dashboard().extras();
        long current = (long) Json.num(extras, "version", 0);
        if (current != version) {
            version = current;
            rebuild(extras);
        }
        TagResolver de = plugin.display().resolver(true);
        TagResolver en = plugin.display().resolver(false);
        for (Holo holo : holograms.values()) {
            holo.display().text(MM.deserialize(holo.text(), de),
                    MM.deserialize(holo.textEn().isBlank() ? holo.text() : holo.textEn(), en));
            holo.display().ensureSpawned();
        }
        for (Npc npc : npcs.values()) {
            if (npc.entity == null || !npc.entity.isValid()) spawnEntity(npc);
            if (npc.label == null) continue;
            npc.label.text(npcLabel(npc, true, de), npcLabel(npc, false, en));
            npc.label.ensureSpawned();
        }
        for (ServerSign sign : signs.values()) updateSign(sign);
    }

    /** Sichtbarkeit der Texte für einen Spieler (seine Sprache). */
    void apply(Player player) {
        for (Holo holo : holograms.values()) holo.display().apply(player, plugin);
        for (Npc npc : npcs.values()) if (npc.label != null) npc.label.apply(player, plugin);
    }

    private void rebuild(JsonObject extras) {
        removeAll();
        JsonObject holos = Json.obj(extras, "holograms");
        if (holos != null) {
            for (Map.Entry<String, JsonElement> e : holos.entrySet()) {
                JsonObject h = e.getValue().getAsJsonObject();
                Location loc = Json.loc(Json.obj(h, "location"));
                if (loc == null) continue;
                DualText display = new DualText(loc, (float) Json.num(h, "scale", 1), Json.bool(h, "background", true));
                holograms.put(e.getKey(), new Holo(display, Json.str(h, "text", ""), Json.str(h, "text_en", "")));
            }
        }
        JsonObject npcList = Json.obj(extras, "npcs");
        if (npcList != null) {
            for (Map.Entry<String, JsonElement> e : npcList.entrySet()) {
                JsonObject n = e.getValue().getAsJsonObject();
                Location loc = Json.loc(Json.obj(n, "location"));
                if (loc == null) continue;
                npcs.put(e.getKey(), new Npc(e.getKey(), n, loc));
            }
        }
        JsonObject signList = Json.obj(extras, "signs");
        if (signList != null) {
            for (JsonElement e : signList.asMap().values()) {
                JsonObject s = e.getAsJsonObject();
                Location loc = Json.loc(Json.obj(s, "location"));
                if (loc != null) signs.put(blockKey(loc), new ServerSign(loc, Json.str(s, "server", "")));
            }
        }
        plugin.parkour().rebuild(extras);
    }

    void removeAll() {
        holograms.values().forEach(h -> h.display().remove());
        holograms.clear();
        for (Npc npc : npcs.values()) {
            if (npc.entity != null) npc.entity.remove();
            if (npc.label != null) npc.label.remove();
        }
        npcs.clear();
        npcByEntity.clear();
        signs.clear();
    }

    // ---------- NPCs ----------

    private void spawnEntity(Npc npc) {
        if (!npc.location.isChunkLoaded()) return;
        if (npc.entity != null) npcByEntity.remove(npc.entity.getUniqueId());
        String type = Json.str(npc.data, "type", "PLAYER");
        LivingEntity entity;
        if ("PLAYER".equals(type)) {
            String skin = Json.str(npc.data, "skin", "");
            entity = npc.location.getWorld().spawn(npc.location, Mannequin.class, m -> {
                m.setProfile(skin.isBlank() ? Mannequin.defaultProfile() : ResolvableProfile.resolvableProfile().name(skin).build());
                m.setDescription(null);
                m.setImmovable(true);
                prepare(m);
            });
        } else {
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(type);
            } catch (IllegalArgumentException e) {
                entityType = EntityType.VILLAGER;
            }
            Class<? extends Entity> cls = entityType.getEntityClass();
            if (cls == null || !LivingEntity.class.isAssignableFrom(cls) || !entityType.isSpawnable()) return;
            entity = (LivingEntity) npc.location.getWorld().spawn(npc.location, cls, e -> prepare((LivingEntity) e));
        }
        npc.entity = entity;
        if (npc.label == null) npc.label = new DualText(npc.location.clone().add(0, entity.getHeight() + 0.3, 0), 1f, true);
        npcByEntity.put(entity.getUniqueId(), npc);
    }

    private static void prepare(LivingEntity e) {
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setCollidable(false);
        e.setAI(false);
        e.setGravity(false);
        if (e instanceof Ageable ageable) ageable.setAdult();
    }

    private Component npcLabel(Npc npc, boolean german, TagResolver resolver) {
        String name = Json.str(npc.data, german ? "name" : "name_en", "");
        if (name.isBlank()) name = Json.str(npc.data, "name", "");
        StringBuilder text = new StringBuilder(name);
        if ("server".equals(npc.action())) {
            ServerEntry server = server(npc.target());
            if (!text.isEmpty()) text.append('\n');
            text.append(LobbyDisplay.statusText(server, german));
        }
        return MM.deserialize(text.toString(), resolver);
    }

    /** Alle 5 Ticks: NPCs schauen zum nächsten Spieler in der Nähe. */
    void lookTick() {
        for (Npc npc : npcs.values()) {
            if (npc.entity == null || !npc.entity.isValid() || !Json.bool(npc.data, "look", true)) continue;
            Location eye = npc.entity.getEyeLocation();
            Player nearest = null;
            double best = 100; // 10 Blöcke
            for (Player p : npc.entity.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(eye);
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest == null) {
                npc.entity.setRotation(npc.location.getYaw(), npc.location.getPitch());
                continue;
            }
            Location dir = eye.clone().setDirection(nearest.getEyeLocation().toVector().subtract(eye.toVector()));
            npc.entity.setRotation(dir.getYaw(), dir.getPitch());
        }
    }

    private boolean cooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.put(player.getUniqueId(), now);
        return last != null && now - last < 500;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractNpc(PlayerInteractEntityEvent event) {
        Npc npc = npcByEntity.get(event.getRightClicked().getUniqueId());
        if (npc == null) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) click(event.getPlayer(), npc);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitNpc(EntityDamageByEntityEvent event) {
        Npc npc = npcByEntity.get(event.getEntity().getUniqueId());
        if (npc == null) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player player) click(player, npc);
    }

    private void click(Player player, Npc npc) {
        if (cooldown(player)) return;
        if (plugin.isBuilding(player) && player.isSneaking()) {
            player.sendMessage(MM.deserialize("<gray>NPC <white>" + npc.id + "<gray> · " + npc.action()
                    + (npc.target().isBlank() ? "" : " <white>" + MM.escapeTags(npc.target()))));
            return;
        }
        String target = npc.target();
        switch (npc.action()) {
            case "menu" -> plugin.openMenu(player);
            case "cosmetics" -> plugin.cosmetics().openMain(player);
            case "server" -> plugin.tryConnect(player, target);
            case "parkour" -> plugin.parkour().teleportToStart(player, target);
            case "command" -> {
                if (!target.isBlank()) player.performCommand(target.startsWith("/") ? target.substring(1) : target);
            }
            default -> {
            }
        }
    }

    // ---------- Server-Schilder ----------

    private ServerEntry server(String id) {
        return plugin.dashboard().servers().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    private List<Component> signLines(ServerSign sign, boolean german) {
        ServerEntry server = server(sign.server);
        String name = server == null ? sign.server : server.name();
        List<Component> lines = new ArrayList<>(4);
        lines.add(MM.deserialize("<dark_blue><bold>" + MM.escapeTags(name)));
        if (server == null) {
            lines.add(MM.deserialize(german ? "<dark_gray>unbekannt" : "<dark_gray>unknown"));
            lines.add(Component.empty());
            lines.add(Component.empty());
        } else if (server.online()) {
            lines.add(MM.deserialize("<dark_green>● Online"));
            lines.add(MM.deserialize("<black>" + server.players() + (german ? " Spieler" : " players")));
            lines.add(MM.deserialize(german ? "<dark_gray>» Betreten «" : "<dark_gray>» Join «"));
        } else {
            lines.add(MM.deserialize("starting".equals(server.status())
                    ? (german ? "<gold>● Startet ..." : "<gold>● Starting ...") : "<dark_red>● Offline"));
            lines.add(Component.empty());
            lines.add(Component.empty());
        }
        return lines;
    }

    private void updateSign(ServerSign sign) {
        if (!sign.location.isChunkLoaded()) return;
        Block block = sign.location.getBlock();
        if (!(block.getState() instanceof Sign state)) return;
        List<Component> de = signLines(sign, true);
        String key = de.toString();
        if (!key.equals(sign.lastText)) {
            sign.lastText = key;
            for (Side side : Side.values()) {
                for (int i = 0; i < 4; i++) state.getSide(side).line(i, de.get(i));
            }
            state.setWaxed(true);
            state.update(true, false);
        }
        // Spieler mit englischem Minecraft bekommen den Text auf Englisch geschickt
        Sign english = null;
        for (Player player : sign.location.getWorld().getPlayers()) {
            if (Lang.german(player.locale()) || player.getLocation().distanceSquared(sign.location) > 48 * 48) continue;
            if (english == null) {
                // Kopie des Schilds mit englischem Text – wird nur an den Spieler geschickt, nicht gespeichert
                english = (Sign) block.getState();
                List<Component> en = signLines(sign, false);
                for (Side side : Side.values()) {
                    for (int i = 0; i < 4; i++) english.getSide(side).line(i, en.get(i));
                }
            }
            player.sendBlockUpdate(sign.location, english);
        }
    }

    String signAt(Block block) {
        ServerSign sign = signs.get(blockKey(block.getLocation()));
        return sign == null ? null : sign.server;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        ServerSign sign = signs.get(blockKey(event.getClickedBlock().getLocation()));
        if (sign == null) return;
        Player player = event.getPlayer();
        if (plugin.isBuilding(player) && player.isSneaking()) return; // im Baumodus bearbeitbar
        event.setCancelled(true);
        if (!cooldown(player)) plugin.tryConnect(player, sign.server);
    }

    /** Im Baumodus abgebaute Schilder auch im Dashboard entfernen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        String key = blockKey(event.getBlock().getLocation());
        if (!signs.containsKey(key)) return;
        signs.remove(key);
        plugin.saveExtra(event.getPlayer(), "signs", signId(event.getBlock().getLocation()), null,
                "Server-Schild entfernt.", "Server sign removed.");
    }

    static String signId(Location l) {
        return (l.getBlockX() + "_" + l.getBlockY() + "_" + l.getBlockZ()).replace('-', 'm');
    }

    void forget(Player player) {
        clickCooldown.remove(player.getUniqueId());
    }
}
