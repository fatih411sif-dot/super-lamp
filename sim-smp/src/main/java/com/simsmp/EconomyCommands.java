package com.simsmp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/** /balance, /pay, /sell, /trade */
final class EconomyCommands {
    private EconomyCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d, SimConfig cfg, EconomyManager eco, TradeManager trades) {
        // --- balance -------------------------------------------------------
        Command<CommandSourceStack> balance = ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            Msg.info(p, "Your balance: " + eco.fmt(eco.get(p.getUUID())));
            return 1;
        };
        d.register(Commands.literal("balance").executes(balance));
        d.register(Commands.literal("bal").executes(balance));
        d.register(Commands.literal("money").executes(balance));

        // --- pay -----------------------------------------------------------
        d.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    ServerPlayer from = ctx.getSource().getPlayerOrException();
                                    ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
                                    long amount = LongArgumentType.getLong(ctx, "amount");
                                    if (from.getUUID().equals(to.getUUID())) {
                                        Msg.err(from, "You can't pay yourself.");
                                        return 0;
                                    }
                                    if (!eco.take(from.getUUID(), amount)) {
                                        Msg.err(from, "You only have " + eco.fmt(eco.get(from.getUUID())) + ".");
                                        return 0;
                                    }
                                    eco.add(to.getUUID(), amount);
                                    Msg.ok(from, "You paid " + eco.fmt(amount) + " to " + Msg.name(to) + ".");
                                    Msg.ok(to, Msg.name(from) + " paid you " + eco.fmt(amount) + ".");
                                    return 1;
                                }))));

        // --- sell ----------------------------------------------------------
        d.register(Commands.literal("sell").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            SellMenu.open(p, cfg, eco);
            return 1;
        }));

        // --- trade ---------------------------------------------------------
        d.register(Commands.literal("trade")
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer me = ctx.getSource().getPlayerOrException();
                                    trades.accept(me, EntityArgument.getPlayer(ctx, "player"));
                                    return 1;
                                })))
                .then(Commands.literal("deny").executes(ctx -> {
                    ServerPlayer me = ctx.getSource().getPlayerOrException();
                    trades.deny(me, ctx.getSource().getServer());
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    trades.cancel(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("money")
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(ctx -> {
                                    ServerPlayer me = ctx.getSource().getPlayerOrException();
                                    trades.setMoney(me, LongArgumentType.getLong(ctx, "amount"));
                                    return 1;
                                })))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer me = ctx.getSource().getPlayerOrException();
                            trades.request(me, EntityArgument.getPlayer(ctx, "player"));
                            return 1;
                        })));
    }
}
