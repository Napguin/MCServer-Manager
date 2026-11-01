package de.mcnetwork.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bewegung in der Lobby: Doppelsprung, Launchpads, Rückkehr aus dem Void, kein Wetter, keine Mobs. */
final class LobbyFeatures implements Listener {

    private static final Sound JUMP = Sound.sound(Key.key("entity.bat.takeoff"), Sound.Source.PLAYER, 0.6f, 1.3f);
    private static final Sound LAUNCH = Sound.sound(Key.key("entity.firework_rocket.launch"), Sound.Source.PLAYER, 1f, 1f);

    private final LobbySelector plugin;
    private final Map<UUID, Long> padCooldown = new ConcurrentHashMap<>();

    LobbyFeatures(LobbySelector plugin) {
        this.plugin = plugin;
    }

    private boolean canDoubleJump(Player player) {
        GameMode mode = player.getGameMode();
        return plugin.settings(player).doubleJump()
                && (mode == GameMode.ADVENTURE || mode == GameMode.SURVIVAL)
                && !plugin.isBuilding(player)
                && !plugin.parkour().isRunning(player);
    }

    private static boolean standsOnBlock(Player player) {
        return player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType().isSolid();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (plugin.settings(player).voidTeleport() && to.getY() < plugin.hub().voidLevel()
                && to.getWorld().equals(plugin.hub().world())) {
            player.setFallDistance(0);
            if (plugin.parkour().isRunning(player)) plugin.parkour().backToCheckpoint(player);
            else player.teleport(plugin.hub().spawn());
            return;
        }
        // Flugerlaubnis = Doppelsprung bereit, sobald der Spieler wieder steht
        if (canDoubleJump(player) && !player.getAllowFlight() && standsOnBlock(player)) {
            player.setAllowFlight(true);
        }
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !canDoubleJump(player)) return;
        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setVelocity(player.getLocation().getDirection().multiply(1.3).setY(1.0));
        player.playSound(JUMP);
    }

    @EventHandler
    public void onLaunchPad(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.LIGHT_WEIGHTED_PRESSURE_PLATE) return;
        Player player = event.getPlayer();
        if (!plugin.settings(player).launchPads() || plugin.parkour().isRunning(player)) return;
        if (plugin.parkour().isPoint(event.getClickedBlock())) return; // Ziel-Druckplatte eines Jump & Runs
        long now = System.currentTimeMillis();
        if (now - padCooldown.getOrDefault(player.getUniqueId(), 0L) < 1000) return;
        padCooldown.put(player.getUniqueId(), now);

        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        player.setVelocity(direction.normalize().multiply(2.8).setY(1.2));
        player.playSound(LAUNCH);
    }

    void forget(Player player) {
        padCooldown.remove(player.getUniqueId());
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) event.setCancelled(true);
    }
}
