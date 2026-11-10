package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionCheck.Require;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** /rank and /jumpscare. */
final class ExtraCommands {
   private ExtraCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> d, RankManager ranks) {
      // ---- /rank ----
      SuggestionProvider<CommandSourceStack> colors = (ctx, builder) -> SharedSuggestionProvider.suggest(RankManager.colorNames(), builder);
      SuggestionProvider<CommandSourceStack> presets = (ctx, builder) -> SharedSuggestionProvider.suggest(RankManager.presetIds(), builder);
      LiteralArgumentBuilder<CommandSourceStack> rank = Commands.literal("rank");
      rank.executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         if (ranks.has(p.getUUID())) {
            Msg.info(p, "Your rank: " + ranks.rankName(p.getUUID()));
            return 1;
         } else {
            Msg.info(p, "You don't have a rank yet.");
            return 0;
         }
      });

      // /rank set <player> <owner|admin|mod|helper|builder|dev|youtube|twitch|staff|mvp|vip_plus|vip|player>
      LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
      set.requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      set.then(
         Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("rank", StringArgumentType.word()).suggests(presets).executes(ctx -> {
               ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
               String id = StringArgumentType.getString(ctx, "rank").toLowerCase();
               if (!ranks.setPreset(ctx.getSource().getServer(), target, id)) {
                  ctx.getSource().sendFailure(Component.literal("Unknown rank '" + id + "'. Choose: " + String.join(", ", RankManager.presetIds())));
                  return 0;
               } else {
                  ranks.save();
                  ctx.getSource().sendSuccess(() -> Component.literal("Gave " + Msg.name(target) + " the rank " + id + "."), true);
                  Msg.info(target, "You now have the rank " + ranks.rankName(target.getUUID()) + "!");
                  return 1;
               }
            }))
      );

      // /rank custom <player> <color> <text>   - free-text rank, always plain colored text
      LiteralArgumentBuilder<CommandSourceStack> custom = Commands.literal("custom");
      custom.requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      custom.then(
         Commands.argument("player", EntityArgument.player())
            .then(
               Commands.argument("color", StringArgumentType.word())
                  .suggests(colors)
                  .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                     ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                     String colorName = StringArgumentType.getString(ctx, "color").toLowerCase();
                     String name = StringArgumentType.getString(ctx, "name").trim();
                     ChatFormatting color = RankManager.parseColor(colorName);
                     if (color == null) {
                        ctx.getSource().sendFailure(Component.literal("Unknown color '" + colorName + "'."));
                        return 0;
                     } else if (name.isEmpty() || name.length() > 16) {
                        ctx.getSource().sendFailure(Component.literal("Rank name must be 1-16 characters."));
                        return 0;
                     } else {
                        ranks.setCustom(ctx.getSource().getServer(), target, color, name);
                        ranks.save();
                        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + Msg.name(target) + " the rank [" + name + "]."), true);
                        Msg.colored(target, "You now have the rank [" + name + "]!", color);
                        return 1;
                     }
                  }))
            )
      );

      LiteralArgumentBuilder<CommandSourceStack> clear = Commands.literal("clear");
      clear.requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      clear.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
         ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
         boolean had = ranks.clear(ctx.getSource().getServer(), target);
         ranks.save();
         if (!had) {
            ctx.getSource().sendFailure(Component.literal(Msg.name(target) + " has no rank."));
            return 0;
         } else {
            ctx.getSource().sendSuccess(() -> Component.literal("Removed the rank of " + Msg.name(target) + "."), true);
            return 1;
         }
      }));

      // /rank badges <on|off>  - badge icons (needs the resource pack) or plain [VIP] text
      LiteralArgumentBuilder<CommandSourceStack> badges = Commands.literal("badges");
      badges.requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      badges.then(Commands.argument("mode", StringArgumentType.word())
         .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
         .executes(ctx -> {
            String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
            if (!mode.equals("on") && !mode.equals("off")) {
               ctx.getSource().sendFailure(Component.literal("Use /rank badges on  or  /rank badges off"));
               return 0;
            } else {
               ranks.setBadges(ctx.getSource().getServer(), mode.equals("on"));
               ctx.getSource().sendSuccess(() -> Component.literal("Rank badges are now " + mode + "."), true);
               return 1;
            }
         }));

      rank.then(set);
      rank.then(custom);
      rank.then(clear);
      rank.then(badges);
      d.register(rank);

      // ---- /lukisan <name> (OP only): gives a custom painting item, e.g. /lukisan foto_2 ----
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("lukisan")
               .requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER))))
            .then(
               Commands.argument("name", StringArgumentType.word())
                  .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("foto_2", "photo"), builder))
                  .executes(ctx -> {
                     ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                     String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                     if (!name.matches("[a-z0-9_]+")) {
                        ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Invalid painting name."));
                        return 0;
                     }

                     net.minecraft.server.MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
                     server.getCommands()
                        .performPrefixedCommand(
                           server.createCommandSourceStack().withSuppressedOutput(),
                           "give " + p.getUUID() + " minecraft:painting[minecraft:painting/variant=\"sim_smp:" + name + "\"] 1"
                        );
                     ((CommandSourceStack)ctx.getSource())
                        .sendSuccess(() -> Component.literal("Gave you the painting " + name + ". Place it on a free wall (the size of the painting must fit)."), false);
                     return 1;
                  })
            )
      );

      // ---- /jumpscare <players> (OP only) ----
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("jumpscare")
               .requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER))))
            .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> {
               int n = 0;

               for (ServerPlayer target : EntityArgument.getPlayers(ctx, "targets")) {
                  scare(target);
                  n++;
               }

               int count = n;
               ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Jumpscared " + count + " player(s)."), true);
               return n;
            }))
      );
   }

   /** The vanilla elder-guardian scare (face + curse sound) plus a red title and a short blindness. */
   private static void scare(ServerPlayer p) {
      p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT, 1.0F));
      p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 10));
      p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BOO!").withStyle(new ChatFormatting[]{ChatFormatting.DARK_RED, ChatFormatting.BOLD})));
      p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, true, false, false));
   }
}
