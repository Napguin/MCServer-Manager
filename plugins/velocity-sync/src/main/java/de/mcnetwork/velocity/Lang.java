package de.mcnetwork.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;

import java.util.Locale;

/** Deutsch für Spieler mit deutschem Minecraft, sonst Englisch. */
final class Lang {

    private Lang() {
    }

    /** null = Sprache noch unbekannt (z. B. beim Login). */
    static Boolean germanOrNull(Player player) {
        Locale locale = player.getEffectiveLocale();
        if (locale == null && player.hasSentPlayerSettings()) locale = player.getPlayerSettings().getLocale();
        return locale == null ? null : "de".equals(locale.getLanguage());
    }

    static boolean german(Player player) {
        return Boolean.TRUE.equals(germanOrNull(player));
    }

    static String t(Player player, String de, String en) {
        return german(player) ? de : en;
    }

    /** Text aus den Dashboard-Einstellungen: "<key>_en" für Englisch, sonst der deutsche. */
    static String text(JsonObject settings, String key, String fallback, boolean german) {
        if (!german) {
            String en = VelocitySync.str(settings, key + "_en", "");
            if (!en.isBlank()) return en;
        }
        return VelocitySync.str(settings, key, fallback);
    }
}
