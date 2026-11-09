package com.simsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class Msg {
   private Msg() {
   }

   private static MutableComponent prefix() {
      return Component.literal("[SIM SMP] ").withStyle(new ChatFormatting[]{ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD});
   }

   static void ok(ServerPlayer p, String text) {
      p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.GREEN)));
   }

   static void info(ServerPlayer p, String text) {
      p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
   }

   static void err(ServerPlayer p, String text) {
      p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
   }

   static void colored(ServerPlayer p, String text, ChatFormatting color) {
      p.sendSystemMessage(prefix().append(Component.literal(text).withStyle(color)));
   }

   static void broadcast(MinecraftServer server, String text, ChatFormatting color) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         colored(p, text, color);
      }
   }

   static String name(ServerPlayer p) {
      return p.getName().getString();
   }

   static String pretty(String id) {
      String path = id.contains(":") ? id.substring(id.indexOf(58) + 1) : id;
      StringBuilder sb = new StringBuilder();

      for (String part : path.split("_")) {
         if (!part.isEmpty()) {
            if (sb.length() > 0) {
               sb.append(' ');
            }

            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
         }
      }

      return sb.toString();
   }
}
