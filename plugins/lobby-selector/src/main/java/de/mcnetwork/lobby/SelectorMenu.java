package de.mcnetwork.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Das Serverauswahl-GUI. Merkt sich, welcher Slot zu welchem Server gehört. */
public class SelectorMenu implements InventoryHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Inventory inventory;
    private final Map<Integer, ServerEntry> bySlot = new HashMap<>();

    public SelectorMenu(String title, List<ServerEntry> servers, Player viewer) {
        // Wunsch-Slots zuerst belegen, Server ohne (oder mit doppeltem) Slot danach auffüllen
        List<ServerEntry> unplaced = new ArrayList<>();
        for (ServerEntry server : servers) {
            if (server.slot() >= 0 && server.slot() < 54 && !bySlot.containsKey(server.slot())) {
                bySlot.put(server.slot(), server);
            } else {
                unplaced.add(server);
            }
        }
        int next = 0;
        for (ServerEntry server : unplaced) {
            while (bySlot.containsKey(next)) next++;
            if (next >= 54) break;
            bySlot.put(next, server);
        }

        int highest = bySlot.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int rows = Math.min(6, Math.max(1, highest / 9 + 1));
        this.inventory = Bukkit.createInventory(this, rows * 9, MM.deserialize(title));

        boolean de = Lang.german(viewer.locale());
        bySlot.forEach((slot, server) -> inventory.setItem(slot, icon(server, canJoin(viewer, server), de)));
        if (bySlot.isEmpty()) {
            inventory.setItem(4, item(Material.BARRIER,
                    MM.deserialize(de ? "<red>Noch keine Server" : "<red>No servers yet"),
                    List.of(MM.deserialize(de ? "<gray>Füge im Dashboard einen Server hinzu." : "<gray>Add a server in the dashboard."))));
        }
    }

    /** Geschützte Server brauchen das Recht network.server.<id> (der Proxy prüft das ebenfalls). */
    static boolean canJoin(Player player, ServerEntry server) {
        return !server.restricted() || player.hasPermission("network.server." + server.id());
    }

    private static ItemStack icon(ServerEntry server, boolean allowed, boolean de) {
        Material material = Material.matchMaterial(server.icon() == null ? "" : server.icon());
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.GRASS_BLOCK;
        }

        List<Component> lore = new ArrayList<>();
        if (server.description() != null && !server.description().isBlank()) {
            for (String line : server.description().split("\n")) {
                lore.add(MM.deserialize("<gray>" + MM.escapeTags(line)));
            }
            lore.add(Component.empty());
        }
        if (!allowed) {
            material = Material.IRON_BARS;
            lore.add(MM.deserialize(de ? "<red>🔒 Kein Zugang" : "<red>🔒 No access"));
            lore.add(MM.deserialize(de ? "<gray>Dieser Server ist nur für bestimmte Spieler." : "<gray>This server is for selected players only."));
        } else if (server.online()) {
            lore.add(MM.deserialize("<green>● Online"));
            lore.add(MM.deserialize((de ? "<gray>Spieler: <white>" : "<gray>Players: <white>") + server.players()));
            lore.add(Component.empty());
            lore.add(MM.deserialize(de ? "<yellow>Klicke zum Betreten" : "<yellow>Click to join"));
        } else if ("starting".equals(server.status())) {
            lore.add(MM.deserialize(de ? "<gold>● Startet gerade..." : "<gold>● Starting..."));
        } else {
            lore.add(MM.deserialize("<red>● Offline"));
        }

        return item(material, MM.deserialize("<white><bold>" + MM.escapeTags(server.name())), lore);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    public ServerEntry serverAt(int slot) {
        return bySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
