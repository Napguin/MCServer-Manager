package de.mcnetwork.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

/**
 * Die Lobby-Welt: eine leere Void-Welt, in die beim ersten Start eine schwebende
 * Spawn-Insel mit Brunnen, Laternen und Launchpads gebaut wird.
 */
final class HubWorld {

    static final String NAME = "hub";
    static final int Y = 64;
    static final int RADIUS = 20;
    /** So weit unter dem Spawn gilt man als "ins Void gefallen" (passt auch für eigene Maps). */
    static final int VOID_DEPTH = 30;

    private final JavaPlugin plugin;
    private World world;
    private Location spawn;

    HubWorld(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Leere Welt ohne Gelände. */
    static final class VoidGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, Y + 1, -11.5);
        }
    }

    void load() {
        world = Bukkit.getWorld(NAME);
        if (world == null) {
            world = new WorldCreator(NAME).generator(new VoidGenerator()).generateStructures(false).createWorld();
        }
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6000);

        File marker = new File(plugin.getDataFolder(), "hub-gebaut.txt");
        if (!marker.exists()) {
            plugin.getLogger().info("Baue die Lobby-Insel ...");
            build();
            try {
                Files.createDirectories(marker.getParentFile().toPath());
                Files.writeString(marker.toPath(), "Diese Datei löschen, um die Insel beim nächsten Start neu zu bauen.\n");
            } catch (IOException e) {
                plugin.getLogger().warning("Marker konnte nicht gespeichert werden: " + e.getMessage());
            }
        }

        // Config erst jetzt neu lesen: gespeicherte Positionen verweisen auf die Welt "hub",
        // die beim ersten Einlesen noch nicht geladen war
        plugin.reloadConfig();
        Location saved = plugin.getConfig().getLocation("spawn");
        spawn = saved != null && saved.getWorld() != null ? saved : new Location(world, 0.5, Y + 1, -11.5, 0f, 0f);
        plugin.getLogger().info(String.format("Lobby-Spawn: %.1f / %.1f / %.1f%s", spawn.getX(), spawn.getY(), spawn.getZ(),
                saved != null ? " (aus der Config)" : " (Standard)"));
    }

    World world() {
        return world;
    }

    Location spawn() {
        return spawn.clone();
    }

    void setSpawn(Location location) {
        spawn = location.clone();
        plugin.getConfig().set("spawn", spawn);
        plugin.saveConfig();
    }

    double voidLevel() {
        return spawn.getY() - VOID_DEPTH;
    }

    /** Hologramm: gespeicherte Position (/sethologram) oder 4 Blöcke vor dem Spawn in Augenhöhe+. */
    Location hologram() {
        Location saved = plugin.getConfig().getLocation("hologram");
        if (saved != null && saved.getWorld() != null) return saved.clone();
        Location base = spawn.clone();
        base.setPitch(0);
        return base.add(base.getDirection().multiply(4)).add(0, 2.5, 0);
    }

    void setHologram(Location location) {
        plugin.getConfig().set("hologram", location.clone());
        plugin.saveConfig();
    }

    // ---------- Bau der Insel ----------

    private void build() {
        Random random = new Random(42);

        for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
            for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= RADIUS) {
                    set(x, Y, z, floor(x, z, d));
                    // Unterseite: zur Mitte hin tiefer, wie eine schwebende Insel
                    int depth = Math.min(14, (int) ((RADIUS - d) * 0.7) + 1);
                    for (int k = 1; k <= depth; k++) {
                        Material m;
                        if (k == 1) m = Material.DIRT;
                        else if (k == depth && depth > 3) m = Material.DEEPSLATE;
                        else m = random.nextInt(5) == 0 ? Material.ANDESITE : Material.STONE;
                        set(x, Y - k, z, m);
                    }
                } else if (d <= RADIUS + 1) {
                    set(x, Y, z, Material.POLISHED_ANDESITE);
                    set(x, Y + 1, z, Material.SMOOTH_QUARTZ_SLAB);
                }
            }
        }

        // Laternen am Rand
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4 + Math.PI / 8;
            int px = (int) Math.round(Math.cos(angle) * (RADIUS - 1.5));
            int pz = (int) Math.round(Math.sin(angle) * (RADIUS - 1.5));
            for (int k = 1; k <= 3; k++) set(px, Y + k, pz, Material.QUARTZ_PILLAR);
            set(px, Y + 4, pz, Material.SEA_LANTERN);
        }

        // Launchpads auf den vier Wegen
        int[][] pads = {{15, 0}, {-15, 0}, {0, 15}, {0, -16}};
        for (int[] pad : pads) {
            set(pad[0], Y, pad[1], Material.GOLD_BLOCK);
            set(pad[0], Y + 1, pad[1], Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        }

        // Brunnen in der Mitte
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 4.2) continue;
                set(x, Y, z, Material.QUARTZ_BRICKS);
                if (d > 3) set(x, Y + 1, z, Material.QUARTZ_BRICKS);
            }
        }
        set(0, Y, 0, Material.SEA_LANTERN);
        for (int k = 1; k <= 3; k++) set(0, Y + k, 0, Material.QUARTZ_PILLAR);
        // Wasser zuletzt und mit Physik, damit es vom Brunnen herunterfließt
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if ((x != 0 || z != 0) && Math.sqrt(x * x + z * z) <= 3) {
                    world.getBlockAt(x, Y + 1, z).setType(Material.WATER, true);
                }
            }
        }
        world.getBlockAt(0, Y + 4, 0).setType(Material.WATER, true);
    }

    private static Material floor(int x, int z, double d) {
        if (Math.abs(x) <= 1 || Math.abs(z) <= 1) return Material.POLISHED_ANDESITE; // Wege
        int ring = (int) d;
        if (ring == 8 || ring == 16) return Material.CYAN_TERRACOTTA;
        int sector = (int) Math.floor((Math.atan2(z, x) + Math.PI) / (Math.PI / 8));
        return sector % 2 == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
    }

    private void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
