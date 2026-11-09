package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Gacha {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private final Path file;
   private List<Gacha.Entry> pool = new ArrayList<>();
   private List<Gacha.Slot> slots;

   public Gacha(Path file) {
      this.file = file;
   }

   public void load() {
      try {
         if (Files.exists(this.file)) {
            try (Reader r = Files.newBufferedReader(this.file)) {
               Type t = (new TypeToken<ArrayList<Gacha.Entry>>() {
                  {
                     Objects.requireNonNull(Gacha.this);
                  }
               }).getType();
               List<Gacha.Entry> read = (List<Gacha.Entry>)GSON.fromJson(r, t);
               if (read != null) {
                  this.pool = read;
               }
            }
         } else {
            this.pool = defaultPool();
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(this.pool));
         }
      } catch (RuntimeException | IOException var6) {
         SimSmp.LOGGER.error("Could not read gacha.json, using the default prize pool", var6);
         this.pool = defaultPool();
      }
   }

   private synchronized List<Gacha.Slot> slots() {
      if (this.slots == null) {
         List<Gacha.Slot> out = new ArrayList<>();

         for (Gacha.Entry e : this.pool) {
            if (e != null && e.chance > 0) {
               Item item = ItemIds.find(e.item);
               if (item == null) {
                  SimSmp.LOGGER.warn("gacha.json: unknown item '{}' skipped", e.item);
               } else {
                  int max = new ItemStack(item).getMaxStackSize();
                  out.add(new Gacha.Slot(item, Math.max(1, Math.min(e.amount, max)), e.chance, Gacha.Rarity.parse(e.rarity)));
               }
            }
         }

         this.slots = out;
      }

      return this.slots;
   }

   public boolean isEmpty() {
      return this.slots().isEmpty();
   }

   public Gacha.Prize roll() {
      List<Gacha.Slot> s = this.slots();
      if (s.isEmpty()) {
         return null;
      } else {
         long total = 0L;

         for (Gacha.Slot x : s) {
            total += x.chance();
         }

         long r = ThreadLocalRandom.current().nextLong(total);

         for (Gacha.Slot x : s) {
            r -= x.chance();
            if (r < 0L) {
               return prize(x);
            }
         }

         return prize(s.get(s.size() - 1));
      }
   }

   public ItemStack randomIcon() {
      List<Gacha.Slot> s = this.slots();
      Gacha.Slot x = s.get(ThreadLocalRandom.current().nextInt(s.size()));
      return new ItemStack(x.item(), x.amount());
   }

   private static Gacha.Prize prize(Gacha.Slot x) {
      return new Gacha.Prize(new ItemStack(x.item(), x.amount()), x.rarity());
   }

   public Map<Gacha.Rarity, Double> rates() {
      Map<Gacha.Rarity, Double> out = new LinkedHashMap<>();

      for (Gacha.Rarity r : Gacha.Rarity.values()) {
         out.put(r, 0.0);
      }

      long total = 0L;

      for (Gacha.Slot x : this.slots()) {
         total += x.chance();
      }

      if (total <= 0L) {
         return out;
      } else {
         for (Gacha.Slot x : this.slots()) {
            out.merge(x.rarity(), 100.0 * x.chance() / total, Double::sum);
         }

         return out;
      }
   }

   private static void tier(List<Gacha.Entry> out, Gacha.Rarity rarity, int total, String... items) {
      int each = Math.max(1, total / items.length);

      for (String spec : items) {
         String[] parts = spec.split("=");
         Gacha.Entry e = new Gacha.Entry();
         e.item = "minecraft:" + parts[0];
         e.amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
         e.chance = each;
         e.rarity = rarity.name();
         out.add(e);
      }
   }

   private static List<Gacha.Entry> defaultPool() {
      List<Gacha.Entry> p = new ArrayList<>();
      tier(
         p,
         Gacha.Rarity.COMMON,
         70000,
         "bread=8",
         "torch=16",
         "oak_log=16",
         "cobblestone=32",
         "sand=32",
         "arrow=16",
         "string=8",
         "bone=8",
         "carrot=16",
         "potato=16",
         "cooked_beef=6",
         "glass=16",
         "redstone=8",
         "coal=8",
         "raw_copper=8",
         "raw_iron=4",
         "apple=4",
         "wheat=16",
         "sugar_cane=16",
         "cookie=8"
      );
      tier(
         p,
         Gacha.Rarity.UNCOMMON,
         22000,
         "iron_ingot=8",
         "gold_ingot=4",
         "lapis_lazuli=8",
         "quartz=8",
         "ender_pearl=2",
         "blaze_rod=2",
         "slime_ball=4",
         "experience_bottle=4",
         "golden_apple=1",
         "golden_carrot=8",
         "obsidian=4",
         "gunpowder=8",
         "glowstone=8",
         "copper_ingot=16"
      );
      tier(
         p,
         Gacha.Rarity.RARE,
         6500,
         "diamond=1",
         "emerald=2",
         "ancient_debris=1",
         "shulker_shell=1",
         "nautilus_shell=1",
         "name_tag=1",
         "saddle=1",
         "iron_block=2",
         "gold_block=1",
         "experience_bottle=16",
         "ender_chest=1",
         "enchanting_table=1",
         "anvil=1",
         "crying_obsidian=4"
      );
      tier(
         p,
         Gacha.Rarity.EPIC,
         1400,
         "diamond_sword=1",
         "diamond_pickaxe=1",
         "diamond_chestplate=1",
         "diamond_helmet=1",
         "diamond_block=1",
         "netherite_scrap=2",
         "totem_of_undying=1",
         "enchanted_golden_apple=1",
         "trident=1",
         "heart_of_the_sea=1",
         "lodestone=1",
         "respawn_anchor=1"
      );
      tier(p, Gacha.Rarity.LEGENDARY, 100, "netherite_ingot=1", "nether_star=1", "elytra=1", "beacon=1", "mace=1", "heavy_core=1");
      return p;
   }

   public static final class Entry {
      public String item;
      public int amount = 1;
      public int chance = 100;
      public String rarity = "COMMON";

      public Entry() {
      }
   }

   public record Prize(ItemStack stack, Gacha.Rarity rarity) {
   }

   public static enum Rarity {
      COMMON("Common", ChatFormatting.WHITE),
      UNCOMMON("Uncommon", ChatFormatting.GREEN),
      RARE("Rare", ChatFormatting.AQUA),
      EPIC("Epic", ChatFormatting.LIGHT_PURPLE),
      LEGENDARY("Legendary", ChatFormatting.GOLD);

      public final String label;
      public final ChatFormatting color;

      private Rarity(String label, ChatFormatting color) {
         this.label = label;
         this.color = color;
      }

      static Gacha.Rarity parse(String s) {
         if (s != null) {
            for (Gacha.Rarity r : values()) {
               if (r.name().equalsIgnoreCase(s.trim())) {
                  return r;
               }
            }
         }

         return COMMON;
      }
   }

   private record Slot(Item item, int amount, int chance, Gacha.Rarity rarity) {
   }
}
