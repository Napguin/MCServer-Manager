package de.mcnetwork.chat;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Deutsch für Spieler mit deutschem Minecraft, sonst Englisch. */
final class Lang {

    private Lang() {
    }

    static boolean german(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage());
    }

    static boolean german(CommandSender sender) {
        return !(sender instanceof Player player) || german(player.locale());
    }

    static String t(CommandSender sender, String de, String en) {
        return german(sender) ? de : en;
    }
}
