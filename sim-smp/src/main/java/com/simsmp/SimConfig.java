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
import java.util.Map.Entry;

public final class SimConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   public String currencySymbol = "₹";
   public String scoreboardTitle = "SIM SMP";
   public long startingBalance = 2000L;
   public int saveIntervalSeconds = 120;
   public int tradeRequestSeconds = 60;
   public int maxListingsPerPlayer = 10;
   public int marketTaxPercent = 0;
   public int dailyShopHours = 1;
   public int dailyShopSlots = 20;
   public long gachaPrice = 85L;
   public int classSwitchMinutes = 10;
   public long classChangeFee = 10000L;
   public int veinMinerMaxBlocks = 64;
   public int veinHarvesterMaxBlocks = 128;
   public int instantGrowCooldownSeconds = 300;
   public int instantGrowMaxBlocks = 256;
   public int instantTreeCooldownSeconds = 30;
   public transient Map<String, Long> prices = new LinkedHashMap<>();
   public transient Path dir;

   public SimConfig() {
   }

   public synchronized void savePrices() {
      if (this.dir != null) {
         try {
            Files.writeString(this.dir.resolve("prices.json"), GSON.toJson(this.prices));
         } catch (IOException var2) {
            SimSmp.LOGGER.error("Could not save prices.json", var2);
         }
      }
   }

   public static SimConfig load(Path dir) {
      SimConfig cfg = new SimConfig();

      try {
         Files.createDirectories(dir);
         Path cfgFile = dir.resolve("config.json");
         if (Files.exists(cfgFile)) {
            try (Reader r = Files.newBufferedReader(cfgFile)) {
               SimConfig read = (SimConfig)GSON.fromJson(r, SimConfig.class);
               if (read != null) {
                  cfg = read;
               }
            }
         }

         Files.writeString(cfgFile, GSON.toJson(cfg));
         Path priceFile = dir.resolve("prices.json");
         Map<String, Long> raw;
         if (Files.exists(priceFile)) {
            try (Reader rx = Files.newBufferedReader(priceFile)) {
               Type t = (new TypeToken<LinkedHashMap<String, Long>>() {}).getType();
               raw = (Map<String, Long>)GSON.fromJson(rx, t);
            }

            if (raw == null) {
               raw = defaultPrices();
            }
         } else {
            raw = defaultPrices();
            Files.writeString(priceFile, GSON.toJson(raw));
         }

         cfg.prices = new LinkedHashMap<>();

         for (Entry<String, Long> e : raw.entrySet()) {
            String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            cfg.prices.put(key, e.getValue());
         }
      } catch (RuntimeException | IOException var12) {
         SimSmp.LOGGER.error("Could not load SIM SMP config, using defaults", var12);
         cfg.prices = new LinkedHashMap<>();

         for (Entry<String, Long> e : defaultPrices().entrySet()) {
            cfg.prices.put("minecraft:" + e.getKey(), e.getValue());
         }
      }

      cfg.dir = dir;
      return cfg;
   }

   private static Map<String, Long> defaultPrices() {
      Map<String, Long> p = new LinkedHashMap<>();
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
      p.put("netherite_ingot", 1400L);
      p.put("obsidian", 20L);
      p.put("coal_block", 72L);
      p.put("copper_block", 90L);
      p.put("iron_block", 360L);
      p.put("gold_block", 900L);
      p.put("redstone_block", 90L);
      p.put("lapis_block", 108L);
      p.put("diamond_block", 1800L);
      p.put("emerald_block", 1080L);
      p.put("netherite_block", 12600L);

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
      p.put("bread", 20L);
      p.put("cookie", 6L);
      p.put("pumpkin_pie", 20L);
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
