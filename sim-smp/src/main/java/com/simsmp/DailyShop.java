package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class DailyShop {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   public static final int MAX_SLOTS = 25;
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final Path poolFile;
   private final Path stateFile;
   private List<DailyShop.Entry> pool = new ArrayList<>();
   private List<DailyShop.Entry> valid;
   private long statePeriod = -1L;
   private final Set<String> sold = new HashSet<>();
   private boolean dirty;
   private static final String SHOP_TAG = "sim_smp_shop";

   public DailyShop(SimConfig cfg, EconomyManager eco, Path dir) {
      this.cfg = cfg;
      this.eco = eco;
      this.poolFile = dir.resolve("dailyshop.json");
      this.stateFile = dir.resolve("dailyshop_state.json");
   }

   public void load() {
      try {
         if (Files.exists(this.poolFile)) {
            try (Reader r = Files.newBufferedReader(this.poolFile)) {
               Type t = (new TypeToken<ArrayList<DailyShop.Entry>>() {
                  {
                     Objects.requireNonNull(DailyShop.this);
                  }
               }).getType();
               List<DailyShop.Entry> read = (List<DailyShop.Entry>)GSON.fromJson(r, t);
               if (read != null) {
                  this.pool = read;
               }
            }
         } else {
            this.pool = defaultPool();
            Files.createDirectories(this.poolFile.getParent());
            Files.writeString(this.poolFile, GSON.toJson(this.pool));
         }
      } catch (RuntimeException | IOException var9) {
         SimSmp.LOGGER.error("Could not read dailyshop.json, using the default item pool", var9);
         this.pool = defaultPool();
      }

      if (Files.exists(this.stateFile)) {
         try (Reader rx = Files.newBufferedReader(this.stateFile)) {
            DailyShop.State s = (DailyShop.State)GSON.fromJson(rx, DailyShop.State.class);
            if (s != null && s.period == this.currentPeriod()) {
               this.statePeriod = s.period;
               if (s.sold != null) {
                  this.sold.addAll(s.sold);
               }
            }
         } catch (RuntimeException | IOException var7) {
            SimSmp.LOGGER.warn("dailyshop_state.json is unreadable; purchases of this period are forgotten", var7);
         }
      }
   }

   public synchronized void save() {
      if (this.dirty) {
         this.dirty = false;
         DailyShop.State s = new DailyShop.State();
         s.period = this.statePeriod;
         s.sold.addAll(this.sold);

         try {
            Files.createDirectories(this.stateFile.getParent());
            Path tmp = this.stateFile.resolveSibling("dailyshop_state.json.tmp");
            Files.writeString(tmp, GSON.toJson(s));

            try {
               Files.move(tmp, this.stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var4) {
               Files.move(tmp, this.stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException var5) {
            this.dirty = true;
            SimSmp.LOGGER.error("Could not save dailyshop_state.json", var5);
         }
      }
   }

   private long intervalMillis() {
      return Math.max(1, this.cfg.dailyShopHours) * 3600000L;
   }

   private long currentPeriod() {
      return System.currentTimeMillis() / this.intervalMillis();
   }

   public long millisUntilRefresh() {
      long interval = this.intervalMillis();
      return interval - System.currentTimeMillis() % interval;
   }

   private void rollover() {
      long period = this.currentPeriod();
      if (period != this.statePeriod) {
         this.statePeriod = period;
         this.sold.clear();
         this.dirty = true;
      }
   }

   private static Item resolve(String id) {
      return ItemIds.find(id);
   }

   private synchronized List<DailyShop.Entry> validEntries() {
      if (this.valid == null) {
         this.valid = new ArrayList<>();
         Set<String> seen = new HashSet<>();

         for (DailyShop.Entry e : this.pool) {
            if (e != null && e.chance > 0 && e.price >= 0L) {
               if (resolve(e.item) == null) {
                  SimSmp.LOGGER.warn("dailyshop.json: unknown item '{}' skipped", e.item);
               } else {
                  String key = ItemIds.normalize(e.item);
                  if (seen.add(key)) {
                     this.valid.add(e);
                  }
               }
            }
         }
      }

      return this.valid;
   }

   public synchronized List<DailyShop.Offer> offers() {
      this.rollover();
      List<DailyShop.Entry> candidates = new ArrayList<>(this.validEntries());
      int slots = Math.max(1, Math.min(25, this.cfg.dailyShopSlots));
      long seed = this.statePeriod * -4417276706812531889L + -7046029254386353131L;
      Random random = new Random(seed);
      List<DailyShop.Offer> out = new ArrayList<>();

      while (out.size() < slots && !candidates.isEmpty()) {
         long total = 0L;

         for (DailyShop.Entry e : candidates) {
            total += e.chance;
         }

         long roll = (long)(random.nextDouble() * total);
         DailyShop.Entry picked = candidates.get(candidates.size() - 1);
         Iterator item = candidates.iterator();

         while (true) {
            if (item.hasNext()) {
               DailyShop.Entry e = (DailyShop.Entry)item.next();
               roll -= e.chance;
               if (roll >= 0L) {
                  continue;
               }

               picked = e;
            }

            candidates.remove(picked);
            Item itemx = resolve(picked.item);
            ItemStack stack = new ItemStack(itemx, 1);
            int amount = Math.max(1, Math.min(picked.amount, stack.getMaxStackSize()));
            stack.setCount(amount);
            out.add(new DailyShop.Offer(ItemIds.normalize(picked.item), stack, picked.price));
            break;
         }
      }

      return out;
   }

   static void markShopItem(ItemStack stack) {
      CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("sim_smp_shop", true));
   }

   static boolean isShopItem(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().contains("sim_smp_shop");
   }

   public synchronized boolean isSold(String id) {
      this.rollover();
      return this.sold.contains(id);
   }

   public synchronized boolean buy(ServerPlayer p, String id) {
      DailyShop.Offer offer = null;

      for (DailyShop.Offer o : this.offers()) {
         if (o.id().equals(id)) {
            offer = o;
            break;
         }
      }

      if (offer == null) {
         Msg.err(p, "That offer is gone - the shop has refreshed.");
         return false;
      } else if (this.sold.contains(id)) {
         Msg.err(p, "Too late - someone else already bought that.");
         return false;
      } else if (!this.eco.take(p.getUUID(), offer.price())) {
         Msg.err(p, "You need " + this.eco.fmt(offer.price()) + " but only have " + this.eco.fmt(this.eco.get(p.getUUID())) + ".");
         return false;
      } else {
         this.sold.add(id);
         this.dirty = true;
         ItemStack given = offer.stack().copy();
         markShopItem(given);
         ItemGiver.give(p, given);
         this.save();
         Msg.ok(p, "You bought " + offer.stack().getCount() + "x " + offer.stack().getHoverName().getString() + " for " + this.eco.fmt(offer.price()) + ".");
         return true;
      }
   }

   private static DailyShop.Entry e(String item, int amount, long price, int chance) {
      DailyShop.Entry x = new DailyShop.Entry();
      x.item = "minecraft:" + item;
      x.amount = amount;
      x.price = price;
      x.chance = chance;
      return x;
   }

   private static List<DailyShop.Entry> defaultPool() {
      List<DailyShop.Entry> p = new ArrayList<>();
      p.add(e("bread", 16, 480L, 100));
      p.add(e("cooked_beef", 16, 480L, 100));
      p.add(e("cooked_porkchop", 16, 480L, 100));
      p.add(e("torch", 32, 30L, 100));
      p.add(e("oak_log", 32, 480L, 100));
      p.add(e("oak_planks", 64, 192L, 100));
      p.add(e("cobblestone", 64, 80L, 100));
      p.add(e("sand", 64, 80L, 100));
      p.add(e("glass", 32, 60L, 100));
      p.add(e("coal", 32, 384L, 100));
      p.add(e("arrow", 32, 90L, 100));
      p.add(e("string", 16, 60L, 100));
      p.add(e("bone", 16, 50L, 100));
      p.add(e("leather", 8, 180L, 100));
      p.add(e("sugar_cane", 32, 144L, 100));
      p.add(e("wheat", 32, 336L, 100));
      p.add(e("carrot", 32, 240L, 100));
      p.add(e("potato", 32, 240L, 100));
      p.add(e("apple", 8, 96L, 100));
      p.add(e("cookie", 16, 144L, 100));
      p.add(e("book", 4, 60L, 100));
      p.add(e("iron_ingot", 16, 960L, 40));
      p.add(e("copper_ingot", 32, 480L, 40));
      p.add(e("gold_ingot", 8, 1200L, 40));
      p.add(e("redstone", 32, 480L, 40));
      p.add(e("lapis_lazuli", 16, 288L, 40));
      p.add(e("quartz", 16, 288L, 40));
      p.add(e("gunpowder", 8, 80L, 40));
      p.add(e("slime_ball", 8, 120L, 40));
      p.add(e("ender_pearl", 4, 160L, 40));
      p.add(e("blaze_rod", 4, 110L, 40));
      p.add(e("experience_bottle", 8, 240L, 40));
      p.add(e("golden_carrot", 16, 150L, 40));
      p.add(e("golden_apple", 1, 200L, 40));
      p.add(e("obsidian", 8, 240L, 40));
      p.add(e("glowstone", 16, 120L, 40));
      p.add(e("sea_lantern", 4, 100L, 40));
      p.add(e("honey_bottle", 4, 90L, 40));
      p.add(e("cake", 1, 60L, 40));
      p.add(e("bucket", 1, 90L, 40));
      p.add(e("hopper", 1, 140L, 40));
      p.add(e("name_tag", 1, 180L, 40));
      p.add(e("saddle", 1, 200L, 40));
      p.add(e("shield", 1, 150L, 40));
      p.add(e("bow", 1, 120L, 40));
      p.add(e("crossbow", 1, 160L, 40));
      p.add(e("iron_sword", 1, 160L, 40));
      p.add(e("iron_pickaxe", 1, 220L, 40));
      p.add(e("iron_chestplate", 1, 330L, 40));
      p.add(e("diamond", 2, 600L, 10));
      p.add(e("emerald", 4, 720L, 10));
      p.add(e("diamond_sword", 1, 650L, 10));
      p.add(e("diamond_pickaxe", 1, 700L, 10));
      p.add(e("diamond_axe", 1, 700L, 10));
      p.add(e("diamond_shovel", 1, 300L, 10));
      p.add(e("diamond_helmet", 1, 700L, 10));
      p.add(e("diamond_chestplate", 1, 1100L, 10));
      p.add(e("diamond_leggings", 1, 1000L, 10));
      p.add(e("diamond_boots", 1, 600L, 10));
      p.add(e("ancient_debris", 1, 360L, 10));
      p.add(e("netherite_scrap", 1, 375L, 10));
      p.add(e("shulker_shell", 2, 500L, 10));
      p.add(e("nautilus_shell", 1, 200L, 10));
      p.add(e("heart_of_the_sea", 1, 800L, 10));
      p.add(e("trident", 1, 900L, 10));
      p.add(e("ender_chest", 1, 350L, 10));
      p.add(e("enchanting_table", 1, 450L, 10));
      p.add(e("anvil", 1, 250L, 10));
      p.add(e("lodestone", 1, 600L, 10));
      p.add(e("respawn_anchor", 1, 550L, 10));
      p.add(e("crying_obsidian", 4, 250L, 10));
      p.add(e("tnt", 8, 280L, 10));
      p.add(e("wind_charge", 16, 400L, 10));
      p.add(e("breeze_rod", 4, 380L, 10));
      p.add(e("netherite_ingot", 1, 2250L, 3));
      p.add(e("totem_of_undying", 1, 1800L, 3));
      p.add(e("elytra", 1, 4500L, 3));
      p.add(e("enchanted_golden_apple", 1, 2000L, 3));
      p.add(e("beacon", 1, 3500L, 3));
      p.add(e("nether_star", 1, 6000L, 3));
      p.add(e("netherite_upgrade_smithing_template", 1, 1500L, 3));
      p.add(e("music_disc_pigstep", 1, 1200L, 3));
      p.add(e("heavy_core", 1, 2000L, 3));
      p.add(e("mace", 1, 3000L, 3));
      return p;
   }

   public static final class Entry {
      public String item;
      public int amount = 1;
      public long price = 100L;
      public int chance = 10;

      public Entry() {
      }
   }

   public record Offer(String id, ItemStack stack, long price) {
   }

   private static final class State {
      long period = -1L;
      List<String> sold = new ArrayList<>();

      private State() {
      }
   }
}
