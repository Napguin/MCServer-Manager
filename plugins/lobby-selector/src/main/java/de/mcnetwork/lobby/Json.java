package de.mcnetwork.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Kleine Helfer zum Lesen und Schreiben der Lobby-Extras (JSON vom Dashboard). */
final class Json {

    private Json() {
    }

    static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    static double num(JsonObject o, String key, double fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Position lesen; null, wenn sie fehlt oder die Welt nicht geladen ist. */
    static Location loc(JsonObject o) {
        if (o == null) return null;
        World world = Bukkit.getWorld(str(o, "world", ""));
        if (world == null) return null;
        return new Location(world, num(o, "x", 0), num(o, "y", 0), num(o, "z", 0),
                (float) num(o, "yaw", 0), (float) num(o, "pitch", 0));
    }

    static JsonObject loc(Location l) {
        JsonObject o = new JsonObject();
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", round(l.getX()));
        o.addProperty("y", round(l.getY()));
        o.addProperty("z", round(l.getZ()));
        o.addProperty("yaw", round(l.getYaw()));
        o.addProperty("pitch", round(l.getPitch()));
        return o;
    }

    /** Blockposition (Mitte des Blocks, ohne Blickrichtung). */
    static JsonObject block(Location l) {
        JsonObject o = new JsonObject();
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", l.getBlockX());
        o.addProperty("y", l.getBlockY());
        o.addProperty("z", l.getBlockZ());
        return o;
    }

    static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
