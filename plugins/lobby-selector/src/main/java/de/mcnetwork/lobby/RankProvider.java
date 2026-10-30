package de.mcnetwork.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import org.bukkit.entity.Player;

/**
 * Rang eines Spielers aus LuckPerms. Eigene Klasse, damit die LuckPerms-Klassen
 * nur geladen werden, wenn LuckPerms auch installiert ist.
 */
final class RankProvider {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private RankProvider() {
    }

    static Component rank(Player player) {
        LuckPerms luckPerms = LuckPermsProvider.get();
        CachedMetaData meta = luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String prefix = meta.getPrefix();
        if (prefix != null && !prefix.isBlank()) {
            return LEGACY.deserialize(prefix.strip());
        }
        String primary = meta.getPrimaryGroup();
        Group group = primary == null ? null : luckPerms.getGroupManager().getGroup(primary);
        String name = group != null && group.getDisplayName() != null ? group.getDisplayName() : primary;
        boolean defaultRank = name == null || name.equals("default");
        return Component.text(defaultRank ? Lang.t(player, "Spieler", "Player") : name);
    }
}
