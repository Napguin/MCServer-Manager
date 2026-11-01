package de.mcnetwork.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Das Lobby-System: eigene Lobby-Welt, Kompass mit Serverauswahl, Spieler verstecken,
 * Doppelsprung, Launchpads, Scoreboard, Tab-Liste und Schutz der Lobby.
 * Texte und Schalter kommen aus dem Dashboard (Seite "Lobby").
 */
public class LobbySelector extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String SELECTOR = "selector";
    private static final String HIDER = "hider";
    private static final int HIDER_SLOT = 8;

    private NamespacedKey itemKey;
    private DashboardClient dashboard;
    private HubWorld hub;
    private LobbyDisplay display;
    private LobbyFeatures features;
    private LobbyObjects objects;
    private Parkour parkour;
    private Cosmetics cosmetics;
    private ExtrasCommands extrasCommands;
    private boolean luckPerms;

    private final Set<UUID> building = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hiding = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> hiderCooldown = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item");
        // Erst die Lobby-Welt laden, dann die Config lesen (gespeicherte Positionen brauchen die Welt)
        hub = new HubWorld(this);
        hub.load();
        dashboard = new DashboardClient(getLogger(),
                env("DASHBOARD_URL", getConfig().getString("dashboard-url", "http://dashboard:8080")),
                env("DASHBOARD_API_KEY", getConfig().getString("api-key", "")));
        luckPerms = getServer().getPluginManager().isPluginEnabled("LuckPerms");

        display = new LobbyDisplay(this);
        features = new LobbyFeatures(this);
        parkour = new Parkour(this);
        objects = new LobbyObjects(this);
        cosmetics = new Cosmetics(this);
        extrasCommands = new ExtrasCommands(this);

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(features, this);
        getServer().getPluginManager().registerEvents(objects, this);
        getServer().getPluginManager().registerEvents(parkour, this);
        getServer().getPluginManager().registerEvents(cosmetics, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, dashboard::refresh, 1L, 60L);
        getServer().getScheduler().runTaskTimer(this, this::slowTick, 20L, 40L);
        getServer().getScheduler().runTaskTimer(this, objects::lookTick, 25L, 5L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            parkour.timerTick();
            cosmetics.tick();
        }, 22L, 2L);

        // Nach einem Reload die schon verbundenen Spieler übernehmen
        for (Player player : getServer().getOnlinePlayers()) {
            preparePlayer(player, true);
            display.add(player);
        }
    }

    @Override
    public void onDisable() {
        if (parkour != null) parkour.shutdown();
        if (objects != null) objects.removeAll();
        if (cosmetics != null) cosmetics.shutdown();
        if (display != null) display.shutdown();
    }

    /** Alle 2 Sekunden: Scoreboard, Hologramme, NPCs, Schilder und Bestenlisten aktualisieren. */
    private void slowTick() {
        display.tick();
        refreshExtras();
    }

    private void refreshExtras() {
        objects.tick();
        parkour.tick();
        for (Player player : getServer().getOnlinePlayers()) {
            objects.apply(player);
            parkour.apply(player);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Einstellungen in der Sprache des Spielers (Deutsch oder Englisch). */
    LobbySettings settings(Player player) {
        return dashboard.settings(Lang.german(player.locale()));
    }

    DashboardClient dashboard() {
        return dashboard;
    }

    HubWorld hub() {
        return hub;
    }

    LobbyDisplay display() {
        return display;
    }

    LobbyObjects objects() {
        return objects;
    }

    Parkour parkour() {
        return parkour;
    }

    Cosmetics cosmetics() {
        return cosmetics;
    }

    /** Unsichtbare Spieler (/vanish) sollen sich nicht durch Partikel verraten. */
    boolean isVanishedLike(Player player) {
        if (!luckPerms) return false;
        try {
            return RankProvider.vanished(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Eintrag der Lobby-Extras im Dashboard speichern (data = null löscht ihn) und dem Spieler
     * Bescheid geben. Danach werden Hologramme, NPCs usw. sofort neu aufgebaut.
     */
    void saveExtra(Player player, String kind, String id, JsonObject data, String okDe, String okEn) {
        String by = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String error = null;
            try {
                dashboard.saveExtra(kind, id, data, by);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "abgebrochen";
            }
            String failure = error;
            getServer().getScheduler().runTask(this, () -> {
                if (failure == null) refreshExtras();
                if (!player.isOnline()) return;
                player.sendMessage(MM.deserialize(failure == null
                        ? "<green>" + Lang.t(player, okDe, okEn)
                        : Lang.t(player, "<red>Speichern fehlgeschlagen: ", "<red>Saving failed: ") + MM.escapeTags(failure)));
            });
        });
    }

    boolean isBuilding(Player player) {
        return building.contains(player.getUniqueId());
    }

    Component rank(Player player) {
        if (luckPerms) {
            try {
                return RankProvider.rank(player);
            } catch (Throwable ignored) {
                // LuckPerms noch nicht bereit
            }
        }
        return Component.text(Lang.t(player, "Spieler", "Player"));
    }

    // ---------- Items ----------

    ItemStack item(String type, Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private String itemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private ItemStack selectorItem(Player player) {
        Material material = Material.matchMaterial(getConfig().getString("selector.material", "COMPASS"));
        String name = Lang.german(player.locale())
                ? getConfig().getString("selector.name", "<gold><bold>Serverauswahl</bold></gold> <gray>(Rechtsklick)")
                : getConfig().getString("selector.name-en", "<gold><bold>Server Selector</bold></gold> <gray>(Right-click)");
        return item(SELECTOR, material == null ? Material.COMPASS : material, name);
    }

    private ItemStack hiderItem(Player player, boolean hidden) {
        boolean de = Lang.german(player.locale());
        return hidden
                ? item(HIDER, Material.GRAY_DYE, de ? "<red>Spieler: <white>versteckt <gray>(Rechtsklick)" : "<red>Players: <white>hidden <gray>(Right-click)")
                : item(HIDER, Material.LIME_DYE, de ? "<green>Spieler: <white>sichtbar <gray>(Rechtsklick)" : "<green>Players: <white>visible <gray>(Right-click)");
    }

    private void giveLobbyItems(Player player) {
        player.getInventory().clear();
        player.getInventory().setItem(getConfig().getInt("selector.slot", 4), selectorItem(player));
        if (settings(player).playerHider()) {
            player.getInventory().setItem(HIDER_SLOT, hiderItem(player, hiding.contains(player.getUniqueId())));
        }
        cosmetics.apply(player);
    }

    /** Nach einem Jump & Run: Lobby-Items wieder ausgeben. */
    void restoreLobby(Player player) {
        if (!isBuilding(player)) giveLobbyItems(player);
    }

    private void preparePlayer(Player player, boolean teleport) {
        if (!isBuilding(player)) {
            player.setGameMode(GameMode.ADVENTURE);
            giveLobbyItems(player);
        }
        player.setFoodLevel(20);
        player.setFireTicks(0);
        if (teleport) player.teleport(hub.spawn());
        // Spieler, die andere verstecken, sollen auch Neue nicht sehen
        for (UUID hiderId : hiding) {
            Player hider = getServer().getPlayer(hiderId);
            if (hider != null && hider != player) hider.hidePlayer(this, player);
        }
    }

    private void toggleHider(Player player) {
        long now = System.currentTimeMillis();
        if (now - hiderCooldown.getOrDefault(player.getUniqueId(), 0L) < 1000) return;
        hiderCooldown.put(player.getUniqueId(), now);

        boolean hide = hiding.add(player.getUniqueId());
        if (!hide) hiding.remove(player.getUniqueId());
        for (Player other : getServer().getOnlinePlayers()) {
            if (other == player) continue;
            if (hide) player.hidePlayer(this, other);
            else player.showPlayer(this, other);
        }
        player.getInventory().setItem(HIDER_SLOT, hiderItem(player, hide));
        player.sendActionBar(MM.deserialize(hide
                ? Lang.t(player, "<red>Andere Spieler sind versteckt", "<red>Other players are hidden")
                : Lang.t(player, "<green>Andere Spieler sind sichtbar", "<green>Other players are visible")));
    }

    void openMenu(Player player) {
        String title = Lang.german(player.locale())
                ? getConfig().getString("selector.gui-title", "<dark_gray>Wähle einen Server")
                : getConfig().getString("selector.gui-title-en", "<dark_gray>Choose a server");
        player.openInventory(new SelectorMenu(title, dashboard.servers(), player).getInventory());
    }

    /** Zu einem Server wechseln (NPCs, Schilder) – mit denselben Prüfungen wie im GUI. */
    void tryConnect(Player player, String serverId) {
        ServerEntry server = dashboard.servers().stream().filter(s -> s.id().equalsIgnoreCase(serverId)).findFirst().orElse(null);
        if (server == null) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Diesen Server gibt es nicht.", "<red>This server doesn't exist.")));
        } else if (!SelectorMenu.canJoin(player, server)) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Du hast keinen Zugang zu diesem Server.", "<red>You don't have access to this server.")));
        } else if (!server.online()) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Dieser Server ist gerade nicht online.", "<red>This server is offline right now.")));
        } else {
            connect(player, server);
        }
    }

    private void connect(Player player, ServerEntry server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server.id());
            player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
            player.sendMessage(MM.deserialize(Lang.t(player, "<gray>Verbinde mit <white>", "<gray>Connecting to <white>")
                    + MM.escapeTags(server.name()) + "<gray>..."));
        } catch (IOException e) {
            player.sendMessage(MM.deserialize(Lang.t(player, "<red>Verbindung fehlgeschlagen.", "<red>Connection failed.")));
        }
    }

    // ---------- Befehle ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only in game.");
            return true;
        }
        switch (command.getName().toLowerCase()) {
            case "servers" -> openMenu(player);
            case "spawn" -> {
                parkour.stop(player, true);
                player.teleport(hub.spawn());
            }
            case "cosmetics" -> cosmetics.openMain(player);
            case "holo" -> {
                return extrasCommands.holo(player, args);
            }
            case "npc" -> {
                return extrasCommands.npc(player, args);
            }
            case "serversign" -> {
                return extrasCommands.serverSign(player, args);
            }
            case "parkour" -> {
                return parkour.command(player, args);
            }
            case "setspawn", "sethologram" -> {
                if (!player.getWorld().equals(hub.world())) {
                    player.sendMessage(MM.deserialize(Lang.t(player, "<red>Das geht nur in der Lobby-Welt.", "<red>This only works in the lobby world.")));
                } else if (command.getName().equalsIgnoreCase("setspawn")) {
                    hub.setSpawn(player.getLocation());
                    player.sendMessage(MM.deserialize(Lang.t(player, "<green>Spawn gesetzt.", "<green>Spawn set.")));
                } else {
                    hub.setHologram(player.getLocation().add(0, 1.5, 0));
                    display.resetHologram();
                    player.sendMessage(MM.deserialize(Lang.t(player, "<green>Hologramm verschoben.", "<green>Hologram moved.")));
                }
            }
            case "build" -> {
                if (building.remove(player.getUniqueId())) {
                    preparePlayer(player, false);
                    player.sendMessage(MM.deserialize(Lang.t(player, "<yellow>Baumodus <red>aus<yellow>.", "<yellow>Build mode <red>off<yellow>.")));
                } else {
                    parkour.stop(player, false);
                    building.add(player.getUniqueId());
                    player.getInventory().clear();
                    player.setGameMode(GameMode.CREATIVE);
                    player.sendMessage(MM.deserialize(Lang.t(player,
                            "<yellow>Baumodus <green>an<yellow>. Du kannst jetzt bauen. <gray>(/build zum Beenden)",
                            "<yellow>Build mode <green>on<yellow>. You can build now. <gray>(/build to stop)")));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("parkour") && !sender.hasPermission("lobby.admin")) {
            return args.length == 1 ? Stream.of("leave", "list", "tp").filter(o -> o.startsWith(args[0].toLowerCase())).toList() : List.of();
        }
        return extrasCommands.complete(name, args);
    }

    // ---------- Beitreten / Verlassen ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LobbySettings settings = settings(player);
        if (settings.hideJoinMessages()) event.joinMessage(null);
        preparePlayer(player, true);
        display.add(player);
        objects.apply(player);
        parkour.apply(player);
        if (!settings.welcomeTitle().isBlank() || !settings.welcomeSubtitle().isBlank()) {
            var resolver = Placeholder.unparsed("player", player.getName());
            var network = Placeholder.parsed("network", settings.networkName());
            player.showTitle(Title.title(
                    MM.deserialize(settings.welcomeTitle(), resolver, network),
                    MM.deserialize(settings.welcomeSubtitle(), resolver, network)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (settings(player).hideJoinMessages()) event.quitMessage(null);
        building.remove(player.getUniqueId());
        hiding.remove(player.getUniqueId());
        hiderCooldown.remove(player.getUniqueId());
        features.forget(player);
        parkour.forget(player);
        objects.forget(player);
        cosmetics.forget(player);
        display.remove(player);
    }

    /** Sprache im Spiel umgestellt: Items mit neuen Namen ausgeben. */
    @EventHandler
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline() && !isBuilding(player)) giveLobbyItems(player);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(hub.spawn());
        getServer().getScheduler().runTask(this, () -> preparePlayer(event.getPlayer(), false));
    }

    // ---------- Items benutzen & Lobby schützen ----------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            String type = itemType(event.getItem());
            if (SELECTOR.equals(type)) {
                event.setCancelled(true);
                openMenu(player);
                return;
            }
            if (HIDER.equals(type)) {
                event.setCancelled(true);
                toggleHider(player);
                return;
            }
            if (type != null) {
                switch (type) {
                    case Cosmetics.ITEM_MENU -> cosmetics.openMain(player);
                    case Cosmetics.ITEM_GADGET -> {
                        // Die Angel (Enterhaken) muss normal ausgeworfen werden
                        if (event.getItem().getType() == Material.FISHING_ROD) return;
                        cosmetics.useGadget(player);
                    }
                    case Parkour.ITEM_CHECKPOINT -> parkour.backToCheckpoint(player);
                    case Parkour.ITEM_RESTART -> parkour.restart(player);
                    case Parkour.ITEM_LEAVE -> {
                        parkour.stop(player, true);
                        player.sendMessage(MM.deserialize(Lang.t(player, "<yellow>Jump & Run verlassen.", "<yellow>Left the parkour.")));
                    }
                    default -> {
                        return;
                    }
                }
                event.setCancelled(true);
                return;
            }
        }
        if (isBuilding(player)) return;
        // Türen, Truhen, Knöpfe usw. nicht benutzen; Druckplatten (Launchpads) bleiben erlaubt
        if (action == Action.RIGHT_CLICK_BLOCK) event.setUseInteractedBlock(Event.Result.DENY);
        if (action == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof Cosmetics.Menu menu) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getView().getTopInventory() && event.getWhoClicked() instanceof Player player) {
                cosmetics.click(player, menu, event.getSlot());
            }
            return;
        }
        if (event.getInventory().getHolder(false) instanceof SelectorMenu menu) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            ServerEntry server = menu.serverAt(event.getSlot());
            if (server == null || !(event.getWhoClicked() instanceof Player player)) return;
            if (!SelectorMenu.canJoin(player, server)) {
                player.sendMessage(MM.deserialize(Lang.t(player, "<red>Du hast keinen Zugang zu diesem Server.", "<red>You don't have access to this server.")));
                return;
            }
            if (!server.online()) {
                player.sendMessage(MM.deserialize(Lang.t(player, "<red>Dieser Server ist gerade nicht online.", "<red>This server is offline right now.")));
                return;
            }
            player.closeInventory();
            connect(player, server);
            return;
        }
        if (event.getWhoClicked() instanceof Player player && !isBuilding(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SelectorMenu
                || event.getInventory().getHolder(false) instanceof Cosmetics.Menu
                || (event.getWhoClicked() instanceof Player player && !isBuilding(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        } else if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player player && !isBuilding(player)) {
            event.setCancelled(true); // z. B. Rüstungsständer
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player) || !isBuilding(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!isBuilding(event.getPlayer())) event.setCancelled(true);
    }
}
