package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Supply/demand engine.
 *  - "volume" = money that came out of /sell and the player market in the last window. Lots of volume = inflation
 *    (all /sell prices and the gold price go up), little volume = deflation (they go down).
 *  - every ore/ingot has its own multiplier: when many players dump it into /sell the system buy price drops,
 *    when few do it rises (so players are pushed to sell to each other).
 *  - when inflation is high the bank sells gold from the owner's account to cool it down.
 */
public final class PriceEngine {
   private static PriceEngine instance;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   public static PriceEngine get() {
      return instance;
   }

   private static final class Data {
      double inflation = 1.0;
      double volume;
      long nextUpdate;
      double lastChangePct;
      Map<String, Double> sold = new HashMap<>();
      Map<String, Long> stock = new HashMap<>();
      List<Double> history = new ArrayList<>();
   }

   private final Path file;
   private final Path cfgFile;
   private Data data = new Data();
   private boolean dirty;
   private RankManager ranks;
   private int intervalSeconds = 300;
   private double targetVolume = 20000.0;
   private double stepPercent = 6.0;
   private double minInflation = 0.5;
   private double maxInflation = 3.0;
   private double oreSaturation = 256.0;
   private double stabilizeAbove = 1.25;
   private long stabilizeIngots = 8L;

   public PriceEngine(Path dir) {
      this.file = dir.resolve("priceengine.json");
      this.cfgFile = dir.resolve("priceengine.properties");
      instance = this;
   }

   public void setRanks(RankManager ranks) {
      this.ranks = ranks;
   }

