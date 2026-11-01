package de.mcnetwork.lobby;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/** Alle Cosmetics. Die IDs ("kategorie.name") stehen genauso im Dashboard (extras.js). */
enum Cosmetic {

    // Partikel-Spuren
    TRAIL_HEARTS(Category.TRAIL, "hearts", "Herzen", "Hearts", Material.RED_DYE),
    TRAIL_FLAMES(Category.TRAIL, "flames", "Flammen", "Flames", Material.BLAZE_POWDER),
    TRAIL_NOTES(Category.TRAIL, "notes", "Noten", "Notes", Material.NOTE_BLOCK),
    TRAIL_CLOUDS(Category.TRAIL, "clouds", "Wolken", "Clouds", Material.WHITE_WOOL),
    TRAIL_SNOW(Category.TRAIL, "snow", "Schnee", "Snow", Material.SNOWBALL),
    TRAIL_SPARKLE(Category.TRAIL, "sparkle", "Glitzer", "Sparkle", Material.END_ROD),
    TRAIL_MAGIC(Category.TRAIL, "magic", "Magie", "Magic", Material.ENCHANTING_TABLE),
    TRAIL_RAINBOW(Category.TRAIL, "rainbow", "Regenbogen", "Rainbow", Material.PRISMARINE_CRYSTALS),
    TRAIL_SOULS(Category.TRAIL, "souls", "Seelenfeuer", "Soul fire", Material.SOUL_LANTERN),
    TRAIL_CHERRY(Category.TRAIL, "cherry", "Kirschblüten", "Cherry blossoms", Material.CHERRY_LEAVES),

    // Hüte (Blöcke auf dem Kopf)
    HAT_GLASS(Category.HAT, "glass", "Astronaut", "Astronaut", Material.GLASS),
    HAT_TNT(Category.HAT, "tnt", "TNT", "TNT", Material.TNT),
    HAT_MELON(Category.HAT, "melon", "Melone", "Melon", Material.MELON),
    HAT_DIAMOND(Category.HAT, "diamond", "Diamantkopf", "Diamond head", Material.DIAMOND_BLOCK),
    HAT_GOLD(Category.HAT, "gold", "Goldkopf", "Gold head", Material.GOLD_BLOCK),
    HAT_SLIME(Category.HAT, "slime", "Schleim", "Slime", Material.SLIME_BLOCK),
    HAT_HAY(Category.HAT, "hay", "Strohhut", "Straw hat", Material.HAY_BLOCK),
    HAT_BEACON(Category.HAT, "beacon", "Leuchtfeuer", "Beacon", Material.BEACON),
    HAT_LANTERN(Category.HAT, "lantern", "Kürbislaterne", "Jack o'Lantern", Material.JACK_O_LANTERN),
    HAT_ICE(Category.HAT, "ice", "Eiskopf", "Ice head", Material.ICE),

    // Gadgets (Item zum Rechtsklicken)
    GADGET_FIREWORK(Category.GADGET, "firework", "Feuerwerk", "Firework", Material.FIREWORK_ROCKET),
    GADGET_GRAPPLE(Category.GADGET, "grapple", "Enterhaken", "Grappling hook", Material.FISHING_ROD),
    GADGET_PAINTBALL(Category.GADGET, "paintball", "Paintball", "Paintball", Material.SNOWBALL),
    GADGET_CONFETTI(Category.GADGET, "confetti", "Konfetti-Kanone", "Confetti cannon", Material.PINK_DYE),
    GADGET_ROCKET(Category.GADGET, "rocket", "Raketenstiefel", "Rocket boots", Material.FIRE_CHARGE),
    GADGET_PEARL(Category.GADGET, "pearl", "Enderperle", "Ender pearl", Material.ENDER_PEARL),

    // Haustiere, die einem folgen
    PET_DOG(Category.PET, "dog", "Hund", "Dog", Material.BONE),
    PET_CAT(Category.PET, "cat", "Katze", "Cat", Material.COD),
    PET_FOX(Category.PET, "fox", "Fuchs", "Fox", Material.SWEET_BERRIES),
    PET_RABBIT(Category.PET, "rabbit", "Hase", "Rabbit", Material.CARROT),
    PET_PIG(Category.PET, "pig", "Schwein", "Pig", Material.PORKCHOP),
    PET_CHICKEN(Category.PET, "chicken", "Huhn", "Chicken", Material.FEATHER),
    PET_BEE(Category.PET, "bee", "Biene", "Bee", Material.HONEYCOMB),
    PET_AXOLOTL(Category.PET, "axolotl", "Axolotl", "Axolotl", Material.AXOLOTL_BUCKET);

    enum Category {
        TRAIL("trail", "Partikel", "Particles", Material.BLAZE_POWDER),
        HAT("hat", "Hüte", "Hats", Material.GOLDEN_HELMET),
        GADGET("gadget", "Gadgets", "Gadgets", Material.FIREWORK_ROCKET),
        PET("pet", "Haustiere", "Pets", Material.BONE);

        final String id;
        final String de;
        final String en;
        final Material icon;

        Category(String id, String de, String en, Material icon) {
            this.id = id;
            this.de = de;
            this.en = en;
            this.icon = icon;
        }

        String name(boolean german) {
            return german ? de : en;
        }
    }

    final Category category;
    final String shortId;
    final String de;
    final String en;
    final Material icon;

    Cosmetic(Category category, String shortId, String de, String en, Material icon) {
        this.category = category;
        this.shortId = shortId;
        this.de = de;
        this.en = en;
        this.icon = icon;
    }

    String key() {
        return category.id + "." + shortId;
    }

    String name(boolean german) {
        return german ? de : en;
    }

    static Cosmetic byKey(String key) {
        for (Cosmetic c : values()) if (c.key().equals(key)) return c;
        return null;
    }

    EntityType petType() {
        return switch (this) {
            case PET_DOG -> EntityType.WOLF;
            case PET_CAT -> EntityType.CAT;
            case PET_FOX -> EntityType.FOX;
            case PET_RABBIT -> EntityType.RABBIT;
            case PET_PIG -> EntityType.PIG;
            case PET_CHICKEN -> EntityType.CHICKEN;
            case PET_BEE -> EntityType.BEE;
            case PET_AXOLOTL -> EntityType.AXOLOTL;
            default -> null;
        };
    }
}
