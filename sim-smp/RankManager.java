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
 * OP-assigned ranks (VIP, MVP, Owner...). A rank shows as a prefix in chat, the tab list and above the
 * player's head (via a vanilla scoreboard team) and on the sidebar "Player:" line.
 */
public final class RankManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Type TYPE = (new TypeToken<HashMap<String, RankManager.Rank>>() {}).getType();
   private static final String TEAM_PREFIX = "sr_";
   private final Path file;
   private final Map<UUID, RankManager.Rank> ranks = new HashMap<>();
   private boolean dirty;

   public RankManager(Path dir) {
      this.file = dir.resolve("ranks.json");
   }

   public synchronized void load() {
      this.ranks.clear();
      if (Files.exists(this.file)) {
         try (Reader r = Files.newBufferedReader(this.file)) {
            Map<String, RankManager.Rank> raw = GSON.fromJson(r, TYPE);
            if (raw != null) {
               for (Map.Entry<String, RankManager.Rank> e : raw.entrySet()) {
                  try {
                     if (e.getValue() != null && e.getValue().name != null && !e.getValue().name.isBlank()) {
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

   public synchronized boolean has(UUID id) {
      return this.ranks.containsKey(id);
   }

   public synchronized String rankName(UUID id) {
      RankManager.Rank r = this.ranks.get(id);
      return r == null ? "" : r.name;
   }

   /** "[VIP] " or "" - for plain-text places like the sidebar. */
   public synchronized String prefix(UUID id) {
      RankManager.Rank r = this.ranks.get(id);
      return r == null ? "" : "[" + r.name + "] ";
   }

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

   public synchronized void set(MinecraftServer server, ServerPlayer p, ChatFormatting color, String name) {
      RankManager.Rank r = new RankManager.Rank();
      r.name = name;
      r.color = nameOf(color);
      this.ranks.put(p.getUUID(), r);
      this.dirty = true;
      this.apply(server, p);
   }

   public synchronized boolean clear(MinecraftServer server, ServerPlayer p) {
      RankManager.Rank old = this.ranks.remove(p.getUUID());
      this.dirty = true;
      this.unapply(server, p);
      return old != null;
   }

   /** Puts the player in the scoreboard team that carries their rank prefix. Safe to call repeatedly. */
   public synchronized void apply(MinecraftServer server, ServerPlayer p) {
      RankManager.Rank r = this.ranks.get(p.getUUID());
      if (r != null) {
         Scoreboard board = server.getScoreboard();
         String holder = p.getScoreboardName();
         ChatFormatting color = colorOf(r.color);
         String teamName = TEAM_PREFIX + Integer.toHexString((nameOf(color) + "|" + r.name).hashCode());
         PlayerTeam team = board.getPlayerTeam(teamName);
         if (team == null) {
            team = board.addPlayerTeam(teamName);
         }

         team.setPlayerPrefix(Component.literal("[" + r.name + "] ").withStyle(color));
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

      public Rank() {
      }
   }
}
