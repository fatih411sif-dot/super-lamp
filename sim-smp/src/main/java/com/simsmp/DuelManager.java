package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 1v1 duels in a void colosseum dimension (replaces the idea of a Multiverse world: Multiverse only exists for Bukkit/Paper).
 * Fighters' items are saved to disk and given back afterwards; fighters pick a kit; other players can /watch and bet.
 * Settings: config/sim_smp/duel.properties, kits: config/sim_smp/duel_kits.json.
 */
public final class DuelManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

   public enum Phase {
      NONE,
      SELECT,
      COUNTDOWN,
      FIGHT,
      ENDING
   }

   public record Kit(String name, String icon, JsonArray items, String description) {
   }

   private static final class Fighter {
      UUID id;
      String name;
      int kit = -1;
      boolean ready;
   }

   private static final class Bet {
      int on;       // 0 = fighter A, 1 = fighter B
      long amount;
   }

   private static final class Challenge {
      UUID from;
      long expires;
   }

   private final Path dir;
   private final Path savedDir;
   private final EconomyManager eco;
   private final Path configFile;
   private final Path kitsFile;
   private final Path builtFlag;
   // settings
   private String dimension = "sim_smp:colosseum";
   private int cx;
   private int cz;
   private int selectSeconds = 30;
   private int countdownSeconds = 5;
   private int fightSeconds = 180;
   private int fighterPercent = 70;
   private long minBet = 100L;
   private long maxBet = 100000L;
   private int betFightSeconds = 30;
   private int challengeSeconds = 60;
   private List<Kit> kits = new ArrayList<>();
   // state
   private Phase phase = Phase.NONE;
   private final Fighter[] f = new Fighter[2];
   private long phaseEnd;
   private long fightStart;
   private int countdown;
   private UUID winner;
   private boolean drawResult;
   private final Map<UUID, Bet> bets = new HashMap<>();
   private final Set<UUID> watchers = new HashSet<>();
   private final Map<UUID, Challenge> challenges = new HashMap<>();
   private long buildAt;
   private boolean built;

   public DuelManager(Path dir, EconomyManager eco) {
      this.dir = dir;
      this.savedDir = dir.resolve("duel_saved");
      this.eco = eco;
      this.configFile = dir.resolve("duel.properties");
      this.kitsFile = dir.resolve("duel_kits.json");
      this.builtFlag = dir.resolve("duel_arena_built.txt");
   }

   // ------------------------------------------------------------------ config
   public void load() {
      try {
         Files.createDirectories(this.dir);
         Files.createDirectories(this.savedDir);
         Properties p = new Properties();
         if (Files.exists(this.configFile)) {
            try (Reader r = Files.newBufferedReader(this.configFile)) {
               p.load(r);
            }
         } else {
            p.setProperty("dimension", this.dimension);
            p.setProperty("centerX", "0");
            p.setProperty("centerZ", "0");
            p.setProperty("selectSeconds", "30");
            p.setProperty("countdownSeconds", "5");
            p.setProperty("fightSeconds", "180");
            p.setProperty("fighterPercent", "70");
            p.setProperty("minBet", "100");
            p.setProperty("maxBet", "100000");
            p.setProperty("betFightSeconds", "30");
            p.setProperty("challengeSeconds", "60");
            try (Writer w = Files.newBufferedWriter(this.configFile)) {
               p.store(w, "SIM SMP duel. dimension = arena dimension (default sim_smp:colosseum from the mod; minecraft:the_end with centerX/centerZ 300 also works). fighterPercent = share of the bet pool the winning fighter gets, the rest goes to the people who bet on him.");
            }
         }

         this.dimension = p.getProperty("dimension", this.dimension).trim();
         this.cx = Integer.parseInt(p.getProperty("centerX", "0").trim());
         this.cz = Integer.parseInt(p.getProperty("centerZ", "0").trim());
         this.selectSeconds = Integer.parseInt(p.getProperty("selectSeconds", "30").trim());
         this.countdownSeconds = Integer.parseInt(p.getProperty("countdownSeconds", "5").trim());
         this.fightSeconds = Integer.parseInt(p.getProperty("fightSeconds", "180").trim());
         this.fighterPercent = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("fighterPercent", "70").trim())));
         this.minBet = Long.parseLong(p.getProperty("minBet", "100").trim());
         this.maxBet = Long.parseLong(p.getProperty("maxBet", "100000").trim());
         this.betFightSeconds = Integer.parseInt(p.getProperty("betFightSeconds", "30").trim());
         this.challengeSeconds = Integer.parseInt(p.getProperty("challengeSeconds", "60").trim());
         this.built = Files.exists(this.builtFlag);
         if (!Files.exists(this.kitsFile)) {
            Files.writeString(this.kitsFile, defaultKits());
         }

         this.kits = new ArrayList<>();
         JsonArray arr = JsonParser.parseString(Files.readString(this.kitsFile)).getAsJsonArray();
         for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            this.kits.add(new Kit(o.get("name").getAsString(), o.get("icon").getAsString(), o.getAsJsonArray("items"), o.has("description") ? o.get("description").getAsString() : ""));
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read the duel settings/kits, duels may not work", e);
      }
   }

   private static String item(int slot, String id, int count) {
      return "{\"slot\":" + slot + ",\"item\":{\"id\":\"minecraft:" + id + "\",\"count\":" + count + "}}";
   }

   private static String defaultKits() {
      String warrior = String.join(",", item(0, "iron_sword", 1), item(1, "golden_apple", 4), item(2, "cooked_beef", 16), item(36, "iron_boots", 1), item(37, "iron_leggings", 1), item(38, "iron_chestplate", 1), item(39, "iron_helmet", 1), item(40, "shield", 1));
      String archer = String.join(",", item(0, "bow", 1), item(1, "stone_sword", 1), item(2, "golden_apple", 3), item(3, "cooked_beef", 16), item(9, "arrow", 64), item(36, "chainmail_boots", 1), item(37, "chainmail_leggings", 1), item(38, "chainmail_chestplate", 1), item(39, "chainmail_helmet", 1));
      String tank = String.join(",", item(0, "stone_sword", 1), item(1, "golden_apple", 5), item(2, "cooked_beef", 16), item(36, "diamond_boots", 1), item(37, "diamond_leggings", 1), item(38, "diamond_chestplate", 1), item(39, "diamond_helmet", 1), item(40, "shield", 1));
      String berserker = String.join(",", item(0, "diamond_axe", 1), item(1, "golden_apple", 3), item(2, "cooked_beef", 16), item(36, "leather_boots", 1), item(37, "leather_leggings", 1), item(38, "leather_chestplate", 1), item(39, "leather_helmet", 1));
      return "[\n"
         + "{\"name\":\"Warrior\",\"icon\":\"minecraft:iron_sword\",\"description\":\"Iron armor, sword and shield\",\"items\":[" + warrior + "]},\n"
         + "{\"name\":\"Archer\",\"icon\":\"minecraft:bow\",\"description\":\"Bow, 64 arrows, chainmail\",\"items\":[" + archer + "]},\n"
         + "{\"name\":\"Tank\",\"icon\":\"minecraft:diamond_chestplate\",\"description\":\"Diamond armor, shield, stone sword\",\"items\":[" + tank + "]},\n"
         + "{\"name\":\"Berserker\",\"icon\":\"minecraft:diamond_axe\",\"description\":\"Diamond axe, light leather armor\",\"items\":[" + berserker + "]}\n"
         + "]\n";
   }

   public List<Kit> kits() {
      return this.kits;
   }

   public Phase phase() {
      return this.phase;
   }

   // ------------------------------------------------------------------ helpers
   private static void run(MinecraftServer server, String cmd) {
      server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
   }

   private int idxOf(UUID id) {
      for (int i = 0; i < 2; i++) {
         if (this.f[i] != null && this.f[i].id.equals(id)) {
            return i;
         }
      }

      return -1;
   }

   private boolean active() {
      return this.phase != Phase.NONE;
   }

   private void tp(MinecraftServer server, UUID id, String dim, double x, double y, double z, float yaw, float pitch) {
      run(server, "execute in " + dim + " run tp " + id + " " + x + " " + y + " " + z + " " + yaw + " " + pitch);
   }

   private void tpArena(MinecraftServer server, UUID id, double dx, double y, double dz, float yaw, float pitch) {
      this.tp(server, id, this.dimension, this.cx + dx, y, this.cz + dz, yaw, pitch);
   }

   private static String dimOf(ServerPlayer p) {
      String s = p.level().dimension().toString();      // ResourceKey[minecraft:dimension / minecraft:overworld]
      int i = s.indexOf(" / ");
      int j = s.lastIndexOf(']');
      return i >= 0 && j > i ? s.substring(i + 3, j) : "minecraft:overworld";
   }

   private void sendTitle(MinecraftServer server, UUID id, String text, String color) {
      run(server, "title " + id + " title {\"text\":\"" + text + "\",\"color\":\"" + color + "\",\"bold\":true}");
   }

   private ServerPlayer player(MinecraftServer server, UUID id) {
      return id == null ? null : server.getPlayerList().getPlayer(id);
   }

   private void tellAll(MinecraftServer server, String text, ChatFormatting color) {
      Msg.broadcast(server, text, color);
   }

   private RegistryOps<JsonElement> ops(MinecraftServer server) {
      return RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
   }

   // ------------------------------------------------------------------ saved state (items + position)
   private Path savedFile(UUID id) {
      return this.savedDir.resolve(id + ".json");
   }

   /** writes the player's items, position and health to disk (crash-safe), then empties the inventory */
   private void snapshot(ServerPlayer p, boolean withItems) {
      JsonObject o = new JsonObject();
      o.addProperty("dim", dimOf(p));
      o.addProperty("x", p.getX());
      o.addProperty("y", p.getY());
      o.addProperty("z", p.getZ());
      o.addProperty("yaw", p.getYRot());
      o.addProperty("pitch", p.getXRot());
      o.addProperty("health", p.getHealth());
      JsonArray items = new JsonArray();
      if (withItems) {
         RegistryOps<JsonElement> ops = this.ops(p.level().getServer());
         Inventory inv = p.getInventory();
         for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) {
               Optional<JsonElement> enc = ItemStack.CODEC.encodeStart(ops, st).result();
               if (enc.isPresent()) {
                  JsonObject e = new JsonObject();
                  e.addProperty("slot", i);
                  e.add("item", enc.get());
                  items.add(e);
               }
            }
         }
      }

      o.add("items", items);
      o.addProperty("hasItems", withItems);
      try {
         Files.writeString(this.savedFile(p.getUUID()), GSON.toJson(o));
      } catch (IOException e) {
         SimSmp.LOGGER.error("Could not save the inventory of " + p.getUUID(), e);
      }
   }

   /** gives back items/position/health and deletes the file; true if there was something to restore */
   private boolean restore(ServerPlayer p) {
      Path file = this.savedFile(p.getUUID());
      if (!Files.exists(file)) {
         return false;
      } else {
         MinecraftServer server = p.level().getServer();
         try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            run(server, "effect clear " + p.getUUID());
            run(server, "gamemode survival " + p.getUUID());
            if (o.get("hasItems").getAsBoolean()) {
               RegistryOps<JsonElement> ops = this.ops(server);
               Inventory inv = p.getInventory();
               inv.clearContent();
               for (JsonElement e : o.getAsJsonArray("items")) {
                  JsonObject eo = e.getAsJsonObject();
                  Optional<ItemStack> st = ItemStack.CODEC.parse(ops, eo.get("item")).result();
                  if (st.isPresent()) {
                     inv.setItem(eo.get("slot").getAsInt(), st.get());
                  }
               }

               inv.setChanged();
            }

            this.tp(server, p.getUUID(), o.get("dim").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat());
            p.setHealth(Math.max(1.0F, Math.min(p.getMaxHealth(), o.get("health").getAsFloat())));
            Files.delete(file);
            return true;
         } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("Could not restore the saved state of " + p.getUUID() + " (file kept: " + file + ")", e);
            return false;
         }
      }
   }

   /** a player joining with a leftover file (server crashed / they quit mid-duel) gets everything back */
   public void onJoin(ServerPlayer p) {
      if (this.idxOf(p.getUUID()) < 0 && !this.watchers.contains(p.getUUID()) && Files.exists(this.savedFile(p.getUUID()))) {
         if (this.restore(p)) {
            Msg.info(p, "Your items and position from the duel were returned.");
         }
      }
   }

   // ------------------------------------------------------------------ arena
   public void prepareArena(MinecraftServer server) {
      if (!this.built && this.buildAt == 0L) {
         run(server, "execute in " + this.dimension + " run forceload add " + (this.cx - 40) + " " + (this.cz - 40) + " " + (this.cx + 40) + " " + (this.cz + 40));
         this.buildAt = System.currentTimeMillis() + 8000L;
      }
   }

   private void fill(MinecraftServer server, int x1, int y1, int z1, int x2, int y2, int z2, String block, String mode) {
      run(server, "execute in " + this.dimension + " run fill " + (this.cx + x1) + " " + y1 + " " + (this.cz + z1) + " " + (this.cx + x2) + " " + y2 + " " + (this.cz + z2) + " " + block + (mode.isEmpty() ? "" : " " + mode));
   }

   public void buildArena(MinecraftServer server) {
      this.fill(server, -30, 62, -30, 30, 62, 30, "minecraft:stone_bricks", "");
      this.fill(server, -12, 62, -12, 12, 62, 12, "minecraft:sand", "");
      this.fill(server, -13, 63, -13, 13, 65, 13, "minecraft:stone_brick_wall", "outline");
      this.fill(server, -13, 66, -13, 13, 80, 13, "minecraft:barrier", "outline");
      this.fill(server, -12, 80, -12, 12, 80, 12, "minecraft:barrier", "");
      for (int k = 0; k < 6; k++) {
         int h = 14 + k;
         int y = 62 + k;
         this.fill(server, -h, y, -h, h, y, -h, "minecraft:quartz_block", "");
         this.fill(server, -h, y, h, h, y, h, "minecraft:quartz_block", "");
         this.fill(server, -h, y, -h, -h, y, h, "minecraft:quartz_block", "");
         this.fill(server, h, y, -h, h, y, h, "minecraft:quartz_block", "");
      }

      int[][] lamps = {{-13, -13}, {13, -13}, {-13, 13}, {13, 13}, {0, -13}, {0, 13}, {-13, 0}, {13, 0}};
      for (int[] l : lamps) {
         this.fill(server, l[0], 66, l[1], l[0], 66, l[1], "minecraft:glowstone", "");
      }

      this.built = true;
      try {
         Files.writeString(this.builtFlag, "built");
      } catch (IOException ignored) {
      }

      SimSmp.LOGGER.info("Duel colosseum built in {}", this.dimension);
   }

   /** OP: build it again (e.g. after changing the center) */
   public void rebuildArena(MinecraftServer server) {
      this.built = false;
      this.buildAt = 0L;
      try {
         Files.deleteIfExists(this.builtFlag);
      } catch (IOException ignored) {
      }

      this.prepareArena(server);
   }

   // ------------------------------------------------------------------ challenge
   public void challenge(ServerPlayer from, ServerPlayer to) {
      String problem = null;
      if (from.getUUID().equals(to.getUUID())) {
         problem = "You can't duel yourself.";
      } else if (!this.built) {
         problem = "The colosseum is still being built, try again in a few seconds.";
      } else if (this.active()) {
         problem = "The colosseum is in use right now. Watch with /watch.";
      } else if (this.challenges.containsKey(to.getUUID())) {
         problem = Msg.name(to) + " already has a challenge waiting.";
      } else if (this.watchers.contains(from.getUUID()) || this.watchers.contains(to.getUUID())) {
         problem = "Leave the colosseum first (/unwatch).";
      }

      if (problem != null) {
         Msg.err(from, problem);
      } else {
         Challenge c = new Challenge();
         c.from = from.getUUID();
         c.expires = System.currentTimeMillis() + this.challengeSeconds * 1000L;
         this.challenges.put(to.getUUID(), c);
         Msg.ok(from, "Duel challenge sent to " + Msg.name(to) + ".");
         Msg.colored(to, Msg.name(from) + " challenges you to a duel! Type /duel accept or /duel decline.", ChatFormatting.GOLD);
      }
   }

   public void accept(ServerPlayer to) {
      MinecraftServer server = to.level().getServer();
      Challenge c = this.challenges.get(to.getUUID());
      ServerPlayer from = c == null ? null : this.player(server, c.from);
      if (c == null) {
         Msg.err(to, "Nobody has challenged you.");
      } else if (from == null) {
         this.challenges.remove(to.getUUID());
         Msg.err(to, "That player is no longer online.");
      } else if (this.active()) {
         this.challenges.remove(to.getUUID());
         Msg.err(to, "The colosseum is in use right now.");
      } else {
         this.challenges.remove(to.getUUID());
         this.startDuel(server, from, to);
      }
   }

   public void decline(ServerPlayer to) {
      Challenge c = this.challenges.remove(to.getUUID());
      if (c == null) {
         Msg.err(to, "Nobody has challenged you.");
      } else {
         Msg.info(to, "Challenge declined.");
         ServerPlayer from = this.player(to.level().getServer(), c.from);
         if (from != null) {
            Msg.info(from, Msg.name(to) + " declined your duel.");
         }
      }
   }

   private void startDuel(MinecraftServer server, ServerPlayer a, ServerPlayer b) {
      this.f[0] = new Fighter();
      this.f[1] = new Fighter();
      ServerPlayer[] ps = {a, b};
      this.bets.clear();
      this.winner = null;
      this.drawResult = false;
      for (int i = 0; i < 2; i++) {
         this.f[i].id = ps[i].getUUID();
         this.f[i].name = Msg.name(ps[i]);
         this.snapshot(ps[i], true);
         ps[i].getInventory().clearContent();
         ps[i].getInventory().setChanged();
         run(server, "effect clear " + ps[i].getUUID());
         run(server, "gamemode adventure " + ps[i].getUUID());
         run(server, "effect give " + ps[i].getUUID() + " minecraft:instant_health 1 10 true");
         run(server, "effect give " + ps[i].getUUID() + " minecraft:saturation 1 10 true");
         run(server, "effect give " + ps[i].getUUID() + " minecraft:resistance " + (this.selectSeconds + this.countdownSeconds + 5) + " 4 true");
         this.tpArena(server, ps[i].getUUID(), i == 0 ? -8.0 : 8.0, 63.0, 0.0, i == 0 ? -90.0F : 90.0F, 0.0F);
      }

      this.phase = Phase.SELECT;
      this.phaseEnd = System.currentTimeMillis() + this.selectSeconds * 1000L;
      this.tellAll(server, "DUEL: " + this.f[0].name + " vs " + this.f[1].name + "! Watch with /watch and bet with /duelbet <player> <amount>.", ChatFormatting.GOLD);
      for (ServerPlayer p : ps) {
         Msg.info(p, "Pick your kit (" + this.selectSeconds + "s). Your own items are saved and come back after the duel.");
         DuelKitMenu.open(p, this);
      }
   }

   // ------------------------------------------------------------------ kits
   public int kitOf(UUID id) {
      int i = this.idxOf(id);
      return i < 0 ? -1 : this.f[i].kit;
   }

   public boolean readyOf(UUID id) {
      int i = this.idxOf(id);
      return i >= 0 && this.f[i].ready;
   }

   public boolean choosing(UUID id) {
      return this.phase == Phase.SELECT && this.idxOf(id) >= 0;
   }

   public void chooseKit(ServerPlayer p, int kit) {
      int i = this.idxOf(p.getUUID());
      if (this.phase == Phase.SELECT && i >= 0 && kit >= 0 && kit < this.kits.size() && !this.f[i].ready) {
         this.f[i].kit = kit;
      }
   }

   public void setReady(ServerPlayer p) {
      int i = this.idxOf(p.getUUID());
      if (this.phase == Phase.SELECT && i >= 0) {
         if (this.f[i].kit < 0) {
            this.f[i].kit = 0;
         }

         this.f[i].ready = true;
         Msg.ok(p, "You are ready with the " + this.kits.get(this.f[i].kit).name() + " kit.");
      }
   }

   private void giveKit(MinecraftServer server, ServerPlayer p, Kit kit) {
      RegistryOps<JsonElement> ops = this.ops(server);
      Inventory inv = p.getInventory();
      inv.clearContent();
      for (JsonElement e : kit.items()) {
         JsonObject o = e.getAsJsonObject();
         Optional<ItemStack> st = ItemStack.CODEC.parse(ops, o.get("item")).result();
         if (st.isPresent()) {
            inv.setItem(o.get("slot").getAsInt(), st.get());
         }
      }

      inv.setChanged();
   }

   // ------------------------------------------------------------------ betting
   public void bet(ServerPlayer p, ServerPlayer on, long amount) {
      int i = this.idxOf(on.getUUID());
      long secondsIntoFight = (System.currentTimeMillis() - this.fightStart) / 1000L;
      String problem = null;
      if (!this.active() || i < 0) {
         problem = "There is no duel with that player right now.";
      } else if (this.idxOf(p.getUUID()) >= 0) {
         problem = "Fighters can't bet.";
      } else if (this.phase == Phase.ENDING || (this.phase == Phase.FIGHT && secondsIntoFight > this.betFightSeconds)) {
         problem = "Betting is closed.";
      } else if (amount < this.minBet || amount > this.maxBet) {
         problem = "Bets must be between " + this.eco.fmt(this.minBet) + " and " + this.eco.fmt(this.maxBet) + ".";
      } else if (this.bets.containsKey(p.getUUID()) && this.bets.get(p.getUUID()).on != i) {
         problem = "You already bet on the other fighter.";
      } else if (!this.eco.take(p.getUUID(), amount)) {
         problem = "You only have " + this.eco.fmt(this.eco.get(p.getUUID())) + ".";
      }

      if (problem != null) {
         Msg.err(p, problem);
      } else {
         Bet b = this.bets.computeIfAbsent(p.getUUID(), k -> new Bet());
         b.on = i;
         b.amount += amount;
         Msg.ok(p, "You bet " + this.eco.fmt(b.amount) + " on " + this.f[i].name + ".");
         this.tellAll(p.level().getServer(), Msg.name(p) + " bet " + this.eco.fmt(amount) + " on " + this.f[i].name + ".", ChatFormatting.YELLOW);
      }
   }

   private long pool() {
      long t = 0L;
      for (Bet b : this.bets.values()) {
         t += b.amount;
      }

      return t;
   }

   // ------------------------------------------------------------------ watching
   public void watch(ServerPlayer p) {
      MinecraftServer server = p.level().getServer();
      if (!this.active()) {
         Msg.err(p, "There is no duel to watch right now.");
      } else if (this.idxOf(p.getUUID()) >= 0) {
         Msg.err(p, "You are fighting!");
      } else if (this.watchers.contains(p.getUUID())) {
         Msg.info(p, "You are already watching. Use /unwatch to leave.");
      } else {
         this.snapshot(p, false);
         this.watchers.add(p.getUUID());
         run(server, "gamemode spectator " + p.getUUID());
         this.tpArena(server, p.getUUID(), 0.5, 69.0, -19.5, 0.0F, 25.0F);
         Msg.ok(p, "You are watching " + this.f[0].name + " vs " + this.f[1].name + ". Use /unwatch to leave, /duelbet to bet.");
      }
   }

   public void unwatch(ServerPlayer p) {
      if (this.watchers.remove(p.getUUID())) {
         this.restore(p);
         Msg.info(p, "You left the colosseum.");
      } else {
         Msg.err(p, "You are not watching a duel.");
      }
   }

   // ------------------------------------------------------------------ fight end
   /** Fabric ALLOW_DEATH: fighters never really die in the arena. Returns false to cancel the death. */
   public boolean onFatalDamage(net.minecraft.world.entity.LivingEntity entity) {
      if (entity instanceof ServerPlayer p) {
         int i = this.idxOf(p.getUUID());
         if (i >= 0 && this.active()) {
            p.setHealth(p.getMaxHealth());
            if (this.phase == Phase.FIGHT) {
               this.finish(p.level().getServer(), this.f[1 - i].id, false);
            }

            return false;
         }
      }

      return true;
   }

   private void finish(MinecraftServer server, UUID winnerId, boolean draw) {
      if (this.phase == Phase.ENDING || this.phase == Phase.NONE) {
         return;
      }

      this.phase = Phase.ENDING;
      this.phaseEnd = System.currentTimeMillis() + 6000L;
      this.winner = winnerId;
      this.drawResult = draw;
      long pool = this.pool();
      if (draw || winnerId == null) {
         for (Map.Entry<UUID, Bet> e : this.bets.entrySet()) {
            this.eco.add(e.getKey(), e.getValue().amount);
         }

         this.tellAll(server, "DUEL: " + this.f[0].name + " vs " + this.f[1].name + " ended in a draw. All bets were returned.", ChatFormatting.YELLOW);
      } else {
         int wi = this.idxOf(winnerId);
         String wn = this.f[wi].name;
         long winnersStake = 0L;
         for (Bet b : this.bets.values()) {
            if (b.on == wi) {
               winnersStake += b.amount;
            }
         }

         long fighterShare = pool;
         if (winnersStake > 0L) {
            fighterShare = pool * this.fighterPercent / 100L;
            long bettors = pool - fighterShare;
            long given = 0L;
            for (Map.Entry<UUID, Bet> e : this.bets.entrySet()) {
               if (e.getValue().on == wi) {
                  long part = bettors * e.getValue().amount / winnersStake;
                  given += part;
                  this.eco.add(e.getKey(), part);
                  ServerPlayer bp = this.player(server, e.getKey());
                  if (bp != null) {
                     Msg.ok(bp, "Your bet on " + wn + " paid " + this.eco.fmt(part) + ".");
                  }
               }
            }

            fighterShare += bettors - given;
         }

         if (fighterShare > 0L) {
            this.eco.add(winnerId, fighterShare);
         }

         ServerPlayer wp = this.player(server, winnerId);
         if (wp != null && fighterShare > 0L) {
            Msg.ok(wp, "You won the duel! The bet pool paid you " + this.eco.fmt(fighterShare) + ".");
         }

         this.tellAll(server, "DUEL: " + wn + " wins against " + this.f[1 - wi].name + "!" + (pool > 0L ? " Bet pool: " + this.eco.fmt(pool) + "." : ""), ChatFormatting.GOLD);
         ServerPlayer lp = this.player(server, this.f[1 - wi].id);
         if (lp != null) {
            Msg.info(lp, "You lost the duel. Your items come back in a moment.");
         }

         this.sendTitle(server, winnerId, "VICTORY", "gold");
      }

      this.bets.clear();
   }

   private void closeDuel(MinecraftServer server) {
      for (int i = 0; i < 2; i++) {
         if (this.f[i] != null) {
            ServerPlayer p = this.player(server, this.f[i].id);
            if (p != null) {
               this.restore(p);
            }
            // offline fighters get theirs back when they join (onJoin)
         }
      }

      for (UUID w : new ArrayList<>(this.watchers)) {
         ServerPlayer p = this.player(server, w);
         if (p != null) {
            this.restore(p);
            Msg.info(p, "The duel is over.");
         }
      }

      this.watchers.clear();
      this.phase = Phase.NONE;
      this.f[0] = null;
      this.f[1] = null;
      this.bets.clear();
   }

   public void playerLeft(ServerPlayer p) {
      this.challenges.remove(p.getUUID());
      this.challenges.values().removeIf(c -> c.from.equals(p.getUUID()));
      int i = this.idxOf(p.getUUID());
      if (i >= 0 && this.active()) {
         if (this.phase == Phase.FIGHT || this.phase == Phase.COUNTDOWN) {
            this.phase = Phase.FIGHT;
            this.finish(p.level().getServer(), this.f[1 - i].id, false);
         } else if (this.phase == Phase.SELECT) {
            this.finish(p.level().getServer(), null, true);
         }
      }

      this.watchers.remove(p.getUUID());     // their file stays, onJoin returns them
   }

   // ------------------------------------------------------------------ once per second
   public void tick(MinecraftServer server) {
      long now = System.currentTimeMillis();
      if (!this.built && this.buildAt > 0L && now >= this.buildAt) {
         this.buildAt = 0L;
         this.buildArena(server);
      }

      Iterator<Map.Entry<UUID, Challenge>> it = this.challenges.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, Challenge> e = it.next();
         if (now >= e.getValue().expires) {
            it.remove();
            ServerPlayer from = this.player(server, e.getValue().from);
            if (from != null) {
               Msg.info(from, "Your duel challenge expired.");
            }
         }
      }

      if (this.phase == Phase.SELECT) {
         boolean bothReady = this.f[0].ready && this.f[1].ready;
         if (bothReady || now >= this.phaseEnd) {
            for (int i = 0; i < 2; i++) {
               ServerPlayer p = this.player(server, this.f[i].id);
               if (p == null) {
                  this.finish(server, this.f[1 - i].id, false);
                  return;
               }

               if (this.f[i].kit < 0) {
                  this.f[i].kit = 0;
               }

               this.giveKit(server, p, this.kits.get(this.f[i].kit));
               p.closeContainer();
               Msg.info(p, "Get ready!");
            }

            this.phase = Phase.COUNTDOWN;
            this.countdown = this.countdownSeconds;
            this.phaseEnd = now;
         }
      } else if (this.phase == Phase.COUNTDOWN) {
         if (this.countdown > 0) {
            for (int i = 0; i < 2; i++) {
               this.sendTitle(server, this.f[i].id, String.valueOf(this.countdown), "yellow");
            }

            this.countdown--;
         } else {
            for (int i = 0; i < 2; i++) {
               run(server, "effect clear " + this.f[i].id + " minecraft:resistance");
               this.sendTitle(server, this.f[i].id, "FIGHT!", "red");
            }

            this.phase = Phase.FIGHT;
            this.fightStart = now;
            this.phaseEnd = now + this.fightSeconds * 1000L;
            this.tellAll(server, "DUEL started! " + this.f[0].name + " vs " + this.f[1].name + " - betting stays open for " + this.betFightSeconds + "s.", ChatFormatting.RED);
         }
      } else if (this.phase == Phase.FIGHT) {
         if (this.player(server, this.f[0].id) == null || this.player(server, this.f[1].id) == null) {
            return;
         }

         if (now >= this.phaseEnd) {
            this.finish(server, null, true);
         }
      } else if (this.phase == Phase.ENDING && now >= this.phaseEnd) {
         this.closeDuel(server);
      }
   }

   // ------------------------------------------------------------------ commands
   public void register(CommandDispatcher<CommandSourceStack> d) {
      LiteralArgumentBuilder<CommandSourceStack> duel = Commands.literal("duel");
      duel.executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, "Duel: /duel <player>, /duel accept, /duel decline, /watch, /unwatch, /duelbet <player> <amount>");
         return 1;
      });
      duel.then(Commands.literal("accept").executes(ctx -> {
         this.accept(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      duel.then(Commands.literal("decline").executes(ctx -> {
         this.decline(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      duel.then(Commands.literal("kit").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         if (this.choosing(p.getUUID())) {
            DuelKitMenu.open(p, this);
         } else {
            Msg.err(p, "You are not choosing a kit right now.");
         }

         return 1;
      }));
      duel.then(
         Commands.literal("buildarena")
            .requires(Commands.hasPermission(new net.minecraft.server.permissions.PermissionCheck.Require(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)))
            .executes(ctx -> {
               this.rebuildArena(ctx.getSource().getServer());
               ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Building the colosseum in a few seconds..."), true);
               return 1;
            })
      );
      duel.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
         this.challenge(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"));
         return 1;
      }));
      d.register(duel);
      d.register(Commands.literal("watch").executes(ctx -> {
         this.watch(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      d.register(Commands.literal("unwatch").executes(ctx -> {
         this.unwatch(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      d.register(
         Commands.literal("duelbet")
            .then(
               Commands.argument("player", EntityArgument.player())
                  .then(Commands.argument("amount", LongArgumentType.longArg(1L)).executes(ctx -> {
                     this.bet(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount"));
                     return 1;
                  }))
            )
      );
   }
}
