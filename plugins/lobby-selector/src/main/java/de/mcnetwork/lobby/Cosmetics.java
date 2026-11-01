package de.mcnetwork.lobby;

import com.destroystokyo.paper.entity.Pathfinder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Cosmetics in der Lobby: Partikel-Spuren, Hüte, Gadgets und Haustiere.
 * Was ein Spieler trägt, steht in cosmetics.yml. Freischalten: Premium-Cosmetics brauchen
 * lobby.cosmetics.premium oder lobby.cosmetic.<kategorie>.<name>. Läuft auf dem Hauptthread.
 */
final class Cosmetics implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    static final String ITEM_MENU = "cosmetics";
    static final String ITEM_GADGET = "gadget";
    static final int MENU_SLOT = 2;
    static final int GADGET_SLOT = 6;
    private static final Random RANDOM = new Random();
    private static final Material[] PAINT = {
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE,
            Material.PINK_CONCRETE, Material.CYAN_CONCRETE};

    private final LobbySelector plugin;
    private final File file;
    private final YamlConfiguration saved;
    private final Map<UUID, EnumMap<Cosmetic.Category, Cosmetic>> selected = new HashMap<>();
    private final Map<UUID, Mob> pets = new HashMap<>();
    private final Map<UUID, Long> gadgetCooldown = new HashMap<>();
    private final Map<UUID, Integer> rocketTicks = new HashMap<>();
    private final Set<UUID> gadgetProjectiles = new HashSet<>();
    private final Map<Location, BlockData> painted = new HashMap<>();
    private final Map<UUID, Location> lastPosition = new HashMap<>();
    private int tick;

    Cosmetics(LobbySelector plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cosmetics.yml");
        this.saved = YamlConfiguration.loadConfiguration(file);
    }

    // ---------- Einstellungen aus dem Dashboard ----------

    private JsonObject config() {
        return Json.obj(plugin.dashboard().extras(), "cosmetics");
    }

    boolean enabled() {
        return Json.bool(config(), "enabled", true);
    }

    private boolean inList(String list, Cosmetic c) {
        JsonObject cfg = config();
        JsonElement e = cfg == null ? null : cfg.get(list);
        if (e == null || !e.isJsonArray()) return false;
        for (JsonElement item : e.getAsJsonArray()) if (c.key().equals(item.getAsString())) return true;
        return false;
    }

    boolean available(Cosmetic c) {
        return enabled() && !inList("disabled", c);
    }

    boolean premium(Cosmetic c) {
        JsonObject cfg = config();
        // Ohne Dashboard-Daten die Standardliste aus dem Dashboard nachbilden
        if (cfg == null || !(cfg.get("premium") instanceof JsonArray)) {
            return switch (c) {
                case TRAIL_RAINBOW, TRAIL_SOULS, HAT_DIAMOND, HAT_BEACON, GADGET_ROCKET, PET_FOX, PET_AXOLOTL -> true;
                default -> false;
            };
        }
        return inList("premium", c);
    }

    boolean unlocked(Player player, Cosmetic c) {
        return !premium(c) || player.hasPermission("lobby.cosmetics.premium") || player.hasPermission("lobby.cosmetic." + c.key());
    }

    // ---------- Auswahl ----------

    private EnumMap<Cosmetic.Category, Cosmetic> of(Player player) {
        return selected.computeIfAbsent(player.getUniqueId(), id -> {
            EnumMap<Cosmetic.Category, Cosmetic> map = new EnumMap<>(Cosmetic.Category.class);
            for (Cosmetic.Category cat : Cosmetic.Category.values()) {
                Cosmetic c = Cosmetic.byKey(saved.getString(id + "." + cat.id, ""));
                if (c != null) map.put(cat, c);
            }
            return map;
        });
    }

    /** Ausgewähltes Cosmetic, wenn es noch erlaubt ist (Rechte und Dashboard können sich ändern). */
    Cosmetic current(Player player, Cosmetic.Category cat) {
        Cosmetic c = of(player).get(cat);
        return c != null && available(c) && unlocked(player, c) ? c : null;
    }

    private void select(Player player, Cosmetic.Category cat, Cosmetic c) {
        if (c == null) of(player).remove(cat);
        else of(player).put(cat, c);
        saved.set(player.getUniqueId() + "." + cat.id, c == null ? null : c.key());
        String yaml = saved.saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.nio.file.Files.writeString(file.toPath(), yaml);
            } catch (IOException e) {
                plugin.getLogger().warning("cosmetics.yml konnte nicht gespeichert werden: " + e.getMessage());
            }
        });
        apply(player);
    }

    /** Hut, Gadget-Item und Haustier passend zur Auswahl setzen (nach Join, Lobby-Items, Änderung). */
    void apply(Player player) {
        boolean lobby = player.getWorld().equals(plugin.hub().world()) && !plugin.isBuilding(player);
        applyHat(player);
        if (!plugin.parkour().isRunning(player) && lobby) {
            player.getInventory().setItem(MENU_SLOT, enabled() ? menuItem(player) : null);
            Cosmetic gadget = current(player, Cosmetic.Category.GADGET);
            player.getInventory().setItem(GADGET_SLOT, gadget == null ? null : gadgetItem(player, gadget));
        }
        Cosmetic pet = lobby ? current(player, Cosmetic.Category.PET) : null;
        Mob existing = pets.get(player.getUniqueId());
        if (pet == null || existing == null || !existing.isValid() || existing.getType() != pet.petType()) {
            removePet(player);
            if (pet != null) spawnPet(player, pet);
        }
    }

    void applyHat(Player player) {
        if (plugin.isBuilding(player)) return;
        Cosmetic hat = current(player, Cosmetic.Category.HAT);
        player.getInventory().setHelmet(hat == null ? null : named(new ItemStack(hat.icon), "<white>" + hat.name(Lang.german(player.locale()))));
    }

    void forget(Player player) {
        removePet(player);
        selected.remove(player.getUniqueId());
        gadgetCooldown.remove(player.getUniqueId());
        rocketTicks.remove(player.getUniqueId());
        lastPosition.remove(player.getUniqueId());
    }

    void shutdown() {
        pets.values().forEach(Entity::remove);
        pets.clear();
        // Bemalte Blöcke zurücksetzen
        for (Map.Entry<Location, BlockData> e : painted.entrySet()) {
            for (Player p : e.getKey().getWorld().getPlayers()) p.sendBlockChange(e.getKey(), e.getKey().getBlock().getBlockData());
        }
        painted.clear();
    }

    // ---------- Items & Menü ----------

    private static ItemStack named(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack menuItem(Player player) {
        return plugin.item(ITEM_MENU, Material.ENDER_CHEST, Lang.t(player,
                "<light_purple><bold>Cosmetics</bold> <gray>(Rechtsklick)", "<light_purple><bold>Cosmetics</bold> <gray>(Right-click)"));
    }

    private ItemStack gadgetItem(Player player, Cosmetic gadget) {
        ItemStack item = plugin.item(ITEM_GADGET, gadget.icon, "<aqua>" + gadget.name(Lang.german(player.locale()))
                + Lang.t(player, " <gray>(Rechtsklick)", " <gray>(Right-click)"));
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        if (meta instanceof FireworkMeta fw) fw.setPower(1);
        item.setItemMeta(meta);
        return item;
    }

    /** GUI der Cosmetics; category == null ist die Übersicht. */
    static final class Menu implements InventoryHolder {
        final Cosmetic.Category category;
        final Map<Integer, Cosmetic> bySlot = new HashMap<>();
        Inventory inventory;

        Menu(Cosmetic.Category category) {
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static final int BACK_SLOT = 39;
    static final int CLEAR_SLOT = 41;
    private static final int[] CATEGORY_SLOTS = {10, 12, 14, 16};

    void openMain(Player player) {
        boolean de = Lang.german(player.locale());
        if (!enabled()) {
            player.sendMessage(MM.deserialize(de ? "<red>Cosmetics sind gerade ausgeschaltet." : "<red>Cosmetics are turned off right now."));
            return;
        }
        Menu menu = new Menu(null);
        menu.inventory = Bukkit.createInventory(menu, 27, MM.deserialize("<dark_purple>Cosmetics"));
        Cosmetic.Category[] cats = Cosmetic.Category.values();
        for (int i = 0; i < cats.length; i++) {
            Cosmetic c = current(player, cats[i]);
            List<String> lore = new ArrayList<>();
            lore.add((de ? "<gray>Aktiv: " : "<gray>Active: ") + (c == null ? (de ? "<dark_gray>nichts" : "<dark_gray>none") : "<green>" + c.name(de)));
            lore.add("");
            lore.add(de ? "<yellow>Klicken zum Öffnen" : "<yellow>Click to open");
            menu.inventory.setItem(CATEGORY_SLOTS[i], icon(cats[i].icon, "<light_purple><bold>" + cats[i].name(de), lore, false));
        }
        menu.inventory.setItem(22, icon(Material.BARRIER, de ? "<red>Alles ablegen" : "<red>Remove all", List.of(), false));
        player.openInventory(menu.inventory);
    }

    private void openCategory(Player player, Cosmetic.Category cat) {
        boolean de = Lang.german(player.locale());
        Menu menu = new Menu(cat);
        menu.inventory = Bukkit.createInventory(menu, 45, MM.deserialize("<dark_purple>Cosmetics <dark_gray>» <dark_purple>" + cat.name(de)));
        Cosmetic active = current(player, cat);
        int slot = 10;
        for (Cosmetic c : Cosmetic.values()) {
            if (c.category != cat || !available(c)) continue;
            if (slot % 9 == 8) slot += 2; // Rand frei lassen
            if (slot >= 35) break;
            boolean unlocked = unlocked(player, c);
            List<String> lore = new ArrayList<>();
            if (premium(c)) lore.add("<gold>★ Premium");
            if (c == active) lore.add(de ? "<green>✔ Ausgewählt – klicken zum Ablegen" : "<green>✔ Selected – click to remove");
            else if (unlocked) lore.add(de ? "<yellow>Klicken zum Auswählen" : "<yellow>Click to select");
            else lore.add(de ? "<red>🔒 Gesperrt" : "<red>🔒 Locked");
            menu.inventory.setItem(slot, icon(unlocked ? c.icon : Material.GRAY_DYE, (unlocked ? "<white>" : "<gray>") + c.name(de), lore, c == active));
            menu.bySlot.put(slot, c);
            slot++;
        }
        menu.inventory.setItem(BACK_SLOT, icon(Material.ARROW, de ? "<gray>Zurück" : "<gray>Back", List.of(), false));
        menu.inventory.setItem(CLEAR_SLOT, icon(Material.BARRIER, de ? "<red>Ablegen" : "<red>Remove", List.of(), false));
        player.openInventory(menu.inventory);
    }

    private static ItemStack icon(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        item.setItemMeta(meta);
        return item;
    }

    /** Klick im Cosmetics-GUI (das Event ist schon abgebrochen). */
    void click(Player player, Menu menu, int slot) {
        boolean de = Lang.german(player.locale());
        if (menu.category == null) {
            for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
                if (CATEGORY_SLOTS[i] == slot) openCategory(player, Cosmetic.Category.values()[i]);
            }
            if (slot == 22) {
                for (Cosmetic.Category cat : Cosmetic.Category.values()) select(player, cat, null);
                player.closeInventory();
                player.sendActionBar(MM.deserialize(de ? "<yellow>Alle Cosmetics abgelegt" : "<yellow>All cosmetics removed"));
            }
            return;
        }
        if (slot == BACK_SLOT) {
            openMain(player);
            return;
        }
        if (slot == CLEAR_SLOT) {
            select(player, menu.category, null);
            openCategory(player, menu.category);
            return;
        }
        Cosmetic c = menu.bySlot.get(slot);
        if (c == null) return;
        if (!unlocked(player, c)) {
            player.sendMessage(MM.deserialize(de ? "<red>Dieses Cosmetic ist nur für Premium-Spieler." : "<red>This cosmetic is for premium players only."));
            return;
        }
        boolean remove = c == current(player, c.category);
        select(player, c.category, remove ? null : c);
        player.playSound(Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.6f, 1.4f));
        player.sendActionBar(MM.deserialize(remove
                ? (de ? "<yellow>Abgelegt: <white>" : "<yellow>Removed: <white>") + c.name(de)
                : (de ? "<green>Ausgewählt: <white>" : "<green>Selected: <white>") + c.name(de)));
        openCategory(player, c.category);
    }

    // ---------- Partikel & Haustiere ----------

    /** Jeden zweiten Tick: Spuren zeichnen, Raketen antreiben; alle 10 Ticks Haustiere nachziehen. */
    void tick() {
        tick++;
        for (Player player : plugin.hub().world().getPlayers()) {
            Integer rocket = rocketTicks.get(player.getUniqueId());
            if (rocket != null) {
                player.getWorld().spawnParticle(Particle.FLAME, player.getLocation(), 6, 0.15, 0, 0.15, 0.02);
                player.getWorld().spawnParticle(Particle.POOF, player.getLocation(), 2, 0.1, 0, 0.1, 0.01);
                if (rocket <= 1) rocketTicks.remove(player.getUniqueId());
                else rocketTicks.put(player.getUniqueId(), rocket - 1);
            }
            Cosmetic trail = current(player, Cosmetic.Category.TRAIL);
            if (trail != null && !plugin.isVanishedLike(player)) trail(player, trail);
        }
        if (tick % 5 == 0) followPets();
    }

    private void trail(Player player, Cosmetic trail) {
        Location loc = player.getLocation().add(0, 0.15, 0);
        Location last = lastPosition.put(player.getUniqueId(), loc.clone());
        boolean moving = last == null || !last.getWorld().equals(loc.getWorld()) || last.distanceSquared(loc) > 0.0025;
        if (!moving && tick % 4 != 0) return; // im Stehen seltener
        var world = player.getWorld();
        switch (trail) {
            case TRAIL_HEARTS -> world.spawnParticle(Particle.HEART, loc.add(0, 0.3, 0), 1, 0.3, 0.2, 0.3, 0);
            case TRAIL_FLAMES -> world.spawnParticle(Particle.FLAME, loc, 3, 0.2, 0.05, 0.2, 0.01);
            case TRAIL_NOTES -> world.spawnParticle(Particle.NOTE, loc.add(0, 2.2, 0), 1, 0.4, 0.1, 0.4, 1);
            case TRAIL_CLOUDS -> world.spawnParticle(Particle.CLOUD, loc, 3, 0.2, 0.02, 0.2, 0.01);
            case TRAIL_SNOW -> world.spawnParticle(Particle.SNOWFLAKE, loc.add(0, 2.3, 0), 3, 0.3, 0.1, 0.3, 0.01);
            case TRAIL_SPARKLE -> world.spawnParticle(Particle.END_ROD, loc.add(0, 0.8, 0), 1, 0.35, 0.5, 0.35, 0.01);
            case TRAIL_MAGIC -> world.spawnParticle(Particle.ENCHANT, loc.add(0, 1, 0), 8, 0.4, 0.6, 0.4, 0.4);
            case TRAIL_RAINBOW -> {
                java.awt.Color c = java.awt.Color.getHSBColor((tick % 60) / 60f, 1f, 1f);
                world.spawnParticle(Particle.DUST, loc, 4, 0.25, 0.05, 0.25, 0,
                        new Particle.DustOptions(Color.fromRGB(c.getRed(), c.getGreen(), c.getBlue()), 1.2f));
            }
            case TRAIL_SOULS -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 3, 0.2, 0.05, 0.2, 0.01);
            case TRAIL_CHERRY -> world.spawnParticle(Particle.CHERRY_LEAVES, loc.add(0, 2.2, 0), 2, 0.4, 0.1, 0.4, 0);
            default -> {
            }
        }
    }

    private void spawnPet(Player player, Cosmetic pet) {
        Location loc = player.getLocation();
        Entity entity = loc.getWorld().spawnEntity(loc, pet.petType());
        if (!(entity instanceof Mob mob)) {
            entity.remove();
            return;
        }
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setInvulnerable(true);
        mob.setCollidable(false);
        mob.setSilent(pet != Cosmetic.PET_CAT && pet != Cosmetic.PET_DOG);
        if (mob instanceof Ageable ageable) ageable.setBaby();
        Bukkit.getMobGoals().removeAllGoals(mob); // nicht weglaufen, nichts angreifen
        boolean de = Lang.german(player.locale());
        mob.customName(MM.deserialize("<gray>" + MM.escapeTags(player.getName()) + (de ? "s " : "'s ") + "<white>" + pet.name(de)));
        mob.setCustomNameVisible(true);
        pets.put(player.getUniqueId(), mob);
    }

    private void removePet(Player player) {
        Mob mob = pets.remove(player.getUniqueId());
        if (mob != null) mob.remove();
    }

    boolean isPet(Entity entity) {
        return entity instanceof Mob mob && pets.containsValue(mob);
    }

    private void followPets() {
        for (Map.Entry<UUID, Mob> e : new ArrayList<>(pets.entrySet())) {
            Player owner = Bukkit.getPlayer(e.getKey());
            Mob mob = e.getValue();
            if (owner == null || !mob.isValid()) {
                if (owner != null) apply(owner); // neu erzeugen (z. B. Chunk entladen)
                else mob.remove();
                continue;
            }
            Location target = owner.getLocation();
            if (!mob.getWorld().equals(target.getWorld()) || mob.getLocation().distanceSquared(target) > 15 * 15) {
                mob.teleport(target);
                continue;
            }
            Pathfinder pathfinder = mob.getPathfinder();
            if (mob.getLocation().distanceSquared(target) > 3 * 3) pathfinder.moveTo(target, 1.4);
            else pathfinder.stopPathfinding();
        }
    }

    // ---------- Gadgets ----------

    /** Rechtsklick mit dem Gadget-Item. */
    void useGadget(Player player) {
        Cosmetic gadget = current(player, Cosmetic.Category.GADGET);
        if (gadget == null) return;
        if (gadget == Cosmetic.GADGET_GRAPPLE) return; // läuft über die Angel (PlayerFishEvent)
        if (!cooldown(player, gadget)) return;
        Location eye = player.getEyeLocation();
        switch (gadget) {
            case GADGET_FIREWORK -> {
                Firework fw = player.getWorld().spawn(player.getLocation(), Firework.class, f -> {
                    FireworkMeta meta = f.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.values()[RANDOM.nextInt(FireworkEffect.Type.values().length)])
                            .withColor(randomColor(), randomColor()).withFade(randomColor())
                            .flicker(RANDOM.nextBoolean()).trail(true).build());
                    meta.setPower(1);
                    f.setFireworkMeta(meta);
                });
                gadgetProjectiles.add(fw.getUniqueId());
            }
            case GADGET_PAINTBALL -> {
                Snowball ball = player.launchProjectile(Snowball.class, eye.getDirection().multiply(1.8));
                gadgetProjectiles.add(ball.getUniqueId());
                player.playSound(Sound.sound(Key.key("entity.snowball.throw"), Sound.Source.PLAYER, 0.8f, 1.4f));
            }
            case GADGET_CONFETTI -> {
                Location at = eye.clone().add(eye.getDirection().multiply(1.5));
                for (int i = 0; i < 40; i++) {
                    player.getWorld().spawnParticle(Particle.DUST, at, 1, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(randomColor(), 1.3f));
                }
                player.getWorld().spawnParticle(Particle.FIREWORK, at, 25, 0.3, 0.3, 0.3, 0.15);
                player.getWorld().playSound(Sound.sound(Key.key("entity.firework_rocket.twinkle"), Sound.Source.PLAYER, 1f, 1.2f), at.getX(), at.getY(), at.getZ());
            }
            case GADGET_ROCKET -> {
                player.setVelocity(player.getVelocity().setY(1.6));
                rocketTicks.put(player.getUniqueId(), 12);
                player.playSound(Sound.sound(Key.key("entity.firework_rocket.launch"), Sound.Source.PLAYER, 1f, 0.8f));
            }
            case GADGET_PEARL -> {
                EnderPearl pearl = player.launchProjectile(EnderPearl.class);
                gadgetProjectiles.add(pearl.getUniqueId());
            }
            default -> {
            }
        }
    }

    private boolean cooldown(Player player, Cosmetic gadget) {
        long seconds = switch (gadget) {
            case GADGET_ROCKET -> 5;
            case GADGET_PEARL, GADGET_FIREWORK -> 3;
            case GADGET_GRAPPLE -> 1;
            default -> 1;
        };
        long now = System.currentTimeMillis();
        long last = gadgetCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < seconds * 1000) {
            double left = (seconds * 1000 - (now - last)) / 1000.0;
            player.sendActionBar(MM.deserialize(String.format(Lang.t(player, "<red>Noch %.1f s warten", "<red>Wait %.1f s"), left)));
            return false;
        }
        gadgetCooldown.put(player.getUniqueId(), now);
        return true;
    }

    private static Color randomColor() {
        java.awt.Color c = java.awt.Color.getHSBColor(RANDOM.nextFloat(), 0.9f, 1f);
        return Color.fromRGB(c.getRed(), c.getGreen(), c.getBlue());
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (current(player, Cosmetic.Category.GADGET) != Cosmetic.GADGET_GRAPPLE) return;
        PlayerFishEvent.State state = event.getState();
        if (state != PlayerFishEvent.State.IN_GROUND && state != PlayerFishEvent.State.REEL_IN
                && state != PlayerFishEvent.State.CAUGHT_ENTITY && state != PlayerFishEvent.State.FAILED_ATTEMPT) return;
        Location hook = event.getHook().getLocation();
        Vector pull = hook.toVector().subtract(player.getLocation().toVector());
        double distance = pull.length();
        if (distance < 2 || !cooldown(player, Cosmetic.GADGET_GRAPPLE)) return;
        Vector velocity = pull.normalize().multiply(Math.min(3.0, 0.6 + distance * 0.12));
        velocity.setY(Math.max(velocity.getY(), 0.5) + 0.25);
        player.setVelocity(velocity);
        player.playSound(Sound.sound(Key.key("entity.fishing_bobber.retrieve"), Sound.Source.PLAYER, 1f, 0.8f));
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!gadgetProjectiles.remove(event.getEntity().getUniqueId())) return;
        if (!(event.getEntity() instanceof Snowball ball)) return;
        Location center = event.getHitBlock() != null ? event.getHitBlock().getLocation() : ball.getLocation();
        // Blöcke rundherum nur für die Spieler einfärben, nach 3 Sekunden wieder zurück
        List<Location> changed = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = center.getBlock().getRelative(x, y, z);
                    if (!block.getType().isSolid() || !block.getType().isOccluding() || RANDOM.nextInt(4) == 0) continue;
                    Location loc = block.getLocation();
                    painted.putIfAbsent(loc, block.getBlockData());
                    BlockData paint = PAINT[RANDOM.nextInt(PAINT.length)].createBlockData();
                    for (Player p : block.getWorld().getPlayers()) p.sendBlockChange(loc, paint);
                    changed.add(loc);
                }
            }
        }
        ball.getWorld().spawnParticle(Particle.DUST, ball.getLocation(), 15, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(randomColor(), 1.5f));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Location loc : changed) {
                if (painted.remove(loc) == null) continue;
                BlockData real = loc.getBlock().getBlockData();
                for (Player p : loc.getWorld().getPlayers()) p.sendBlockChange(loc, real);
            }
        }, 60L);
    }

    /** Teleport per Gadget-Enderperle erlauben (ohne Schaden), Cosmetics in anderen Welten ablegen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.getTo().getWorld().equals(event.getFrom().getWorld())) {
            Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
        }
    }
}
