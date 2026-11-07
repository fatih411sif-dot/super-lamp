package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
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

/** /sit, /rank and /jumpscare. */
final class ExtraCommands {
   private ExtraCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> d, RankManager ranks) {
      // ---- /sit (everyone) ----
      d.register((LiteralArgumentBuilder)Commands.literal("sit").executes(ctx -> Sit.toggle(((CommandSourceStack)ctx.getSource()).getPlayerOrException())));

      // ---- /rank ----
      List<String> colorNames = new ArrayList<>();

      for (ChatFormatting f : ChatFormatting.values()) {
         if (f.isColor()) {
            colorNames.add(f.getName());
         }
      }

      SuggestionProvider<CommandSourceStack> colors = (ctx, builder) -> SharedSuggestionProvider.suggest(colorNames, builder);
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
      LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
      set.requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      set.then(
         Commands.argument("player", EntityArgument.player())
            .then(
               Commands.argument("color", StringArgumentType.word())
                  .suggests(colors)
                  .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                     ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                     String colorName = StringArgumentType.getString(ctx, "color").toLowerCase();
                     String name = StringArgumentType.getString(ctx, "name").trim();
                     ChatFormatting color = ChatFormatting.getByName(colorName);
                     if (color == null || !color.isColor()) {
                        ctx.getSource().sendFailure(Component.literal("Unknown color '" + colorName + "'."));
                        return 0;
                     } else if (name.isEmpty() || name.length() > 16) {
                        ctx.getSource().sendFailure(Component.literal("Rank name must be 1-16 characters."));
                        return 0;
                     } else {
                        ranks.set(ctx.getSource().getServer(), target, color, name);
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
      rank.then(set);
      rank.then(clear);
      d.register(rank);

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
