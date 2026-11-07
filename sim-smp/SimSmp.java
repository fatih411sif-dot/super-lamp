package com.simsmp;

import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.After;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimSmp implements ModInitializer {
   public static final String MOD_ID = "sim_smp";
   public static final Logger LOGGER = LoggerFactory.getLogger("SIM SMP");
   private int tickCounter;

   public SimSmp() {
   }

   public void onInitialize() {
      Path dir = FabricLoader.getInstance().getConfigDir().resolve("sim_smp");
      SimConfig cfg = SimConfig.load(dir);
      EconomyManager eco = new EconomyManager(dir.resolve("balances.json"), cfg);
      eco.load();
      ClassManager classes = new ClassManager(cfg, eco, dir);
      classes.load();
      RankManager ranks = new RankManager(dir);
      ranks.load();
      SidebarManager sidebar = new SidebarManager(cfg, eco, classes, ranks);
      TradeManager trades = new TradeManager(cfg, eco);
      MarketManager market = new MarketManager(cfg, eco, dir.resolve("market.json"));
      DailyShop shop = new DailyShop(cfg, eco, dir);
      shop.load();
      Gacha gacha = new Gacha(dir.resolve("gacha.json"));
      gacha.load();
      RedeemManager redeem = new RedeemManager(eco, dir);
      redeem.load();
      CommandRegistrationCallback.EVENT.register((CommandRegistrationCallback)(dispatcher, registryAccess, environment) -> {
         EconomyCommands.register(dispatcher, cfg, eco, trades, market, shop, gacha, classes);
         RedeemCommands.register(dispatcher, redeem);
         ClassCommands.register(dispatcher, classes, cfg);
         ExtraCommands.register(dispatcher, ranks);
      });
      ServerLifecycleEvents.SERVER_STARTED.register(market::load);
      PlayerBlockBreakEvents.AFTER.register((After)(level, player, pos, state, blockEntity) -> {
         if (player instanceof ServerPlayer sp) {
            ClassEffects.onBlockBroken(sp, level, pos, state, classes, cfg);
         }
      });
      UseBlockCallback.EVENT
         .register((UseBlockCallback)(player, level, hand, hitResult) -> ClassEffects.onUseBlock(player, level, hand, hitResult, classes, cfg));
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, server) -> {
         ServerPlayer player = handler.getPlayer();
         eco.ensure(player.getUUID());
         eco.rememberName(player.getUUID(), Msg.name(player));
         ranks.apply(server, player);
         sidebar.show(server, player);
      });
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, server) -> {
         ServerPlayer player = handler.getPlayer();
         trades.playerLeft(player);
         sidebar.forget(player);
         ClassEffects.forget(player);
         Sit.stop(player);
      });
      int saveEvery = 20 * Math.max(10, cfg.saveIntervalSeconds);
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         this.tickCounter++;
         Sit.tick(server);
         GachaMenu.tickAll();
         if (this.tickCounter % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               sidebar.update(server, p);
               ClassEffects.update(p, classes);
            }
         }

         if (this.tickCounter % saveEvery == 0) {
            eco.save();
            shop.save();
            classes.save();
            ranks.save();
         }
      });
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         eco.save();
         classes.save();
         ranks.save();
         market.save();
         shop.save();
      });
      LOGGER.info("SIM SMP economy loaded ({} sell prices, starting balance {}).", cfg.prices.size(), cfg.startingBalance);
   }
}
