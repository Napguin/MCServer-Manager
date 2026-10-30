package de.mcnetwork.velocity;

import com.velocitypowered.api.proxy.Player;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;

/**
 * Zugriff auf LuckPerms (Prefix, Gewicht, Vanish). Eigene Klasse, damit die
 * LuckPerms-Klassen nur geladen werden, wenn LuckPerms installiert ist.
 */
final class Ranks {

    private Ranks() {
    }

    private static CachedMetaData meta(Player player) {
        return LuckPermsProvider.get().getPlayerAdapter(Player.class).getMetaData(player);
    }

    static String prefix(Player player) {
        String value = meta(player).getPrefix();
        return value == null ? "" : value;
    }

    static String suffix(Player player) {
        String value = meta(player).getSuffix();
        return value == null ? "" : value;
    }

    /** Vanish wird netzwerkweit als LuckPerms-Meta "vanished=true" gespeichert. */
    static boolean vanished(Player player) {
        return "true".equals(meta(player).getMetaValue("vanished"));
    }

    static int weight(Player player) {
        LuckPerms luckPerms = LuckPermsProvider.get();
        String primary = meta(player).getPrimaryGroup();
        Group group = primary == null ? null : luckPerms.getGroupManager().getGroup(primary);
        return group == null ? 0 : group.getWeight().orElse(0);
    }
}