   public synchronized void load() {
      try {
         Properties p = new Properties();
         if (Files.exists(this.cfgFile)) {
            try (Reader r = Files.newBufferedReader(this.cfgFile)) {
               p.load(r);
            }
         } else {
            p.setProperty("intervalSeconds", "300");
            p.setProperty("targetVolume", "20000");
            p.setProperty("stepPercent", "6");
            p.setProperty("minInflation", "0.5");
            p.setProperty("maxInflation", "3.0");
            p.setProperty("oreSaturation", "256");
            p.setProperty("stabilizeAbove", "1.25");
            p.setProperty("stabilizeIngots", "8");
            try (Writer w = Files.newBufferedWriter(this.cfgFile)) {
               p.store(w, "SIM price engine. targetVolume = money paid out by /sell + market per interval that keeps prices flat.");
            }
         }

         this.intervalSeconds = Math.max(10, Integer.parseInt(p.getProperty("intervalSeconds", "300").trim()));
         this.targetVolume = Math.max(1.0, Double.parseDouble(p.getProperty("targetVolume", "20000").trim()));
         this.stepPercent = Double.parseDouble(p.getProperty("stepPercent", "6").trim());
         this.minInflation = Double.parseDouble(p.getProperty("minInflation", "0.5").trim());
         this.maxInflation = Double.parseDouble(p.getProperty("maxInflation", "3.0").trim());
         this.oreSaturation = Math.max(1.0, Double.parseDouble(p.getProperty("oreSaturation", "256").trim()));
         this.stabilizeAbove = Double.parseDouble(p.getProperty("stabilizeAbove", "1.25").trim());
         this.stabilizeIngots = Long.parseLong(p.getProperty("stabilizeIngots", "8").trim());
         if (Files.exists(this.file)) {
            try (Reader r = Files.newBufferedReader(this.file)) {
               Data d = GSON.fromJson(r, Data.class);
               if (d != null) {
                  this.data = d;
               }
            }
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read the price engine files (keeping defaults)", e);
      }

      if (this.data.sold == null) {
         this.data.sold = new HashMap<>();
      }

      if (this.data.stock == null) {
         this.data.stock = new HashMap<>();
      }

      if (this.data.history == null) {
         this.data.history = new ArrayList<>();
      }

      if (this.data.inflation < this.minInflation || this.data.inflation > this.maxInflation) {
         this.data.inflation = 1.0;
      }

      if (this.data.nextUpdate <= 0L) {
         this.data.nextUpdate = System.currentTimeMillis() + this.intervalSeconds * 1000L;
      }
   }

   public synchronized void save() {
      try {
         Path tmp = this.file.resolveSibling("priceengine.json.tmp");
         Files.writeString(tmp, GSON.toJson(this.data));
         Files.move(tmp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         this.dirty = false;
      } catch (IOException e) {
         SimSmp.LOGGER.error("Could not save priceengine.json", e);
      }
   }

   // ------------------------------------------------------------------ item groups
   private static String path(String id) {
      return id.contains(":") ? id.substring(id.indexOf(58) + 1) : id;
   }

   /** ingots, ores and raw ores: the only things the player market accepts and the ones with a supply multiplier */
   public static boolean isOreGroup(String id) {
      String p = path(id);
      return p.endsWith("_ingot")
         || p.endsWith("_ore")
         || p.startsWith("raw_")
         || p.equals("diamond")
         || p.equals("emerald")
         || p.equals("coal")
         || p.equals("lapis_lazuli")
         || p.equals("redstone")
         || p.equals("quartz")
         || p.equals("netherite_scrap")
         || p.equals("ancient_debris");
   }

   public static boolean marketAllowed(String id) {
      return isOreGroup(id);
   }

   /** gold in nuggets (0 = not gold). Gold sold through /sell goes to the bank owner. */
   public static long goldNuggets(String id) {
      return switch (path(id)) {
         case "gold_nugget" -> 1L;
         case "gold_ingot", "raw_gold", "gold_ore", "deepslate_gold_ore" -> 9L;
         case "nether_gold_ore" -> 5L;
         case "gold_block", "raw_gold_block" -> 81L;
         default -> 0L;
      };
   }

   /** diamonds (0 = not diamond). Also redirected to the bank owner. */
   public static long diamonds(String id) {
      return switch (path(id)) {
         case "diamond", "diamond_ore", "deepslate_diamond_ore" -> 1L;
         case "diamond_block" -> 9L;
         default -> 0L;
      };
   }

   // ------------------------------------------------------------------ prices
   public synchronized double inflation() {
      return this.data.inflation;
   }

   public synchronized double lastChangePct() {
      return this.data.lastChangePct;
   }

   public synchronized double oreMult(String id) {
      if (!isOreGroup(id)) {
         return 1.0;
      }

      double c = this.data.sold.getOrDefault(id, 0.0);
      return Math.max(0.3, Math.min(1.5, 1.5 / (1.0 + c / this.oreSaturation)));
   }

   /** price the system pays per item right now (base price from prices.json x inflation x supply multiplier) */
   public synchronized long effective(String id, long base) {
      return Math.max(1L, Math.round(base * this.data.inflation * this.oreMult(id)));
   }

   public synchronized void recordSale(String id, int count, long money) {
      if (isOreGroup(id)) {
         this.data.sold.merge(id, (double)count, Double::sum);
         if (goldNuggets(id) <= 0L && diamonds(id) <= 0L) {
            this.data.stock.merge(id, (long)count, Long::sum);
         }
      }

      this.data.volume += money;
      this.dirty = true;
   }

   // ------------------------------------------------------------------ /buy shop (stock = ores players sold to the system)
   private SimConfig cfg;
   private EconomyManager eco;

   public void setContext(SimConfig cfg, EconomyManager eco) {
      this.cfg = cfg;
      this.eco = eco;
   }

   public synchronized long stock(String id) {
      return this.data.stock.getOrDefault(id, 0L);
   }

   public synchronized List<String> stockedItems() {
      List<String> out = new ArrayList<>();
      for (Map.Entry<String, Long> e : this.data.stock.entrySet()) {
         if (e.getValue() > 0L && this.cfg != null && this.cfg.prices.containsKey(e.getKey())) {
            out.add(e.getKey());
         }
      }

      java.util.Collections.sort(out);
      return out;
   }

   /** price a player pays per item: the current system buy price plus a 25% margin (stays above what /sell pays) */
   public synchronized long buyPrice(String id) {
      Long base = this.cfg == null ? null : this.cfg.prices.get(id);
      if (base == null) {
         return -1L;
      }

      long sell = this.effective(id, base);
      return Math.max(sell + 1L, Math.round(sell * 1.25));
   }

   /** returns the amount really bought (0 = nothing) */
   public synchronized int buy(ServerPlayer p, String id, int want) {
      long have = this.stock(id);
      long price = this.buyPrice(id);
      if (price <= 0L || have <= 0L) {
         Msg.err(p, "Out of stock.");
         return 0;
      }

      int n = (int)Math.min((long)want, have);
      long cost = price * n;
      if (!this.eco.take(p.getUUID(), cost)) {
         Msg.err(p, "You need " + this.eco.fmt(cost) + " but only have " + this.eco.fmt(this.eco.get(p.getUUID())) + ".");
         return 0;
      }

      this.data.stock.put(id, have - n);
      // people buying the ore back lowers the "dumped" pressure, so the system price rises again
      this.data.sold.computeIfPresent(id, (k, v) -> Math.max(0.0, v - n));
      this.data.volume += cost;
      this.dirty = true;
      return n;
   }

   public synchronized void recordMarket(long money) {
      this.data.volume += money;
      this.dirty = true;
   }

   /** what the gold price should drift towards (called by the bank) */
   public synchronized double goldFactor() {
      return this.data.inflation;
   }

   // ------------------------------------------------------------------ merchants
   public boolean isMerchant(ServerPlayer p) {
      if (HierarchyMenu.isOp(p)) {
         return true;
      }

      String preset = this.ranks == null ? null : this.ranks.presetOf(p.getUUID());
      return "merchant".equals(preset);
   }

   // ------------------------------------------------------------------ update
   public void tick(MinecraftServer server) {
      boolean due;
      synchronized (this) {
         due = System.currentTimeMillis() >= this.data.nextUpdate;
      }

      if (due) {
         this.update(server);
      }

      if (this.dirty) {
         this.save();
      }
   }

   private void update(MinecraftServer server) {
      double old;
      double now;
      synchronized (this) {
         old = this.data.inflation;
         double ratio = Math.max(-1.0, Math.min(1.0, (this.data.volume - this.targetVolume) / this.targetVolume));
         now = Math.max(this.minInflation, Math.min(this.maxInflation, old * (1.0 + ratio * this.stepPercent / 100.0)));
         this.data.volume = 0.0;
         Iterator<Map.Entry<String, Double>> it = this.data.sold.entrySet().iterator();

         while (it.hasNext()) {
            Map.Entry<String, Double> e = it.next();
            e.setValue(e.getValue() * 0.5);
            if (e.getValue() < 0.5) {
               it.remove();
            }
         }

         this.data.inflation = now;
         this.data.nextUpdate = System.currentTimeMillis() + this.intervalSeconds * 1000L;
         this.dirty = true;
      }

      // the bank cools inflation down by selling gold from its owner's stock
      BankManager bank = BankManager.get();
      if (bank != null && now > this.stabilizeAbove) {
         long sold = bank.stabilizeSell(server, this.stabilizeIngots);
         if (sold > 0L) {
            synchronized (this) {
               this.data.inflation = Math.max(this.minInflation, this.data.inflation - sold * 0.01);
               now = this.data.inflation;
            }

            Msg.broadcast(server, "BANK: sold " + sold + " gold ingots to cool down inflation.", ChatFormatting.GOLD);
         }
      }

      synchronized (this) {
         this.data.lastChangePct = old == 0.0 ? 0.0 : (now - old) * 100.0 / old;
         this.data.history.add(now);
         while (this.data.history.size() > 144) {
            this.data.history.remove(0);
         }

         if (Math.abs(this.data.lastChangePct) >= 2.0) {
            boolean up = this.data.lastChangePct > 0.0;
            Msg.broadcast(
               server,
               "ECONOMY: " + (up ? "inflation, prices rising" : "deflation, prices falling") + " (index " + String.format(Locale.US, "%.2f", now) + ", "
                  + String.format(Locale.US, "%+.1f", this.data.lastChangePct) + "%). " + (up ? "Sell less to the system, trade with players!" : "The system pays less - sell to players or wait."),
               up ? ChatFormatting.RED : ChatFormatting.AQUA
            );
         }
      }
   }

   /** data for the economy web page */
   public synchronized String json() {
      com.google.gson.JsonObject o = new com.google.gson.JsonObject();
      o.addProperty("inflation", this.data.inflation);
      o.addProperty("lastChangePct", this.data.lastChangePct);
      o.addProperty("volume", Math.round(this.data.volume));
      o.addProperty("targetVolume", Math.round(this.targetVolume));
      o.addProperty("secondsToUpdate", Math.max(0L, (this.data.nextUpdate - System.currentTimeMillis()) / 1000L));
      BankManager b = BankManager.get();
      o.addProperty("goldPrice", b == null ? 0L : b.price());
      o.addProperty("goldBuy", b == null ? 0L : b.buyPrice());
      o.addProperty("goldSell", b == null ? 0L : b.sellPrice());
      com.google.gson.JsonArray hist = new com.google.gson.JsonArray();
      for (Double v : this.data.history) {
         hist.add(v);
      }

      if (hist.isEmpty()) {
         hist.add(this.data.inflation);
      }

      o.add("history", hist);
      com.google.gson.JsonArray items = new com.google.gson.JsonArray();
      if (this.cfg != null) {
         for (Map.Entry<String, Long> e : this.cfg.prices.entrySet()) {
            String id = e.getKey();
            if (!isOreGroup(id) || goldNuggets(id) > 0L || diamonds(id) > 0L) {
               continue;
            }

            com.google.gson.JsonObject it = new com.google.gson.JsonObject();
            it.addProperty("name", Msg.pretty(id));
            it.addProperty("sell", this.effective(id, e.getValue()));
            it.addProperty("buy", this.buyPrice(id));
            it.addProperty("mult", this.oreMult(id));
            it.addProperty("stock", this.stock(id));
            items.add(it);
         }
      }

      o.add("items", items);
      return o.toString();
   }

   public synchronized String describe() {
      double i = this.data.inflation;
      String state = i > 1.05 ? "inflation" : i < 0.95 ? "deflation" : "stable";
      return "Currency index " + String.format(Locale.US, "%.2f", i) + " (" + state + "). Volume this window: " + Math.round(this.data.volume) + " / " + Math.round(this.targetVolume) + ".";
   }

   // ------------------------------------------------------------------ commands
   public void register(CommandDispatcher<CommandSourceStack> d) {
      d.register(Commands.literal("buy").executes(ctx -> {
         BuyMenu.open(ctx.getSource().getPlayerOrException(), this, this.eco);
         return 1;
      }));
      d.register(Commands.literal("inflation").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, this.describe());
         List<String> lines = new ArrayList<>();
         synchronized (this) {
            for (Map.Entry<String, Double> e : this.data.sold.entrySet()) {
               lines.add(Msg.pretty(e.getKey()) + " x" + String.format(Locale.US, "%.2f", this.oreMult(e.getKey())));
            }
         }

         if (!lines.isEmpty()) {
            Msg.info(p, "System buy price multipliers: " + String.join(", ", lines));
         }

         Msg.info(p, "Ores/ingots nobody dumps into /sell pay up to x1.50. Sell them to other players via /market.");
         return 1;
      }));
   }
}
