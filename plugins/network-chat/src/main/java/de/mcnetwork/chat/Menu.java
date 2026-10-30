package de.mcnetwork.chat;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Einfaches Klick-Menü: jedes Feld kann eine Aktion haben. */
final class Menu implements InventoryHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    Menu(int rows, String title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, MM.deserialize(title));
    }

    void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) action.accept(player);
    }

    void fill(Material material) {
        ItemStack filler = item(material, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(MM.deserialize(name)));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(line -> noItalic(MM.deserialize(line))).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
