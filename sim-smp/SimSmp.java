package com.simsmp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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

        ClassManager classes = new ClassManager(cfg, eco, dir);
        classes.load();

        SidebarManager sidebar = new SidebarManager(cfg, eco, classes);
        TradeManager trades = new TradeManager(cfg, eco);
        MarketManager market = new MarketManager(cfg, eco, dir.resolve("market.json"));
        DailyShop shop = new DailyShop(cfg, eco, dir);
        shop.load();
        Gacha gacha = new Gacha(dir.resolve("gacha.json"));
        gacha.load();

        RedeemManager redeem = new RedeemManager(eco, dir);
        redeem.load();

        Ranks ranks = new Ranks(dir);
        ranks.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            EconomyCommands.register(dispatcher, cfg, eco, trades, market, shop, gacha, classes);
            RedeemCommands.register(dispatcher, redeem);
            ClassCommands.register(dispatcher, classes, cfg);
            Ranks.register(dispatcher, ranks);
            Sit.register(dispatcher, cfg);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(market::load);

        // Miner: Class XP for every ore mined (plain stone gives nothing, so it cannot be farmed),
        // and the Vein Miner / 3x3 Mining abilities
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer sp)) return;
            ClassEffects.onBlockBroken(sp, level, pos, state, classes, cfg);
        });

        // Farmer: Instant Grow (right-click a crop with a hoe)
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                ClassEffects.onUseBlock(player, level, hand, hitResult, classes, cfg));

        // Warrior: Charge (right-click with a spear)
        UseItemCallback.EVENT.register((player, level, hand) -> ClassEffects.onUseItem(player, level, hand, classes, cfg));

        // Hunter: XP for hunting farm animals, Lucky Drops and Auto Cook
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> ClassEffects.onEntityDeath(entity, source, classes, cfg));

        // Archer: arrow boosts when shot, arrow resistance, poison arrows
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            ClassEffects.onEntityLoad(entity, classes, cfg);
            Sit.onEntityLoad(entity); // clears seats left over from a crash
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> ClassEffects.allowDamage(entity, source, amount, classes));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) ->
                ClassEffects.afterDamage(entity, source, damageTaken, blocked, classes, cfg));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            eco.ensure(player.getUUID());
            eco.rememberName(player.getUUID(), Msg.name(player)); // for /top
            sidebar.show(server, player);
            ranks.apply(server, player); // the rank tag in front of the name
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            trades.playerLeft(player);
            sidebar.forget(player);
            ClassEffects.forget(player);
            Sit.forget(player);
        });

        final int saveEvery = 20 * Math.max(10, cfg.saveIntervalSeconds);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            GachaMenu.tickAll();
            ClassEffects.tickArrows();
            ClassEffects.tickCharges(server, classes, cfg);
            Sit.tick(server);
            if (tickCounter % 20 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    sidebar.update(server, p);
                    ClassEffects.update(p, classes);
                }
            }
            if (tickCounter % saveEvery == 0) {
                eco.save();
                shop.save();
                classes.save();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            eco.save();
            classes.save();
            market.save();
            shop.save();
        });

        LOGGER.info("SIM SMP economy loaded ({} sell prices, starting balance {}).",
                cfg.prices.size(), cfg.startingBalance);
    }
}
