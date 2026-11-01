package de.mcnetwork.lobby;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * Ein schwebender Text in zwei Sprachen: zwei TextDisplays an derselben Stelle,
 * jeder Spieler bekommt nur den in seiner Sprache zu sehen.
 * Nicht gespeichert – wird nach jedem Start neu erzeugt. Nur auf dem Hauptthread benutzen.
 */
final class DualText {

    private final Location location;
    private final float scale;
    private final boolean background;
    private TextDisplay de;
    private TextDisplay en;
    private Component textDe = Component.empty();
    private Component textEn = Component.empty();

    DualText(Location location, float scale, boolean background) {
        this.location = location.clone();
        this.scale = scale;
        this.background = background;
    }

    Location location() {
        return location.clone();
    }

    void text(Component german, Component english) {
        textDe = german;
        textEn = english;
        if (de != null && de.isValid()) de.text(german);
        if (en != null && en.isValid()) en.text(english);
    }

    /** Erzeugt fehlende Displays, sobald der Chunk geladen ist. */
    void ensureSpawned() {
        if (!location.isChunkLoaded()) return;
        if (de == null || !de.isValid()) de = spawn(textDe);
        if (en == null || !en.isValid()) en = spawn(textEn);
    }

    private TextDisplay spawn(Component text) {
        return location.getWorld().spawn(location, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setLineWidth(400);
            if (!background) d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            if (scale != 1f) {
                Transformation t = d.getTransformation();
                d.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(),
                        new Vector3f(scale, scale, scale), t.getRightRotation()));
            }
            d.text(text);
        });
    }

    /** Dem Spieler nur die Variante in seiner Sprache zeigen. */
    void apply(Player player, Plugin plugin) {
        boolean german = Lang.german(player.locale());
        TextDisplay show = german ? de : en;
        TextDisplay hide = german ? en : de;
        if (show != null && show.isValid()) player.showEntity(plugin, show);
        if (hide != null && hide.isValid()) player.hideEntity(plugin, hide);
    }

    void remove() {
        if (de != null) de.remove();
        if (en != null) en.remove();
        de = null;
        en = null;
    }
}
