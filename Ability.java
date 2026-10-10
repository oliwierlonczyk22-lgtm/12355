package dev.gems;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Wszystkie kryształy. Cooldown w sekundach: minimum 30, maksimum 60.
 */
public enum Ability {
    // ---- ruch ----
    DASH("Dash", NamedTextColor.AQUA, Material.DIAMOND, 30, "Szybki zryw do przodu."),
    DOUBLE_JUMP("Podwójny skok", NamedTextColor.WHITE, Material.QUARTZ, 30, "Wybijasz się wysoko w powietrze."),
    BLINK("Teleport", NamedTextColor.LIGHT_PURPLE, Material.PRISMARINE_CRYSTALS, 30, "Natychmiastowy skok o kilka bloków do przodu."),
    PEARL("Perła Endermana", NamedTextColor.DARK_PURPLE, Material.AMETHYST_SHARD, 35, "Rzucasz perłę i się teleportujesz."),
    ROCKET("Rakieta", NamedTextColor.RED, Material.FIREWORK_STAR, 40, "Wystrzeliwujesz się wysoko w górę (z wolnym opadaniem)."),
    SWAP("Zamiana miejsc", NamedTextColor.DARK_PURPLE, Material.RABBIT_FOOT, 40, "Zamieniasz się miejscami z celem, na który patrzysz."),
    SPEED("Szybkość", NamedTextColor.GREEN, Material.EMERALD, 55, "Duży przyrost prędkości na kilka sekund."),

    // ---- pociski / dystans ----
    WIND_CHARGE("Wind Charge", NamedTextColor.DARK_GREEN, Material.PRISMARINE_SHARD, 30, "Wystrzeliwujesz ładunek wiatru."),
    ARROW_VOLLEY("Grad strzał", NamedTextColor.GRAY, Material.SPECTRAL_ARROW, 35, "Wachlarz strzał przed tobą."),
    WITHER_SKULL("Czaszka Withera", NamedTextColor.DARK_GRAY, Material.WITHER_SKELETON_SKULL, 40, "Wystrzeliwujesz czaszkę, która wybucha i zatruwa witherem."),
    FIREBALL("Kula ognia", NamedTextColor.GOLD, Material.BLAZE_POWDER, 45, "Kula ognia wybuchająca jak TNT."),
    SONIC_BOOM("Sonic Boom (Warden)", NamedTextColor.DARK_AQUA, Material.ECHO_SHARD, 45, "Fala dźwiękowa rani wszystko przed tobą."),
    DRAGON_BREATH("Smoczy oddech", NamedTextColor.LIGHT_PURPLE, Material.DRAGON_BREATH, 45, "Stożek ognia przed tobą podpala wrogów."),
    LIGHTNING("Piorun", NamedTextColor.YELLOW, Material.GLOWSTONE_DUST, 50, "Piorun uderza tam, gdzie patrzysz."),
    CHAIN_LIGHTNING("Błyskawica łańcuchowa", NamedTextColor.YELLOW, Material.REDSTONE, 55, "Prąd przeskakuje między wrogami (wymaga celu)."),
    METEOR("Meteoryt", NamedTextColor.GOLD, Material.FIRE_CHARGE, 60, "Z nieba spada meteoryt i potężnie wybucha tam, gdzie patrzysz."),

    // ---- strefa wokół ciebie ----
    SHOCKWAVE("Odepchnięcie", NamedTextColor.GREEN, Material.SLIME_BALL, 35, "Mocno odrzuca wszystkich wokół ciebie."),
    SLAM("Uderzenie w ziemię", NamedTextColor.GRAY, Material.IRON_NUGGET, 40, "Fala uderzeniowa wokół ciebie."),
    COBWEB("Pajęcza sieć", NamedTextColor.WHITE, Material.STRING, 40, "Oplątuje pobliskich wrogów pajęczyną."),
    DARKNESS("Ciemność", NamedTextColor.DARK_GRAY, Material.INK_SAC, 40, "Oślepia wrogów wokół ciebie."),
    VORTEX("Wir", NamedTextColor.DARK_BLUE, Material.NAUTILUS_SHELL, 45, "Przyciąga pobliskich wrogów do ciebie."),
    TORNADO("Tornado", NamedTextColor.WHITE, Material.FEATHER, 45, "Unosi wrogów wysoko w powietrze."),
    INFERNO("Inferno", NamedTextColor.GOLD, Material.MAGMA_CREAM, 45, "Podpala wszystkich wokół ciebie."),
    RADAR("Radar", NamedTextColor.DARK_GREEN, Material.ENDER_EYE, 45, "Podświetla wszystkich w promieniu 30 bloków."),
    FREEZE("Zamrożenie", NamedTextColor.BLUE, Material.LAPIS_LAZULI, 50, "Zamraża i spowalnia wrogów wokół ciebie."),
    STUN("Ogłuszenie", NamedTextColor.YELLOW, Material.FLINT, 50, "Na chwilę unieruchamia wrogów wokół ciebie."),
    WITHER_ROT("Zgnilizna", NamedTextColor.DARK_GRAY, Material.WITHER_ROSE, 50, "Wrogowie wokół ciebie dostają efekt withera."),
    LIFESTEAL("Wampiryzm", NamedTextColor.DARK_RED, Material.FERMENTED_SPIDER_EYE, 50, "Wysysasz życie pobliskim wrogom (wymaga celu)."),
    EARTHQUAKE("Trzęsienie ziemi", NamedTextColor.GRAY, Material.NETHERITE_SCRAP, 60, "Kilka fal uderzeniowych pod rząd wokół ciebie."),

    // ---- wsparcie ----
    HEAL("Leczenie", NamedTextColor.RED, Material.GHAST_TEAR, 60, "Odnawia zdrowie i daje regenerację."),
    SHIELD("Tarcza", NamedTextColor.GOLD, Material.GOLD_NUGGET, 60, "Absorpcja i odporność na krótki czas."),
    BERSERK("Szał bojowy", NamedTextColor.DARK_RED, Material.BLAZE_ROD, 60, "Siła II i szybkość na kilka sekund."),
    INVISIBILITY("Niewidzialność", NamedTextColor.DARK_GRAY, Material.COPPER_INGOT, 60, "Znikasz na kilka sekund.");

    public final String displayName;
    public final NamedTextColor color;
    public final Material material;
    public final int cooldown;
    public final String description;

    Ability(String displayName, NamedTextColor color, Material material, int cooldown, String description) {
        this.displayName = displayName;
        this.color = color;
        this.material = material;
        // zabezpieczenie: cooldown zawsze w przedziale 30-60 s
        this.cooldown = Math.max(30, Math.min(60, cooldown));
        this.description = description;
    }
}
