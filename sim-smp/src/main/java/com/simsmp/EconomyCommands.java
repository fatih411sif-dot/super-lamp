package com.simsmp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionCheck.Require;
import net.minecraft.world.item.ItemStack;

final class EconomyCommands {
   private EconomyCommands() {
   }

   static void register(
      CommandDispatcher<CommandSourceStack> d,
      SimConfig cfg,
      EconomyManager eco,
      TradeManager trades,
      MarketManager market,
      DailyShop shop,
      Gacha gacha,
      ClassManager classes
   ) {
      Command<CommandSourceStack> balance = ctx -> {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
         Msg.info(p, "Your balance: " + eco.fmt(eco.get(p.getUUID())));
         return 1;
      };
      d.register((LiteralArgumentBuilder)Commands.literal("balance").executes(balance));
      d.register((LiteralArgumentBuilder)Commands.literal("bal").executes(balance));
      d.register((LiteralArgumentBuilder)Commands.literal("money").executes(balance));
      d.register(
         (LiteralArgumentBuilder)Commands.literal("tf")
            .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", LongArgumentType.longArg(1L)).executes(ctx -> {
               ServerPlayer from = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
               ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
               long amount = LongArgumentType.getLong(ctx, "amount");
               if (from.getUUID().equals(to.getUUID())) {
                  Msg.err(from, "You can't send money to yourself.");
                  return 0;
               } else if (!eco.take(from.getUUID(), amount)) {
                  Msg.err(from, "You only have " + eco.fmt(eco.get(from.getUUID())) + ".");
                  return 0;
               } else {
                  eco.add(to.getUUID(), amount);
                  Msg.ok(from, "You sent " + eco.fmt(amount) + " to " + Msg.name(to) + ".");
                  Msg.ok(to, Msg.name(from) + " sent you " + eco.fmt(amount) + ".");
                  return 1;
               }
            })))
      );
      d.register((LiteralArgumentBuilder)Commands.literal("top").executes(ctx -> {
         TopMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), eco);
         return 1;
      }));
      d.register((LiteralArgumentBuilder)Commands.literal("sell").executes(ctx -> {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
         SellMenu.open(p, cfg, eco, classes);
         return 1;
      }));
      d.register((LiteralArgumentBuilder)Commands.literal("prices").executes(ctx -> {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
         Msg.info(p, "Items you can sell with /sell (price per item):");
         List<String> chunk = new ArrayList<>();

         for (Entry<String, Long> e : cfg.prices.entrySet()) {
            chunk.add(Msg.pretty(e.getKey()) + " " + eco.fmt(PriceEngine.get() == null ? e.getValue() : PriceEngine.get().effective(e.getKey(), e.getValue())));
            if (chunk.size() == 4) {
               p.sendSystemMessage(Component.literal(String.join("  |  ", chunk)).withStyle(ChatFormatting.GRAY));
               chunk.clear();
            }
         }

         if (!chunk.isEmpty()) {
            p.sendSystemMessage(Component.literal(String.join("  |  ", chunk)).withStyle(ChatFormatting.GRAY));
         }

         return 1;
      }));
      SuggestionProvider<CommandSourceStack> priceItems = (ctx, builder) -> SharedSuggestionProvider.suggest(
         cfg.prices.keySet().stream().map(k -> k.startsWith("minecraft:") ? k.substring(10) : k).toList(), builder
      );
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("sellprice")
                     .requires(Commands.hasPermission(new Require(Permissions.COMMANDS_GAMEMASTER))))
                  .then(Commands.literal("hand").then(Commands.argument("price", LongArgumentType.longArg(1L)).executes(ctx -> {
                     ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                     ItemStack held = p.getMainHandItem();
                     if (held.isEmpty()) {
                        Msg.err(p, "Hold the item you want to price.");
                        return 0;
                     } else {
                        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                        return applyPrice((CommandSourceStack)ctx.getSource(), cfg, eco, id, LongArgumentType.getLong(ctx, "price"));
                     }
                  }))))
               .then(
                  Commands.literal("set")
                     .then(
                        Commands.argument("item", StringArgumentType.word())
                           .suggests(priceItems)
                           .then(
                              Commands.argument("price", LongArgumentType.longArg(1L))
                                 .executes(
                                    ctx -> applyPrice(
                                       (CommandSourceStack)ctx.getSource(),
                                       cfg,
                                       eco,
                                       StringArgumentType.getString(ctx, "item"),
                                       LongArgumentType.getLong(ctx, "price")
                                    )
                                 )
                           )
                     )
               ))
            .then(Commands.literal("remove").then(Commands.argument("item", StringArgumentType.word()).suggests(priceItems).executes(ctx -> {
               String id = ItemIds.normalize(StringArgumentType.getString(ctx, "item"));
               Long old = cfg.prices.remove(id);
               if (old == null) {
                  ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Msg.pretty(id) + " is not sellable."));
                  return 0;
               } else {
                  cfg.savePrices();
                  ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Msg.pretty(id) + " can no longer be sold to the system."), true);
                  return 1;
               }
            })))
      );
      d.register(marketCommand("market", eco, market));
      d.register(marketCommand("ah", eco, market));
      d.register((LiteralArgumentBuilder)Commands.literal("dshop").executes(ctx -> {
         DailyShopMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), shop, eco);
         return 1;
      }));
      d.register((LiteralArgumentBuilder)Commands.literal("gacha").executes(ctx -> {
         GachaMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), ((CommandSourceStack)ctx.getSource()).getServer(), gacha, eco, cfg);
         return 1;
      }));
      d.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("trade")
                        .then(Commands.literal("accept").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                           ServerPlayer me = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                           trades.accept(me, EntityArgument.getPlayer(ctx, "player"));
                           return 1;
                        }))))
                     .then(Commands.literal("deny").executes(ctx -> {
                        ServerPlayer me = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                        trades.deny(me, ((CommandSourceStack)ctx.getSource()).getServer());
                        return 1;
                     })))
                  .then(Commands.literal("cancel").executes(ctx -> {
                     trades.cancel(((CommandSourceStack)ctx.getSource()).getPlayerOrException());
                     return 1;
                  })))
               .then(Commands.literal("money").then(Commands.argument("amount", LongArgumentType.longArg(0L)).executes(ctx -> {
                  ServerPlayer me = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                  trades.setMoney(me, LongArgumentType.getLong(ctx, "amount"));
                  return 1;
               }))))
            .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
               ServerPlayer me = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
               trades.request(me, EntityArgument.getPlayer(ctx, "player"));
               return 1;
            }))
      );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> marketCommand(String name, EconomyManager eco, MarketManager market) {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal(name).executes(ctx -> {
            MarketMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), market, eco);
            return 1;
         }))
         .then(
            Commands.literal("sell")
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("price", LongArgumentType.longArg(1L)).executes(ctx -> {
                        market.list(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), LongArgumentType.getLong(ctx, "price"), 0);
                        return 1;
                     }))
                     .then(
                        Commands.argument("amount", IntegerArgumentType.integer(1))
                           .executes(
                              ctx -> {
                                 market.list(
                                    ((CommandSourceStack)ctx.getSource()).getPlayerOrException(),
                                    LongArgumentType.getLong(ctx, "price"),
                                    IntegerArgumentType.getInteger(ctx, "amount")
                                 );
                                 return 1;
                              }
                           )
                     )
               )
         );
   }

   private static int applyPrice(CommandSourceStack src, SimConfig cfg, EconomyManager eco, String rawId, long price) {
      String id = ItemIds.normalize(rawId);
      if (ItemIds.find(id) == null) {
         src.sendFailure(Component.literal("Unknown item: " + rawId));
         return 0;
      } else {
         Long old = cfg.prices.put(id, price);
         cfg.savePrices();
         src.sendSuccess(
            () -> Component.literal(
               "Sell price of " + Msg.pretty(id) + " set to " + eco.fmt(price) + (old != null ? " (was " + eco.fmt(old) + ")" : " (newly sellable)")
            ),
            true
         );
         return 1;
      }
   }
}
