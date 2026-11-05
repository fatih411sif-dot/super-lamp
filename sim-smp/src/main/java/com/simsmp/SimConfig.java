package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** config.json (general settings) and prices.json (sell prices per item). */
public final class SimConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String currencySymbol = "\u20B9"; // ₹
    public String scoreboardTitle = "SIM SMP";
    public long startingBalance = 2000;
    public int saveIntervalSeconds = 120;
    public int tradeRequestSeconds = 60;
    public int maxListingsPerPlayer = 10;
    /** Percent of each player-market sale taken by the "server" (0 = no fee). */
    public int marketTaxPercent = 0;
    /** Daily shop: how often it refreshes and how many items each player sees (max 25). */
    public int dailyShopHours = 5;
    public int dailyShopSlots = 20;

    /** item id (e.g. "minecraft:diamond") -> price per item. Loaded from prices.json. */
    public transient Map<String, Long> prices = new LinkedHashMap<>();
    public transient Path dir;

    /** Writes the current sell prices back to prices.json (used when an OP changes a price). */
    public synchronized void savePrices() {
        if (dir == null) return;
        try {
            Files.writeString(dir.resolve("prices.json"), GSON.toJson(prices));
        } catch (IOException e) {
            SimSmp.LOGGER.error("Could not save prices.json", e);
        }
    }

    public static SimConfig load(Path dir) {
        SimConfig cfg = new SimConfig();
        try {
            Files.createDirectories(dir);

            Path cfgFile = dir.resolve("config.json");
            if (Files.exists(cfgFile)) {
                try (Reader r = Files.newBufferedReader(cfgFile)) {
                    SimConfig read = GSON.fromJson(r, SimConfig.class);
                    if (read != null) cfg = read;
                }
            }
            Files.writeString(cfgFile, GSON.toJson(cfg)); // rewrite so new options show up

            Path priceFile = dir.resolve("prices.json");
            Map<String, Long> raw;
            if (Files.exists(priceFile)) {
                try (Reader r = Files.newBufferedReader(priceFile)) {
                    Type t = new TypeToken<LinkedHashMap<String, Long>>() {}.getType();
                    raw = GSON.fromJson(r, t);
                }
                if (raw == null) raw = defaultPrices();
            } else {
                raw = defaultPrices();
                Files.writeString(priceFile, GSON.toJson(raw));
            }

            cfg.prices = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : raw.entrySet()) {
                String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
                cfg.prices.put(key, e.getValue());
            }
        } catch (IOException | RuntimeException ex) {
            SimSmp.LOGGER.error("Could not load SIM SMP config, using defaults", ex);
            cfg.prices = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : defaultPrices().entrySet()) {
                cfg.prices.put("minecraft:" + e.getKey(), e.getValue());
            }
        }
        cfg.dir = dir;
        return cfg;
    }

    private static Map<String, Long> defaultPrices() {
        Map<String, Long> p = new LinkedHashMap<>();
        // Ores / minerals
        p.put("coal", 3L);
        p.put("charcoal", 2L);
        p.put("raw_copper", 2L);
        p.put("copper_ingot", 3L);
        p.put("raw_iron", 5L);
        p.put("iron_ingot", 7L);
        p.put("iron_nugget", 1L);
        p.put("raw_gold", 8L);
        p.put("gold_ingot", 11L);
        p.put("gold_nugget", 1L);
        p.put("redstone", 2L);
        p.put("lapis_lazuli", 3L);
        p.put("quartz", 3L);
        p.put("amethyst_shard", 4L);
        p.put("glowstone_dust", 3L);
        p.put("diamond", 60L);
        p.put("emerald", 45L);
        p.put("ancient_debris", 110L);
        p.put("netherite_scrap", 120L);
        p.put("netherite_ingot", 900L);
        p.put("obsidian", 4L);
        // Mineral blocks (9x)
        p.put("coal_block", 27L);
        p.put("copper_block", 27L);
        p.put("iron_block", 63L);
        p.put("gold_block", 99L);
        p.put("redstone_block", 18L);
        p.put("lapis_block", 27L);
        p.put("diamond_block", 540L);
        p.put("emerald_block", 405L);
        p.put("netherite_block", 8100L);
        // Logs
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"}) {
            p.put(wood + "_log", 2L);
        }
        // Farming
        p.put("wheat", 2L);
        p.put("carrot", 1L);
        p.put("potato", 1L);
        p.put("beetroot", 1L);
        p.put("sugar_cane", 1L);
        p.put("pumpkin", 2L);
        p.put("melon_slice", 1L);
        p.put("sweet_berries", 1L);
        p.put("cocoa_beans", 2L);
        p.put("nether_wart", 3L);
        p.put("kelp", 1L);
        p.put("bamboo", 1L);
        // Mob drops
        p.put("bone", 2L);
        p.put("string", 2L);
        p.put("gunpowder", 4L);
        p.put("spider_eye", 3L);
        p.put("rotten_flesh", 1L);
        p.put("leather", 3L);
        p.put("feather", 1L);
        p.put("ink_sac", 3L);
        p.put("slime_ball", 6L);
        p.put("prismarine_shard", 4L);
        p.put("ender_pearl", 20L);
        p.put("blaze_rod", 15L);
        p.put("ghast_tear", 40L);
        p.put("phantom_membrane", 25L);
        p.put("nautilus_shell", 80L);
        p.put("shulker_shell", 100L);
        p.put("nether_star", 2500L);
        return p;
    }
}
