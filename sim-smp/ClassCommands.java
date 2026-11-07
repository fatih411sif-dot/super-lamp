package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionCheck.Require;

final class ClassCommands {
   private ClassCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> d, ClassManager classes, SimConfig cfg) {
      SuggestionProvider<CommandSourceStack> classIds = (ctx, builder) -> SharedSuggestionProvider.suggest(
         ClassDefs.all().stream().map(ClassDefs.ClassDef::id).toList(), builder
      );
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("class").executes(ctx -> {
               ClassMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), classes, cfg);
               return 1;
            }))
            .then(
               Commands.literal("replace")
                  .then(
                     Commands.argument("old", StringArgumentType.word())
                        .suggests(classIds)
                        .then(Commands.argument("new", StringArgumentType.word()).suggests(classIds).executes(ctx -> {
                           ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                           String oldId = StringArgumentType.getString(ctx, "old").toLowerCase();
                           String newId = StringArgumentType.getString(ctx, "new").toLowerCase();
                           String error = classes.replacePick(p.getUUID(), oldId, newId);
                           if (error != null) {
                              Msg.err(p, error);
                              return 0;
                           } else {
                              Msg.ok(
                                 p,
                                 "Swapped "
                                    + oldId
                                    + " for "
                                    + newId
                                    + " ("
                                    + cfg.currencySymbol
                                    + String.format(Locale.US, "%,d", cfg.classChangeFee)
                                    + " paid)."
                              );
                              return 1;
                           }
                        }))
                  )
            )
      );
      SuggestionProvider<CommandSourceStack> abilityNames = (ctx, builder) -> {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
         return SharedSuggestionProvider.suggest(classes.unlockedAbilities(p.getUUID()).stream().map(ClassDefs.Skill::id).toList(), builder);
      };
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("ability")
               .executes(
                  ctx -> {
                     ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                     List<ClassDefs.Skill> list = classes.unlockedAbilities(p.getUUID());
                     if (list.isEmpty()) {
                        Msg.info(p, "You have no abilities yet. Unlock them in the skill tree with /class.");
                        return 0;
                     } else {
                        Msg.info(p, "Your abilities (switch with /ability <name> <true|false>):");

                        for (ClassDefs.Skill s : list) {
                           boolean on = classes.abilitySwitchedOn(p.getUUID(), s.id());
                           p.sendSystemMessage(
                              Component.literal(" - " + s.id() + ": " + (on ? "ON" : "off") + "  (" + s.name() + ")")
                                 .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                           );
                        }

                        return 1;
                     }
                  }
               ))
            .then(
               Commands.argument("name", StringArgumentType.word())
                  .suggests(abilityNames)
                  .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                     ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                     String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                     boolean on = BoolArgumentType.getBool(ctx, "enabled");
                     String error = classes.setAbility(p.getUUID(), name, on);
                     if (error != null) {
                        Msg.err(p, error);
                        return 0;
                     } else {
                        Msg.ok(p, name + " is now " + (on ? "ON" : "off") + ".");
                        return 1;
                     }
                  }))
            )
      );
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("classadmin")
               .requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER))))
            .then(
               Commands.literal("addxp")
                  .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", LongArgumentType.longArg(1L)).executes(ctx -> {
                     ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                     long amount = LongArgumentType.getLong(ctx, "amount");
                     if (classes.active(target.getUUID()).isEmpty()) {
                        ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Msg.name(target) + " has no class equipped."));
                        return 0;
                     } else {
                        classes.addXp(target, amount);
                        ((CommandSourceStack)ctx.getSource())
                           .sendSuccess(() -> Component.literal("Gave " + amount + " Class XP to " + Msg.name(target) + "."), true);
                        return 1;
                     }
                  })))
            )
            .then(
               Commands.literal("reset")
                  .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> resetClass(ctx, classes)))
            )
      );
      // /class reset <player>  - same thing, OP only
      d.register(
         (LiteralArgumentBuilder)Commands.literal("class")
            .then(
               ((LiteralArgumentBuilder)Commands.literal("reset").requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER))))
                  .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> resetClass(ctx, classes)))
            )
      );
   }

   private static int resetClass(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, ClassManager classes) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      boolean had = classes.resetPlayer(target.getUUID());
      ClassEffects.forget(target);
      classes.save();
      if (!had) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Msg.name(target) + " has no class data to reset."));
         return 0;
      } else {
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Reset all class data of " + Msg.name(target) + "."), true);
         Msg.info(target, "An admin reset your classes. Use /class to choose again.");
         return 1;
      }
   }

   static String sidebarText(ClassManager classes, ServerPlayer p) {
      String active = classes.active(p.getUUID());
      ClassDefs.ClassDef def = ClassDefs.get(active);
      return def == null ? "- (use /class)" : classes.availableXp(p.getUUID(), active) + " (" + def.name() + " Lv" + classes.level(p.getUUID(), active) + ")";
   }
}
