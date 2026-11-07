package com.simsmp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

public final class SidebarManager {
   private static final String OBJECTIVE_NAME = "sim_smp_side";
   private static final int LINES = 10;
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final ClassManager classes;
   private final RankManager ranks;
   private final Objective objective;
   private final Map<UUID, String[]> cache = new HashMap<>();

   public SidebarManager(SimConfig cfg, EconomyManager eco, ClassManager classes, RankManager ranks) {
      this.cfg = cfg;
      this.eco = eco;
      this.classes = classes;
      this.ranks = ranks;
      Component title = Component.literal(cfg.scoreboardTitle).withStyle(new ChatFormatting[]{ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD});
      this.objective = new Objective(new Scoreboard(), "sim_smp_side", ObjectiveCriteria.DUMMY, title, RenderType.INTEGER, false, BlankFormat.INSTANCE);
   }

   public void show(MinecraftServer server, ServerPlayer p) {
      p.connection.send(new ClientboundSetObjectivePacket(this.objective, 0));
      p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, this.objective));
      this.cache.remove(p.getUUID());
      this.update(server, p);
   }

   public void forget(ServerPlayer p) {
      this.cache.remove(p.getUUID());
   }

   public void update(MinecraftServer server, ServerPlayer p) {
      Component[] lines = this.build(server, p);
      String[] old = this.cache.computeIfAbsent(p.getUUID(), k -> new String[10]);

      for (int i = 0; i < 10; i++) {
         String text = lines[i].getString();
         if (!text.equals(old[i])) {
            old[i] = text;
            p.connection.send(new ClientboundSetScorePacket("sim_line_" + i, "sim_smp_side", 10 - i, Optional.of(lines[i]), Optional.of(BlankFormat.INSTANCE)));
         }
      }
   }

   private Component[] build(MinecraftServer server, ServerPlayer p) {
      int ping = p.connection.latency();
      ChatFormatting pingColor = ping < 80 ? ChatFormatting.GREEN : (ping < 150 ? ChatFormatting.YELLOW : ChatFormatting.RED);
      int hearts = Math.round(p.getMaxHealth() / 2.0F);
      int deaths = p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
      int playTicks = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
      return new Component[]{
         Component.literal(" "),
         line("Player: ", this.ranks.prefix(p.getUUID()) + Msg.name(p), ChatFormatting.WHITE),
         line("Money: ", this.eco.fmt(this.eco.get(p.getUUID())), ChatFormatting.GOLD),
         line("Class XP: ", ClassCommands.sidebarText(this.classes, p), ChatFormatting.LIGHT_PURPLE),
         line("Hearts: ", String.valueOf(hearts), ChatFormatting.RED),
         line("Deaths: ", String.valueOf(deaths), ChatFormatting.DARK_RED),
         line("Playtime: ", playtime(playTicks), ChatFormatting.AQUA),
         line("Ping: ", ping + "ms", pingColor),
         line("Online: ", server.getPlayerCount() + "/" + server.getMaxPlayers(), ChatFormatting.GREEN),
         Component.literal("  ")
      };
   }

   private static MutableComponent line(String label, String value, ChatFormatting valueColor) {
      return Component.literal(label).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(valueColor));
   }

   private static String playtime(int ticks) {
      long minutes = ticks / 1200L;
      return minutes / 60L + "h " + minutes % 60L + "m";
   }
}
