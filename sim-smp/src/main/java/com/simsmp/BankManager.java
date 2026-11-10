package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * SIM Bank: players deposit money and gold, the gold price moves every few minutes (a simulated market: random walk,
 * pull back to a base price and occasional "news" shocks), big money withdrawals need a teller (set by OP).
 * Settings: config/sim_smp/bank.properties, data: bank.json.
 */
public final class BankManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
   /** gold is stored in nuggets: ingot = 9, block = 81 */
   public static final long INGOT = 9L;
   public static final long BLOCK = 81L;

   public static final class Account {
      public long money;
      public long nuggets;
      public List<String> log = new ArrayList<>();
   }

   public static final class Pending {
      public String id = "";
      public String player = "";
      public long amount;
      public long time;
   }

   private static final class Data {
      long price;
      long prevPrice;
      long nextUpdate;
      List<Long> history = new ArrayList<>();
      Map<String, Account> accounts = new HashMap<>();
      Set<String> tellers = new HashSet<>();
      List<Pending> pending = new ArrayList<>();
   }

   private final Path dir;
   private final Path file;
   private final Path cfgFile;
   private final EconomyManager eco;
   private final Random rnd = new Random();
   private Data data = new Data();
   private boolean dirty;
   private long basePrice = 1000L;
   private long minPrice = 200L;
   private long maxPrice = 5000L;
   private int intervalSeconds = 300;
   private double volatility = 6.0;
   private double spread = 2.0;
   private long approvalLimit = 50000L;

   public BankManager(Path dir, EconomyManager eco) {
      this.dir = dir;
      this.file = dir.resolve("bank.json");
      this.cfgFile = dir.resolve("bank.properties");
      this.eco = eco;
   }

   // ------------------------------------------------------------------ load / save
   public synchronized void load() {
      try {
         Files.createDirectories(this.dir);
         Properties p = new Properties();
         if (Files.exists(this.cfgFile)) {
            try (Reader r = Files.newBufferedReader(this.cfgFile)) {
               p.load(r);
            }
         } else {
            p.setProperty("basePrice", "1000");
            p.setProperty("minPrice", "200");
            p.setProperty("maxPrice", "5000");
            p.setProperty("intervalSeconds", "300");
            p.setProperty("volatilityPercent", "6");
            p.setProperty("spreadPercent", "2");
            p.setProperty("approvalLimit", "50000");
            try (Writer w = Files.newBufferedWriter(this.cfgFile)) {
               p.store(w, "SIM bank. Gold price per ingot, moves every intervalSeconds. Money withdrawals above approvalLimit need a teller.");
            }
         }

         this.basePrice = Long.parseLong(p.getProperty("basePrice", "1000").trim());
         this.minPrice = Long.parseLong(p.getProperty("minPrice", "200").trim());
         this.maxPrice = Long.parseLong(p.getProperty("maxPrice", "5000").trim());
         this.intervalSeconds = Math.max(10, Integer.parseInt(p.getProperty("intervalSeconds", "300").trim()));
         this.volatility = Double.parseDouble(p.getProperty("volatilityPercent", "6").trim());
         this.spread = Double.parseDouble(p.getProperty("spreadPercent", "2").trim());
         this.approvalLimit = Long.parseLong(p.getProperty("approvalLimit", "50000").trim());
         if (Files.exists(this.file)) {
            try (Reader r = Files.newBufferedReader(this.file)) {
               Data d = GSON.fromJson(r, Data.class);
               if (d != null) {
                  this.data = d;
               }
            }
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read the bank settings/data (keeping defaults)", e);
      }

      if (this.data.price <= 0L) {
         this.data.price = this.basePrice;
         this.data.prevPrice = this.basePrice;
      }

      if (this.data.history.isEmpty()) {
         this.data.history.add(this.data.price);
      }

      if (this.data.nextUpdate <= 0L) {
         this.data.nextUpdate = System.currentTimeMillis() + this.intervalSeconds * 1000L;
      }
   }

   public synchronized void save() {
      try {
         Files.createDirectories(this.dir);
         Path tmp = this.file.resolveSibling("bank.json.tmp");
         Files.writeString(tmp, GSON.toJson(this.data));
         Files.move(tmp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         this.dirty = false;
      } catch (IOException e) {
         SimSmp.LOGGER.error("Could not save bank.json", e);
      }
   }

   // ------------------------------------------------------------------ market
   public synchronized long price() {
      return this.data.price;
   }

   public synchronized long prevPrice() {
      return this.data.prevPrice;
   }

   public synchronized List<Long> history() {
      return new ArrayList<>(this.data.history);
   }

   public long buyPrice() {
      return Math.max(1L, Math.round(this.data.price * (1.0 + this.spread / 100.0)));
   }

   public long sellPrice() {
      return Math.max(1L, Math.round(this.data.price * (1.0 - this.spread / 100.0)));
   }

   public synchronized long secondsToUpdate() {
      return Math.max(0L, (this.data.nextUpdate - System.currentTimeMillis()) / 1000L);
   }

   private static final String[] UP_NEWS = {"Gold demand surges", "Rumours of a new gold vein", "Merchants are stocking up", "Kingdom treasury buys gold"};
   private static final String[] DOWN_NEWS = {"Gold flood from a deep mine", "Merchants dump their gold", "The kingdom sells reserves", "Buyers hold back"};

   private synchronized void updatePrice(MinecraftServer server) {
      long old = this.data.price;
      double change = this.rnd.nextGaussian() * this.volatility / 100.0;
      change += (double)(this.basePrice - old) / this.basePrice * 0.05;
      String headline = null;
      if (this.rnd.nextInt(100) < 12) {
         boolean up = this.rnd.nextBoolean();
         change += (up ? 1 : -1) * (0.05 + this.rnd.nextDouble() * 0.1);
         String[] pool = up ? UP_NEWS : DOWN_NEWS;
         headline = pool[this.rnd.nextInt(pool.length)];
      }

      long now = Math.round(old * (1.0 + change));
      now = Math.max(this.minPrice, Math.min(this.maxPrice, now));
      this.data.prevPrice = old;
      this.data.price = now;
      this.data.history.add(now);
      while (this.data.history.size() > 24) {
         this.data.history.remove(0);
      }

      this.data.nextUpdate = System.currentTimeMillis() + this.intervalSeconds * 1000L;
      this.dirty = true;
      double pct = old == 0L ? 0.0 : (now - old) * 100.0 / old;
      if (headline != null || Math.abs(pct) >= 4.0) {
         String arrow = now >= old ? "UP" : "DOWN";
         Msg.broadcast(server, "BANK: gold " + arrow + " to " + this.eco.fmt(now) + " per ingot (" + String.format(java.util.Locale.US, "%+.1f", pct) + "%)" + (headline == null ? "" : " - " + headline), now >= old ? ChatFormatting.GREEN : ChatFormatting.RED);
      }
   }

   /** once per second */
   public void tick(MinecraftServer server) {
      boolean due;
      synchronized (this) {
         due = System.currentTimeMillis() >= this.data.nextUpdate;
      }

      if (due) {
         this.updatePrice(server);
      }

      if (this.dirty) {
         this.save();
      }
   }

   // ------------------------------------------------------------------ accounts
   public synchronized Account account(UUID id) {
      return this.data.accounts.computeIfAbsent(id.toString(), k -> new Account());
   }

   public synchronized long netWorth(UUID id) {
      Account a = this.account(id);
      return a.money + a.nuggets * this.data.price / INGOT;
   }

   private void log(Account a, String text) {
      a.log.add("[" + LocalTime.now().format(HM) + "] " + text);
      while (a.log.size() > 10) {
         a.log.remove(0);
      }

      this.dirty = true;
   }

   public long approvalLimit() {
      return this.approvalLimit;
   }

   public synchronized boolean depositMoney(ServerPlayer p, long amount) {
      if (amount <= 0L || !this.eco.take(p.getUUID(), amount)) {
         return false;
      }

      Account a = this.account(p.getUUID());
      a.money += amount;
      this.log(a, "Deposited " + this.eco.fmt(amount));
      return true;
   }

   /** 0 = failed, 1 = paid, 2 = sent to a teller for approval */
   public synchronized int withdrawMoney(ServerPlayer p, long amount) {
      Account a = this.account(p.getUUID());
      if (amount <= 0L || a.money < amount) {
         return 0;
      }

      a.money -= amount;
      if (amount > this.approvalLimit) {
         Pending pd = new Pending();
         pd.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(this.rnd.nextInt(1296), 36);
         pd.player = p.getUUID().toString();
         pd.amount = amount;
         pd.time = System.currentTimeMillis();
         this.data.pending.add(pd);
         this.log(a, "Requested " + this.eco.fmt(amount) + " (waiting for a teller)");
         MinecraftServer server = p.level().getServer();
         for (ServerPlayer t : server.getPlayerList().getPlayers()) {
            if (this.isTeller(t)) {
               Msg.colored(t, Msg.name(p) + " requests a withdrawal of " + this.eco.fmt(amount) + ". Open /bank desk.", ChatFormatting.GOLD);
            }
         }

         return 2;
      } else {
         this.eco.add(p.getUUID(), amount);
         this.log(a, "Withdrew " + this.eco.fmt(amount));
         return 1;
      }
   }

   /** takes every gold ingot/nugget/block out of the player's inventory; returns the nuggets deposited */
   public synchronized long depositGold(ServerPlayer p) {
      Inventory inv = p.getInventory();
      long n = 0L;
      for (int i = 0; i < 36; i++) {
         ItemStack st = inv.getItem(i);
         if (st.isEmpty()) {
            continue;
         }

         long per = unitOf(st.getItem());
         if (per > 0L) {
            n += per * st.getCount();
            inv.setItem(i, ItemStack.EMPTY);
         }
      }

      if (n > 0L) {
         inv.setChanged();
         Account a = this.account(p.getUUID());
         a.nuggets += n;
         this.log(a, "Deposited " + fmtGold(n) + " gold");
      }

      return n;
   }

   private static long unitOf(Item item) {
      if (item == Items.GOLD_INGOT) {
         return INGOT;
      } else if (item == Items.GOLD_BLOCK) {
         return BLOCK;
      } else {
         return item == Items.GOLD_NUGGET ? 1L : 0L;
      }
   }

   public static String fmtGold(long nuggets) {
      long ingots = nuggets / INGOT;
      long rest = nuggets % INGOT;
      return ingots + (rest == 0L ? "" : "+" + rest + "/9") + " ingots";
   }

   public synchronized long withdrawGold(ServerPlayer p, long nuggets) {
      Account a = this.account(p.getUUID());
      long n = Math.min(nuggets, a.nuggets);
      if (n <= 0L) {
         return 0L;
      }

      a.nuggets -= n;
      this.give(p, Items.GOLD_BLOCK, n / BLOCK);
      this.give(p, Items.GOLD_INGOT, n % BLOCK / INGOT);
      this.give(p, Items.GOLD_NUGGET, n % INGOT);
      this.log(a, "Withdrew " + fmtGold(n) + " gold");
      return n;
   }

   private void give(ServerPlayer p, Item item, long count) {
      long left = count;
      while (left > 0L) {
         int c = (int)Math.min(64L, left);
         ItemStack st = new ItemStack(item, c);
         if (!p.getInventory().add(st) && !st.isEmpty()) {
            p.drop(st, false);
         }

         left -= c;
      }
   }

   /** sells gold inside the bank at the market (sell) price; returns money gained */
   public synchronized long sellGold(ServerPlayer p, long nuggets) {
      Account a = this.account(p.getUUID());
      long n = Math.min(nuggets, a.nuggets);
      if (n <= 0L) {
         return 0L;
      }

      long value = n * this.sellPrice() / INGOT;
      a.nuggets -= n;
      a.money += value;
      this.log(a, "Sold " + fmtGold(n) + " gold for " + this.eco.fmt(value));
      return value;
   }

   /** buys gold with the money in the bank; returns the nuggets bought (0 = not enough money) */
   public synchronized long buyGold(ServerPlayer p, long nuggets) {
      Account a = this.account(p.getUUID());
      long cost = (nuggets * this.buyPrice() + INGOT - 1L) / INGOT;
      if (nuggets <= 0L || cost <= 0L || a.money < cost) {
         return 0L;
      }

      a.money -= cost;
      a.nuggets += nuggets;
      this.log(a, "Bought " + fmtGold(nuggets) + " gold for " + this.eco.fmt(cost));
      return nuggets;
   }

   /** how many nuggets this much money buys right now */
   public long affordableNuggets(long money) {
      return money * INGOT / this.buyPrice();
   }

   // ------------------------------------------------------------------ tellers + approvals
   public static boolean isOp(ServerPlayer p) {
      return HierarchyMenu.isOp(p);
   }

   public synchronized boolean isTeller(ServerPlayer p) {
      return isOp(p) || this.data.tellers.contains(p.getUUID().toString());
   }

   public synchronized boolean hasTellerRole(UUID id) {
      return this.data.tellers.contains(id.toString());
   }

   public synchronized void setTeller(UUID id, boolean on) {
      if (on) {
         this.data.tellers.add(id.toString());
      } else {
         this.data.tellers.remove(id.toString());
      }

      this.dirty = true;
   }

   public synchronized List<String> tellerNames() {
      List<String> out = new ArrayList<>();
      for (String s : this.data.tellers) {
         try {
            out.add(this.eco.nameOf(UUID.fromString(s)));
         } catch (IllegalArgumentException ignored) {
         }
      }

      return out;
   }

   public synchronized List<Pending> pending() {
      return new ArrayList<>(this.data.pending);
   }

   /** teller decision on a request; approve pays the player (works offline), deny returns it to the bank account */
   public synchronized boolean decide(ServerPlayer teller, String id, boolean approve) {
      Pending found = null;
      for (Pending pd : this.data.pending) {
         if (pd.id.equals(id)) {
            found = pd;
         }
      }

      if (found == null || !this.isTeller(teller)) {
         return false;
      }

      this.data.pending.remove(found);
      UUID owner = UUID.fromString(found.player);
      Account a = this.account(owner);
      MinecraftServer server = teller.level().getServer();
      ServerPlayer op = server.getPlayerList().getPlayer(owner);
      if (approve) {
         this.eco.add(owner, found.amount);
         this.log(a, "Withdrawal of " + this.eco.fmt(found.amount) + " approved by " + Msg.name(teller));
         if (op != null) {
            Msg.ok(op, "Teller " + Msg.name(teller) + " approved your withdrawal of " + this.eco.fmt(found.amount) + ".");
         }
      } else {
         a.money += found.amount;
         this.log(a, "Withdrawal of " + this.eco.fmt(found.amount) + " denied");
         if (op != null) {
            Msg.err(op, "Teller " + Msg.name(teller) + " denied your withdrawal. The money is back in your account.");
         }
      }

      this.dirty = true;
      return true;
   }

   public String nameOf(UUID id) {
      return this.eco.nameOf(id);
   }

   public String fmt(long v) {
      return this.eco.fmt(v);
   }

   public long wallet(UUID id) {
      return this.eco.get(id);
   }

   // ------------------------------------------------------------------ commands
   public void register(CommandDispatcher<CommandSourceStack> d) {
      LiteralArgumentBuilder<CommandSourceStack> bank = Commands.literal("bank");
      bank.executes(ctx -> {
         BankMenu.open(ctx.getSource().getPlayerOrException(), this);
         return 1;
      });
      bank.then(Commands.literal("price").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, "Gold: " + this.eco.fmt(this.price()) + " per ingot (buy " + this.eco.fmt(this.buyPrice()) + ", sell " + this.eco.fmt(this.sellPrice()) + "). Next change in " + this.secondsToUpdate() + "s.");
         return 1;
      }));
      bank.then(Commands.literal("desk").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         if (this.isTeller(p)) {
            TellerMenu.open(p, this);
         } else {
            Msg.err(p, "Only tellers can use the teller desk.");
         }

         return 1;
      }));
      bank.then(
         Commands.literal("view")
            .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
               ServerPlayer p = ctx.getSource().getPlayerOrException();
               if (!this.isTeller(p)) {
                  Msg.err(p, "Only tellers can look at accounts.");
                  return 0;
               }

               ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
               Account a = this.account(t.getUUID());
               Msg.info(p, Msg.name(t) + ": money " + this.eco.fmt(a.money) + ", gold " + fmtGold(a.nuggets) + ", net worth " + this.eco.fmt(this.netWorth(t.getUUID())));
               return 1;
            }))
      );
      LiteralArgumentBuilder<CommandSourceStack> teller = Commands.literal("teller");
      teller.requires(Commands.hasPermission(new net.minecraft.server.permissions.PermissionCheck.Require(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)));
      teller.then(Commands.literal("add").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
         ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
         this.setTeller(t.getUUID(), true);
         ctx.getSource().sendSuccess(() -> Component.literal(Msg.name(t) + " is now a bank teller."), true);
         Msg.ok(t, "You are now a bank teller. Use /bank desk.");
         return 1;
      })));
      teller.then(Commands.literal("remove").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
         ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
         this.setTeller(t.getUUID(), false);
         ctx.getSource().sendSuccess(() -> Component.literal(Msg.name(t) + " is no longer a bank teller."), true);
         Msg.info(t, "You are no longer a bank teller.");
         return 1;
      })));
      teller.then(Commands.literal("list").executes(ctx -> {
         List<String> names = this.tellerNames();
         ctx.getSource().sendSuccess(() -> Component.literal("Tellers: " + (names.isEmpty() ? "nobody" : String.join(", ", names))), false);
         return 1;
      }));
      bank.then(teller);
      bank.then(
         Commands.literal("setprice")
            .requires(Commands.hasPermission(new net.minecraft.server.permissions.PermissionCheck.Require(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)))
            .then(Commands.argument("price", LongArgumentType.longArg(1L)).executes(ctx -> {
               synchronized (this) {
                  long np = LongArgumentType.getLong(ctx, "price");
                  this.data.prevPrice = this.data.price;
                  this.data.price = Math.max(this.minPrice, Math.min(this.maxPrice, np));
                  this.data.history.add(this.data.price);
                  this.dirty = true;
               }

               ctx.getSource().sendSuccess(() -> Component.literal("Gold price set to " + this.eco.fmt(this.price()) + " (the market keeps moving from here)."), true);
               return 1;
            }))
      );
      d.register(bank);
   }
}
