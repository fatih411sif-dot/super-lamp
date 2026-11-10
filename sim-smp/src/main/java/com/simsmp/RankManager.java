package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * OP-assigned ranks. A rank shows as a prefix in chat, the tab list and above the player's head (via a vanilla
 * scoreboard team) and on the sidebar "Player:" line.
 *
 * Preset ranks (owner, admin, vip...) are drawn as small badge icons from the SIM SMP resource pack (font glyphs
 * U+E300 + index, see PRESETS - make_pack.py uses the same order). Players without the pack (and Bedrock players)
 * would see empty boxes, so badges can be switched off with /rank badges off, which shows plain "[VIP]" text instead.
 * Custom ranks (/rank custom) are always plain colored text.
 */
public final class RankManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Type TYPE = (new TypeToken<HashMap<String, RankManager.Rank>>() {}).getType();
   private static final String TEAM_PREFIX = "sr_";
   /** id, label, text color. The ORDER is the glyph number: U+E300 + index. */
   private static final String[][] PRESETS = new String[][]{
      // ---- medieval hierarchy (highest to lowest), given with /rank set or the /hierarchy menu
      {"king", "KING", "gold"},
      {"queen", "QUEEN", "light_purple"},
      {"prince", "PRINCE", "aqua"},
      {"duke", "DUKE", "red"},
      {"paladin", "PALADIN", "aqua"},
      {"lord", "LORD", "green"},
      {"count", "COUNT", "blue"},
      {"baron", "BARON", "dark_aqua"},
      {"knight", "KNIGHT", "white"},
      {"squire", "SQUIRE", "dark_green"},
      {"merchant", "MERCHANT", "yellow"},
      {"peasant", "PEASANT", "gray"},
      {"bandit", "BANDIT", "dark_red"},
      // ---- class ranks: from here on they are shown automatically for the class a player has equipped (ids = class ids)
      {"miner", "MINER", "gray"},
      {"farmer", "FARMER", "green"},
      {"lumberjack", "LUMBER", "gold"},
      {"hunter", "HUNTER", "red"},
      {"archer", "ARCHER", "green"},
      {"berserker", "BERSERK", "dark_red"},
      {"warrior", "WARRIOR", "aqua"},
      {"mage", "MAGE", "light_purple"}
   };
   /** the first MANUAL_PRESETS entries can be given with /rank set; the rest follow the player's class */
   private static final int MANUAL_PRESETS = 13;
   private static RankManager instance;
   private ClassManager classes;
   private final Path file;
   private final Path styleFile;
   private final Map<UUID, RankManager.Rank> ranks = new HashMap<>();
   private boolean dirty;
   private boolean badges = true;

   public RankManager(Path dir) {
      this.file = dir.resolve("ranks.json");
      this.styleFile = dir.resolve("rank_style.txt");
      instance = this;
   }

   /** Lets the rank follow the class a player has equipped (when no rank was set by an OP). */
   public void setClasses(ClassManager classes) {
      this.classes = classes;
   }

   /** Re-applies one player's rank, e.g. after they changed class. Does nothing before the mod is set up. */
   public static void refresh(ServerPlayer p) {
      if (instance != null) {
         instance.apply(p.level().getServer(), p);
      }
   }

   /** The rank shown for a player: the one an OP set, otherwise the one of their equipped class, otherwise none. */
   private RankManager.Rank effective(UUID id) {
      RankManager.Rank r = this.ranks.get(id);
      if (r != null) {
         return r;
      } else {
         String active = this.classes == null ? null : this.classes.active(id);
         int i = active == null || active.isEmpty() ? -1 : presetIndex(active);
         if (i < 0) {
            return null;
         } else {
            RankManager.Rank c = new RankManager.Rank();
            c.name = PRESETS[i][1];
            c.color = PRESETS[i][2];
            c.preset = active;
            return c;
         }
      }
   }

   public synchronized void load() {
      this.ranks.clear();
      if (Files.exists(this.styleFile)) {
         try {
            this.badges = !Files.readString(this.styleFile).toLowerCase().contains("false");
         } catch (IOException var8) {
         }
      }

      if (Files.exists(this.file)) {
         try (Reader r = Files.newBufferedReader(this.file)) {
            Map<String, RankManager.Rank> raw = GSON.fromJson(r, TYPE);
            if (raw != null) {
               for (Map.Entry<String, RankManager.Rank> e : raw.entrySet()) {
                  try {
                     RankManager.Rank rk = e.getValue();
                     boolean oldPreset = rk != null && rk.preset != null && presetIndex(rk.preset) < 0;   // removed old roles (owner, vip...) are dropped
                     if (rk != null && !oldPreset && rk.name != null && !rk.name.isBlank()) {
                        this.ranks.put(UUID.fromString(e.getKey()), e.getValue());
                     }
                  } catch (IllegalArgumentException var5) {
                  }
               }
            }
         } catch (RuntimeException | IOException var7) {
            SimSmp.LOGGER.error("ranks.json is unreadable - keeping a copy and starting fresh", var7);

            try {
               Files.move(this.file, this.file.resolveSibling("ranks.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException var4) {
            }
         }
      }
   }

   public synchronized void save() {
      if (this.dirty) {
         this.dirty = false;
         Map<String, RankManager.Rank> raw = new HashMap<>();
         this.ranks.forEach((k, v) -> raw.put(k.toString(), v));

         try {
            Files.createDirectories(this.file.getParent());
            Path tmp = this.file.resolveSibling("ranks.json.tmp");
            Files.writeString(tmp, GSON.toJson(raw));
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
         } catch (IOException var4) {
            this.dirty = true;
            SimSmp.LOGGER.error("Could not save ranks.json", var4);
         }
      }
   }

   // ---------- presets ----------

   public static List<String> presetIds() {
      List<String> out = new ArrayList<>();

      for (int i = 0; i < MANUAL_PRESETS; i++) {
         out.add(PRESETS[i][0]);
      }

      return out;
   }

   private static int presetIndex(String id) {
      if (id != null) {
         for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0].equals(id)) {
               return i;
            }
         }
      }

      return -1;
   }

   private static String glyphOf(String id) {
      return String.valueOf((char)(0xE300 + presetIndex(id)));
   }

   public synchronized boolean badgesOn() {
      return this.badges;
   }

   // ---------- queries ----------

   public synchronized boolean has(UUID id) {
      return this.effective(id) != null;
   }

   /** preset id of the shown rank (null for none or a custom one) */
   public synchronized String presetOf(UUID id) {
      RankManager.Rank r = this.effective(id);
      return r == null ? null : r.preset;
   }

   public synchronized String rankName(UUID id) {
      RankManager.Rank r = this.effective(id);
      return r == null ? "" : r.name;
   }

   /** Badge glyph + space, or "[VIP] " - for plain-text places like the sidebar. */
   public synchronized String prefix(UUID id) {
      RankManager.Rank r = this.effective(id);
      if (r == null) {
         return "";
      } else {
         return this.badges && presetIndex(r.preset) >= 0 ? glyphOf(r.preset) + " " : "[" + r.name + "] ";
      }
   }

   // ---------- colors (own table, ChatFormatting helpers changed in 26.3) ----------

   private static final Map<String, ChatFormatting> COLORS = new LinkedHashMap<>();

   static {
      COLORS.put("black", ChatFormatting.BLACK);
      COLORS.put("dark_blue", ChatFormatting.DARK_BLUE);
      COLORS.put("dark_green", ChatFormatting.DARK_GREEN);
      COLORS.put("dark_aqua", ChatFormatting.DARK_AQUA);
      COLORS.put("dark_red", ChatFormatting.DARK_RED);
      COLORS.put("dark_purple", ChatFormatting.DARK_PURPLE);
      COLORS.put("gold", ChatFormatting.GOLD);
      COLORS.put("gray", ChatFormatting.GRAY);
      COLORS.put("dark_gray", ChatFormatting.DARK_GRAY);
      COLORS.put("blue", ChatFormatting.BLUE);
      COLORS.put("green", ChatFormatting.GREEN);
      COLORS.put("aqua", ChatFormatting.AQUA);
      COLORS.put("red", ChatFormatting.RED);
      COLORS.put("light_purple", ChatFormatting.LIGHT_PURPLE);
      COLORS.put("yellow", ChatFormatting.YELLOW);
      COLORS.put("white", ChatFormatting.WHITE);
   }

   public static List<String> colorNames() {
      return new ArrayList<>(COLORS.keySet());
   }

   /** Color for a typed name, or null if it isn't one of the 16 colors. */
   public static ChatFormatting parseColor(String name) {
      return name == null ? null : COLORS.get(name.toLowerCase());
   }

   public static ChatFormatting colorOf(String name) {
      ChatFormatting f = parseColor(name);
      return f != null ? f : ChatFormatting.WHITE;
   }

   private static String nameOf(ChatFormatting color) {
      for (Map.Entry<String, ChatFormatting> e : COLORS.entrySet()) {
         if (e.getValue() == color) {
            return e.getKey();
         }
      }

      return "white";
   }

   // ---------- changes ----------

   /** A preset rank (badge). Returns false if the id is not a preset. */
   public synchronized boolean setPreset(MinecraftServer server, ServerPlayer p, String presetId) {
      int i = presetIndex(presetId);
      if (i < 0 || i >= MANUAL_PRESETS) {
         return false;
      } else {
         RankManager.Rank r = new RankManager.Rank();
         r.name = PRESETS[i][1];
         r.color = PRESETS[i][2];
         r.preset = presetId;
         this.ranks.put(p.getUUID(), r);
         this.dirty = true;
         this.apply(server, p);
         return true;
      }
   }

   /** A free-text rank in one of the 16 colors. */
   public synchronized void setCustom(MinecraftServer server, ServerPlayer p, ChatFormatting color, String name) {
      RankManager.Rank r = new RankManager.Rank();
      r.name = name;
      r.color = nameOf(color);
      r.preset = null;
      this.ranks.put(p.getUUID(), r);
      this.dirty = true;
      this.apply(server, p);
   }

   public synchronized boolean clear(MinecraftServer server, ServerPlayer p) {
      RankManager.Rank old = this.ranks.remove(p.getUUID());
      this.dirty = true;
      this.apply(server, p);
      return old != null;
   }

   /** Badge icons on or off for everybody (off = plain "[VIP]" text). */
   public synchronized void setBadges(MinecraftServer server, boolean on) {
      this.badges = on;

      try {
         Files.createDirectories(this.styleFile.getParent());
         Files.writeString(this.styleFile, "badges=" + on + "\n");
      } catch (IOException var5) {
         SimSmp.LOGGER.error("Could not save rank_style.txt", var5);
      }

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         this.apply(server, p);
      }
   }

   /** Puts the player in the scoreboard team that carries their rank prefix. Safe to call repeatedly. */
   public synchronized void apply(MinecraftServer server, ServerPlayer p) {
      RankManager.Rank r = this.effective(p.getUUID());
      if (r == null) {
         this.unapply(server, p);
      } else {
         Scoreboard board = server.getScoreboard();
         String holder = p.getScoreboardName();
         ChatFormatting color = colorOf(r.color);
         boolean badge = this.badges && presetIndex(r.preset) >= 0;
         String key = badge ? "badge|" + r.preset : nameOf(color) + "|" + r.name;
         String teamName = TEAM_PREFIX + Integer.toHexString(key.hashCode());
         PlayerTeam team = board.getPlayerTeam(teamName);
         if (team == null) {
            team = board.addPlayerTeam(teamName);
         }

         team.setPlayerPrefix(
            badge
               ? Component.literal(glyphOf(r.preset) + " ").withStyle(ChatFormatting.WHITE)
               : Component.literal("[" + r.name + "] ").withStyle(color)
         );
         PlayerTeam current = board.getPlayersTeam(holder);
         if (current != team) {
            if (current != null) {
               board.removePlayerFromTeam(holder, current);
               this.dropIfEmpty(board, current);
            }

            board.addPlayerToTeam(holder, team);
         }
      }
   }

   private void unapply(MinecraftServer server, ServerPlayer p) {
      Scoreboard board = server.getScoreboard();
      PlayerTeam current = board.getPlayersTeam(p.getScoreboardName());
      if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
         board.removePlayerFromTeam(p.getScoreboardName(), current);
         this.dropIfEmpty(board, current);
      }
   }

   private void dropIfEmpty(Scoreboard board, PlayerTeam team) {
      if (team.getName().startsWith(TEAM_PREFIX) && team.getPlayers().isEmpty()) {
         board.removePlayerTeam(team);
      }
   }

   public static final class Rank {
      public String name = "";
      public String color = "white";
      /** preset id (owner, vip...) or null for a custom rank */
      public String preset = null;

      public Rank() {
      }
   }
}
