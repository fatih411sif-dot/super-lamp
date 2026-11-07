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
    public int dailyShopHours = 1;
    public int dailyShopSlots = 20;
    /** Price of one /gacha pull. */
    public long gachaPrice = 85;
    /** Classes: minutes before a player may switch their equipped class again. */
    public int classSwitchMinutes = 10;
    /** Classes: money fee to replace one of the 4 chosen classes (the replaced class goes back to level 0). */
    public long classChangeFee = 10_000;
    /** Miner's Vein Miner ability: the most blocks it can break in one go. */
    public int veinMinerMaxBlocks = 64;
    /** Farmer's Vein Harvester: the most crops it can harvest in one go. */
    public int veinHarvesterMaxBlocks = 128;
    /** Farmer's Instant Grow: seconds between uses, and the most crops one use can grow. */
    public int instantGrowCooldownSeconds = 300;
    public int instantGrowMaxBlocks = 256;
    /** Lumberjack's Instant Tree Growth: seconds between uses (it grows one sapling per use). */
    public int instantTreeCooldownSeconds = 30;
    /** Archer's Ultimate Range: arrow speed multiplier (1.5 = 50% faster, so it flies further). */
    public double archerRangeMultiplier = 1.5;
    /** Combat classes: killing the same player again gives Class XP only after this many minutes (0 = no limit). */
    public int playerKillXpCooldownMinutes = 10;
    /** Berserker's Parry: seconds between uses. */
    public int parryCooldownSeconds = 30;
    /** Berserker's Parry takes half of the target's health, but never more than this (so bosses are not halved). */
    public double parryMaxDamage = 40;

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
        // Rule: a block is exactly 9x its item, and crafting something (planks, bread) is never worth
        // more than its ingredients, so nobody can profit by converting items back and forth.
        // Smelting (ingot vs raw ore, cooked vs raw food) is worth a little more on purpose.
        p.put("coal", 8L);
        p.put("charcoal", 6L);
        p.put("raw_copper", 8L);
        p.put("copper_ingot", 10L);
        p.put("raw_iron", 35L);
        p.put("iron_ingot", 40L);
        p.put("iron_nugget", 4L);
        p.put("raw_gold", 90L);
        p.put("gold_ingot", 100L);
        p.put("gold_nugget", 11L);
        p.put("redstone", 10L);
        p.put("lapis_lazuli", 12L);
        p.put("quartz", 12L);
        p.put("amethyst_shard", 15L);
        p.put("glowstone_dust", 12L);
        p.put("diamond", 200L);
        p.put("emerald", 120L);
        p.put("ancient_debris", 240L);
        p.put("netherite_scrap", 250L);
        p.put("netherite_ingot", 1400L); // 4 scrap + 4 gold ingots
        p.put("obsidian", 20L);
        // Mineral blocks (9x)
        p.put("coal_block", 72L);
        p.put("copper_block", 90L);
        p.put("iron_block", 360L);
        p.put("gold_block", 900L);
        p.put("redstone_block", 90L);
        p.put("lapis_block", 108L);
        p.put("diamond_block", 1800L);
        p.put("emerald_block", 1080L);
        p.put("netherite_block", 12600L);
        // Wood: logs, planks, leaves and saplings (1 log = 4 planks, so planks stay under 1/4 of a log)
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"}) {
            p.put(wood + "_log", 10L);
            p.put(wood + "_wood", 10L);
            p.put(wood + "_planks", 2L);
            p.put(wood + "_leaves", 2L);
        }
        for (String tree : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "pale_oak"}) {
            p.put(tree + "_sapling", 5L);
        }
        p.put("mangrove_propagule", 5L);
        p.put("azalea_leaves", 2L);
        p.put("flowering_azalea_leaves", 2L);
        p.put("crimson_stem", 10L);
        p.put("warped_stem", 10L);
        p.put("crimson_planks", 2L);
        p.put("warped_planks", 2L);
        // Farming, fruit and vegetables
        p.put("wheat", 7L);
        p.put("carrot", 5L);
        p.put("potato", 5L);
        p.put("baked_potato", 8L);
        p.put("poisonous_potato", 2L);
        p.put("beetroot", 4L);
        p.put("sugar_cane", 3L);
        p.put("sugar", 4L);
        p.put("pumpkin", 8L);
        p.put("melon_slice", 3L);
        p.put("apple", 8L);
        p.put("sweet_berries", 4L);
        p.put("glow_berries", 5L);
        p.put("chorus_fruit", 10L);
        p.put("cocoa_beans", 6L);
        p.put("nether_wart", 6L);
        p.put("kelp", 3L);
        p.put("dried_kelp", 4L);
        p.put("bamboo", 2L);
        p.put("egg", 3L);
        p.put("honey_bottle", 15L);
        // Cooked and baked food
        p.put("bread", 20L);
        p.put("cookie", 6L);
        p.put("pumpkin_pie", 20L);
        // Meat and fish (cooked is worth more than raw)
        p.put("beef", 10L);
        p.put("porkchop", 10L);
        p.put("mutton", 8L);
        p.put("chicken", 8L);
        p.put("rabbit", 8L);
        p.put("cod", 6L);
        p.put("salmon", 8L);
        p.put("tropical_fish", 15L);
        p.put("pufferfish", 15L);
        p.put("cooked_beef", 20L);
        p.put("cooked_porkchop", 20L);
        p.put("cooked_mutton", 16L);
        p.put("cooked_chicken", 16L);
        p.put("cooked_rabbit", 16L);
        p.put("cooked_cod", 10L);
        p.put("cooked_salmon", 12L);
        // Mob drops
        p.put("bone", 2L);
        p.put("string", 2L);
        p.put("gunpowder", 4L);
        p.put("spider_eye", 3L);
        p.put("rotten_flesh", 1L);
        p.put("leather", 15L);
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
