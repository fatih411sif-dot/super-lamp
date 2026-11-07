package com.simsmp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;

/**
 * Class XP for kills. Hunter, Archer, Berserker, Warrior and Mage had no XP source at all before.
 * Hostile mobs give HOSTILE_XP, animals give ANIMAL_XP (Hunter only). Players never give XP.
 */
final class KillXp {
   static final long HOSTILE_XP = 2L;
   static final long ANIMAL_XP = 1L;

   private KillXp() {
   }

   static void onKill(Entity killer, LivingEntity killed, DamageSource source, ClassManager classes) {
      if (killer instanceof ServerPlayer p && !(killed instanceof ServerPlayer)) {
         String active = classes.active(p.getUUID());
         if (active != null && !active.isEmpty()) {
            boolean hostile = killed instanceof Monster;
            // melee = the thing that dealt the damage is the player themselves; otherwise a projectile / indirect hit
            boolean melee = source.getDirectEntity() == p;
            long xp = switch (active) {
               case "hunter" -> hostile ? 0L : ANIMAL_XP;
               case "warrior" -> hostile && melee && p.getMainHandItem().is(ItemTags.SWORDS) ? HOSTILE_XP : 0L;
               case "berserker" -> hostile && melee && p.getMainHandItem().is(ItemTags.AXES) ? HOSTILE_XP : 0L;
               case "archer" -> hostile && !melee ? HOSTILE_XP : 0L;
               case "mage" -> hostile ? HOSTILE_XP : 0L;
               default -> 0L;
            };
            if (xp > 0L) {
               xp += classes.perkTotal(p.getUUID(), "xp");
               classes.addXp(p, xp);
            }
         }
      }
   }
}
