package de.mcnetwork.lobby;

/** Ein Server aus dem Dashboard, so wie er im GUI angezeigt wird. */
public record ServerEntry(
        String id,
        String name,
        String description,
        String icon,
        int slot,
        boolean online,
        int players,
        String status,
        boolean restricted
) {
}
