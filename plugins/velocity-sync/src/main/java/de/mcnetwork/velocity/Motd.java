package de.mcnetwork.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Serverliste: MOTD, Server-Icon, Hover-Text, maximale Spielerzahl und Wartungsmodus –
 * alles aus dem Dashboard (Seite "MOTD"), ohne Neustart.
 */
final class Motd {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final UUID SAMPLE_ID = new UUID(0, 0);

    private final VelocitySync plugin;
    private volatile JsonObject settings;
    private volatile Favicon favicon;
    private volatile String version = "";

    Motd(VelocitySync plugin) {
        this.plugin = plugin;
    }

    String version() {
        return version;
    }

    void update(JsonObject motd) {
        String icon = VelocitySync.str(motd, "icon", "");
        Favicon newFavicon = null;
        if (!icon.isBlank()) {
            try {
                newFavicon = new Favicon(icon);
            } catch (IllegalArgumentException ignored) {
                // ungültiges Icon: ohne Icon weiter
            }
        }
        favicon = newFavicon;
        settings = motd;
        version = VelocitySync.str(motd, "version", "");
    }

    boolean maintenance() {
        JsonObject s = settings;
        return s != null && s.has("maintenance") && s.get("maintenance").getAsBoolean();
    }

    void onPing(ProxyPingEvent event, Collection<Player> visiblePlayers) {
        JsonObject s = settings;
        if (s == null) return;
        String network = VelocitySync.str(plugin.settings(), "networkName", "Netzwerk");
        var networkTag = Placeholder.parsed("network", network);

        String line1 = VelocitySync.str(s, "line1", "");
        String line2 = maintenance() ? VelocitySync.str(s, "maintenanceLine2", "") : VelocitySync.str(s, "line2", "");
        ServerPing.Builder ping = event.getPing().asBuilder();
        if (!line1.isBlank() || !line2.isBlank()) {
            ping.description(MM.deserialize(line1, networkTag).append(Component.newline()).append(MM.deserialize(line2, networkTag)));
        }
        if (favicon != null) ping.favicon(favicon);

        int max = s.has("maxPlayers") ? s.get("maxPlayers").getAsInt() : 0;
        if (max > 0) ping.maximumPlayers(max);
        ping.onlinePlayers(visiblePlayers.size()); // Unsichtbare nicht mitzählen

        // Hover über der Spielerzahl: eigener Text oder die Spieler, die online sind
        ping.clearSamplePlayers();
        List<ServerPing.SamplePlayer> sample = new ArrayList<>();
        String hover = VelocitySync.str(s, "hover", "");
        if (!hover.isBlank()) {
            for (String line : hover.split("\n")) {
                sample.add(new ServerPing.SamplePlayer(SECTION.serialize(MM.deserialize(line, networkTag)), SAMPLE_ID));
            }
        } else {
            visiblePlayers.stream().limit(12).forEach(p ->
                    sample.add(new ServerPing.SamplePlayer(p.getUsername(), p.getUniqueId())));
            if (visiblePlayers.size() > 12) {
                sample.add(new ServerPing.SamplePlayer("§7… +" + (visiblePlayers.size() - 12), SAMPLE_ID));
            }
        }
        ping.samplePlayers(sample.toArray(ServerPing.SamplePlayer[]::new));

        // Wartung: statt der Ping-Balken steht rot "Wartung" in der Serverliste
        if (maintenance()) ping.version(new ServerPing.Version(-1, "Wartung / Maintenance"));
        event.setPing(ping.build());
    }

    /** Im Wartungsmodus kommen nur Spieler mit network.maintenance.bypass ins Netzwerk. */
    void onLogin(LoginEvent event) {
        if (!maintenance() || event.getPlayer().hasPermission("network.maintenance.bypass")) return;
        String kick = VelocitySync.str(settings, "maintenanceKick", "Wartungsarbeiten / Maintenance");
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(kick)));
    }
}
