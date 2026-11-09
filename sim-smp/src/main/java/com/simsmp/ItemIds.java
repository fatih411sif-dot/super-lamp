package com.simsmp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

final class ItemIds {
   private static Map<String, Item> cache;

   private ItemIds() {
   }

   private static synchronized Map<String, Item> all() {
      if (cache == null) {
         Map<String, Item> m = new LinkedHashMap<>();

         for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) {
               m.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
            }
         }

         cache = m;
      }

      return cache;
   }

   static String normalize(String id) {
      if (id == null) {
         return null;
      } else {
         String s = id.trim().toLowerCase(Locale.ROOT);
         return s.contains(":") ? s : "minecraft:" + s;
      }
   }

   static Item find(String id) {
      String key = normalize(id);
      return key == null ? null : all().get(key);
   }
}
