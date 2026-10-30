package de.mcnetwork.chat;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Alles, was LuckPerms braucht. Eigene Klasse, damit die LuckPerms-Klassen
 * nur geladen werden, wenn LuckPerms installiert ist.
 */
final class LuckPermsHook {

    record Rank(String name, String displayName, int weight) {
    }

    private LuckPermsHook() {
    }

    private static LuckPerms api() {
        return LuckPermsProvider.get();
    }

    private static CachedMetaData meta(Player player) {
        return api().getPlayerAdapter(Player.class).getMetaData(player);
    }

    static String prefix(Player player) {
        String value = meta(player).getPrefix();
        return value == null ? "" : value;
    }

    static String suffix(Player player) {
        String value = meta(player).getSuffix();
        return value == null ? "" : value;
    }

    static int weight(Player player) {
        String primary = meta(player).getPrimaryGroup();
        Group group = primary == null ? null : api().getGroupManager().getGroup(primary);
        return group == null ? 0 : group.getWeight().orElse(0);
    }

    static boolean vanished(Player player) {
        return "true".equals(meta(player).getMetaValue("vanished"));
    }

    static void onRecalculate(Plugin plugin, Consumer<UUID> listener) {
        api().getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, e -> listener.accept(e.getUser().getUniqueId()));
    }

    /** Vanish als LuckPerms-Meta speichern; LuckPerms verteilt das an alle Server. */
    static CompletableFuture<Void> setVanished(UUID uuid, boolean vanished) {
        return api().getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.META.predicate(node -> node.getMetaKey().equals("vanished")));
            if (vanished) user.data().add(MetaNode.builder("vanished", "true").build());
        }).thenCompose(v -> pushUpdate(uuid));
    }

    static List<Rank> ranks() {
        return api().getGroupManager().getLoadedGroups().stream()
                .map(g -> new Rank(g.getName(), g.getDisplayName() != null ? g.getDisplayName() : g.getName(), g.getWeight().orElse(0)))
                .sorted(Comparator.comparingInt(Rank::weight).reversed())
                .toList();
    }

    /** Ersetzt alle Rollen des Spielers durch genau diese eine. */
    static CompletableFuture<Void> setRank(UUID uuid, String group) {
        return api().getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.INHERITANCE::matches);
            user.data().add(InheritanceNode.builder(group).build());
        }).thenCompose(v -> pushUpdate(uuid));
    }

    private static CompletableFuture<Void> pushUpdate(UUID uuid) {
        return api().getUserManager().loadUser(uuid).thenAccept((User user) ->
                api().getMessagingService().ifPresent(service -> service.pushUserUpdate(user)));
    }
}
