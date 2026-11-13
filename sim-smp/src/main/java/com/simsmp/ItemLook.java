package com.simsmp;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The ONLY place that touches the "custom look" API. Sets the item_model component so the resource pack
 * (resourcepack/assets/sim_smp/items/<name>.json) draws the item with its own texture.
 * Written for Minecraft 1.21.4+ (item_model + client item definitions). If your Minecraft version names
 * ResourceLocation differently (1.21.11+ / 26.x call it Identifier), only this file needs changing.
 */
public final class ItemLook {
   private ItemLook() {
   }

   public static void apply(ItemStack st, String model) {
      st.set(DataComponents.ITEM_MODEL, ResourceLocation.fromNamespaceAndPath(SimSmp.MOD_ID, model));
   }
}
