package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionCheck.Require;
import net.minecraft.world.item.ItemStack;

final class RedeemCommands {
   private RedeemCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> d, RedeemManager rm) {
      d.register((LiteralArgumentBuilder)Commands.literal("redeem").then(Commands.argument("code", StringArgumentType.word()).executes(ctx -> {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
         rm.redeem(p, StringArgumentType.getString(ctx, "code"));
         return 1;
      })));
      SuggestionProvider<CommandSourceStack> codes = (ctx, builder) -> SharedSuggestionProvider.suggest(rm.codeNames(), builder);
      LiteralArgumentBuilder<CommandSourceStack> admin = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("rcode")
         .requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER)));
      admin.then(
         Commands.literal("create")
            .then(
               Commands.argument("code", StringArgumentType.word())
                  .then(
                     Commands.argument("money", LongArgumentType.longArg(0L))
                        .executes(
                           ctx -> {
                              String code = StringArgumentType.getString(ctx, "code");
                              long money = LongArgumentType.getLong(ctx, "money");
                              return done(
                                 (CommandSourceStack)ctx.getSource(),
                                 rm.create(code, money),
                                 "Created code "
                                    + code.toUpperCase()
                                    + " worth "
                                    + money
                                    + ". Now add items with /rcode item, and limits with /rcode maxuses and /rcode expire."
                              );
                           }
                        )
                  )
            )
      );
      admin.then(Commands.literal("delete").then(codeArg(codes).executes(ctx -> {
         String code = StringArgumentType.getString(ctx, "code");
         return done((CommandSourceStack)ctx.getSource(), rm.delete(code), "Deleted code " + code.toUpperCase() + ".");
      })));
      admin.then(Commands.literal("money").then(codeArg(codes).then(Commands.argument("amount", LongArgumentType.longArg(0L)).executes(ctx -> {
         String code = StringArgumentType.getString(ctx, "code");
         long amount = LongArgumentType.getLong(ctx, "amount");
         return done((CommandSourceStack)ctx.getSource(), rm.setMoney(code, amount), "Money reward of " + code.toUpperCase() + " is now " + amount + ".");
      }))));
      admin.then(
         Commands.literal("maxuses")
            .then(
               codeArg(codes)
                  .then(
                     Commands.argument("uses", IntegerArgumentType.integer(0))
                        .executes(
                           ctx -> {
                              String code = StringArgumentType.getString(ctx, "code");
                              int uses = IntegerArgumentType.getInteger(ctx, "uses");
                              return done(
                                 (CommandSourceStack)ctx.getSource(),
                                 rm.setMaxUses(code, uses),
                                 code.toUpperCase() + (uses == 0 ? " can be used by unlimited players." : " can be used by " + uses + " players in total.")
                              );
                           }
                        )
                  )
            )
      );
      admin.then(
         Commands.literal("expire")
            .then(
               codeArg(codes)
                  .then(
                     Commands.argument("hours", IntegerArgumentType.integer(0))
                        .executes(
                           ctx -> {
                              String code = StringArgumentType.getString(ctx, "code");
                              int hours = IntegerArgumentType.getInteger(ctx, "hours");
                              return done(
                                 (CommandSourceStack)ctx.getSource(),
                                 rm.setExpireHours(code, hours),
                                 code.toUpperCase() + (hours == 0 ? " never expires." : " expires in " + hours + " hour(s).")
                              );
                           }
                        )
                  )
            )
      );
      admin.then(Commands.literal("enable").then(codeArg(codes).executes(ctx -> {
         String code = StringArgumentType.getString(ctx, "code");
         return done((CommandSourceStack)ctx.getSource(), rm.setEnabled(code, true), code.toUpperCase() + " is enabled.");
      })));
      admin.then(Commands.literal("disable").then(codeArg(codes).executes(ctx -> {
         String code = StringArgumentType.getString(ctx, "code");
         return done((CommandSourceStack)ctx.getSource(), rm.setEnabled(code, false), code.toUpperCase() + " is disabled.");
      })));
      admin.then(
         ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("item")
                  .then(
                     Commands.literal("hand")
                        .then(
                           ((RequiredArgumentBuilder)codeArg(codes)
                                 .executes(ctx -> addHeld((CommandSourceStack)ctx.getSource(), rm, StringArgumentType.getString(ctx, "code"), 0)))
                              .then(
                                 Commands.argument("amount", IntegerArgumentType.integer(1))
                                    .executes(
                                       ctx -> addHeld(
                                          (CommandSourceStack)ctx.getSource(),
                                          rm,
                                          StringArgumentType.getString(ctx, "code"),
                                          IntegerArgumentType.getInteger(ctx, "amount")
                                       )
                                    )
                              )
                        )
                  ))
               .then(
                  Commands.literal("add")
                     .then(
                        codeArg(codes)
                           .then(
                              Commands.argument("item", StringArgumentType.word())
                                 .then(
                                    Commands.argument("amount", IntegerArgumentType.integer(1))
                                       .executes(
                                          ctx -> {
                                             String code = StringArgumentType.getString(ctx, "code");
                                             String item = StringArgumentType.getString(ctx, "item");
                                             int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                             return done(
                                                (CommandSourceStack)ctx.getSource(),
                                                rm.addItem(code, item, amount),
                                                "Added " + amount + "x " + Msg.pretty(ItemIds.normalize(item)) + " to " + code.toUpperCase() + "."
                                             );
                                          }
                                       )
                                 )
                           )
                     )
               ))
            .then(Commands.literal("clear").then(codeArg(codes).executes(ctx -> {
               String code = StringArgumentType.getString(ctx, "code");
               return done((CommandSourceStack)ctx.getSource(), rm.clearItems(code), "Removed all item rewards of " + code.toUpperCase() + ".");
            })))
      );
      admin.then(
         Commands.literal("list")
            .executes(
               ctx -> {
                  List<String> lines = rm.list();
                  if (lines.isEmpty()) {
                     ((CommandSourceStack)ctx.getSource())
                        .sendSuccess(() -> Component.literal("There are no codes yet. Create one with /rcode create <code> <money>."), false);
                  }

                  for (String line : lines) {
                     ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(line), false);
                  }

                  return 1;
               }
            )
      );
      admin.then(Commands.literal("info").then(codeArg(codes).executes(ctx -> {
         String code = StringArgumentType.getString(ctx, "code");
         List<String> lines = rm.info(code);
         if (lines == null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("No such code: " + code.toUpperCase()));
            return 0;
         } else {
            for (String line : lines) {
               ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(line), false);
            }

            return 1;
         }
      })));
      admin.then(Commands.literal("reload").executes(ctx -> {
         rm.load();
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("redeem.json reloaded."), false);
         return 1;
      }));
      d.register(admin);
   }

   private static RequiredArgumentBuilder<CommandSourceStack, String> codeArg(SuggestionProvider<CommandSourceStack> suggestions) {
      return Commands.argument("code", StringArgumentType.word()).suggests(suggestions);
   }

   private static int done(CommandSourceStack src, String error, String success) {
      if (error != null) {
         src.sendFailure(Component.literal(error));
         return 0;
      } else {
         src.sendSuccess(() -> Component.literal(success), false);
         return 1;
      }
   }

   private static int addHeld(CommandSourceStack src, RedeemManager rm, String code, int amount) throws CommandSyntaxException {
      ServerPlayer p = src.getPlayerOrException();
      ItemStack held = p.getMainHandItem();
      if (held.isEmpty()) {
         src.sendFailure(Component.literal("Hold the item you want to add as a reward."));
         return 0;
      } else {
         String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
         int count = amount > 0 ? amount : held.getCount();
         return done(src, rm.addItem(code, id, count), "Added " + count + "x " + Msg.pretty(id) + " to " + code.toUpperCase() + " (your item was not taken).");
      }
   }
}
