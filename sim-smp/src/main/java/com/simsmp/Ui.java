package com.simsmp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

final class Ui {
   private Ui() {
   }

   static ItemStack named(Item item, String name, ChatFormatting color, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color).withStyle(st -> st.withItalic(false)));
      if (lore.length > 0) {
         List<Component> lines = new ArrayList<>();

         for (String line : lore) {
            lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
         }

         stack.set(DataComponents.LORE, new ItemLore(lines));
      }

      return stack;
   }

   static ItemStack filler() {
      return named(Items.PAPER, " ", ChatFormatting.GRAY);
   }
}
