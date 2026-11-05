package com.simsmp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * SIM SMP - server-side Fabric mod.
 * Rupee economy, sidebar scoreboard, /sell, /tf, /top, /market, /dshop and /trade.
 */
public class SimSmp implements ModInitializer {
    public static final String MOD_ID = "sim_smp";
    public static final Logger LOGGER = LoggerFactory.getLogger("SIM SMP");

    private int tickCounter;

    @Override
    public void onInitialize() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        SimConfig cfg = SimConfig.load(dir);

        EconomyManager eco = new EconomyManager(dir.resolve("balances.json"), cfg);
        eco.load();

        SidebarManager sidebar = new SidebarManager(cfg, eco);
        TradeManager trades = new TradeManager(cfg, eco);
        MarketManager market = new MarketManager(cfg, eco, dir.resolve("market.json"));
        DailyShop shop = new DailyShop(cfg, eco, dir);
        shop.load();
        Gacha gacha = new Gacha(dir.resolve("gacha.json"));
        gacha.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EconomyCommands.register(dispatcher, cfg, eco, trades, market, shop, gacha));

        ServerLifecycleEvents.SERVER_STARTED.register(market::load);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            eco.ensure(player.getUUID());
            eco.rememberName(player.getUUID(), Msg.name(player)); // for /top
            sidebar.show(server, player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            trades.playerLeft(player);
            sidebar.forget(player);
        });

        final int saveEvery = 20 * Math.max(10, cfg.saveIntervalSeconds);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            GachaMenu.tickAll();
            if (tickCounter % 20 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    sidebar.update(server, p);
                }
            }
            if (tickCounter % saveEvery == 0) {
                eco.save();
                shop.save();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            eco.save();
            market.save();
            shop.save();
        });

        LOGGER.info("SIM SMP economy loaded ({} sell prices, starting balance {}).",
                cfg.prices.size(), cfg.startingBalance);
    }
}
