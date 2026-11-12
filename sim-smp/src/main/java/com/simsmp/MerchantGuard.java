package com.simsmp;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

/** Only players with the Merchant rank (and OPs) may trade with villagers and wandering traders. */
final class MerchantGuard {
   private MerchantGuard() {
   }

   static void register() {
      UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
         if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
         }

         String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
         if (!type.equals("villager") && !type.equals("wandering_trader")) {
            return InteractionResult.PASS;
         }

         PriceEngine pe = PriceEngine.get();
         if (pe != null && pe.isMerchant(sp)) {
            return InteractionResult.PASS;
         }

         Msg.err(sp, "Only Merchants can trade with villagers. Ask the staff for the Merchant rank.");
         return InteractionResult.FAIL;
      });
   }
}
