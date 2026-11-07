package com.simsmp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * /sit - seats the player on an invisible, invulnerable armor stand. Sneak (or /sit again) to stand up.
 * The stand is always removed again: on stand-up, on disconnect, and (for leftovers after a crash) on chunk load.
 */
final class Sit {
   static final String TAG = "sim_sit";
   /** How far below the player's feet the seat is placed. Raise it to sit lower, lower it to sit higher. */
   private static final double SEAT_DROP = 0.8;
   private static final Map<UUID, Seat> seats = new HashMap<>();

   private record Seat(ArmorStand stand, double x, double y, double z) {
   }

   private Sit() {
   }

   static boolean isSitting(ServerPlayer p) {
      return seats.containsKey(p.getUUID());
   }

   /** True for a seat stand this session is still using. */
   static boolean isTracked(Entity e) {
      for (Seat s : seats.values()) {
         if (s.stand == e) {
            return true;
         }
      }

      return false;
   }

   static int toggle(ServerPlayer p) {
      if (isSitting(p)) {
         p.stopRiding();
         return 1;
      } else if (p.isSpectator() || !p.isAlive()) {
         Msg.err(p, "You can't sit right now.");
         return 0;
      } else if (p.isPassenger()) {
         Msg.err(p, "You are already riding something.");
         return 0;
      } else if (p.isInWater() || p.isInLava() || p.getAbilities().flying) {
         Msg.err(p, "You can only sit on solid ground.");
         return 0;
      } else if (!p.onGround()) {
         Msg.err(p, "Stand on the ground to sit.");
         return 0;
      } else {
         ServerLevel level = p.level();
         ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
         stand.setPos(p.getX(), p.getY() - SEAT_DROP, p.getZ());
         stand.setYRot(p.getYRot());
         stand.setSmall(true);
         stand.setInvisible(true);
         stand.setInvulnerable(true);
         stand.setNoGravity(true);
         stand.addTag(TAG);
         // track first, so the chunk-load cleanup doesn't mistake our own stand for a leftover
         seats.put(p.getUUID(), new Seat(stand, p.getX(), p.getY(), p.getZ()));
         level.addFreshEntity(stand);
         if (!p.startRiding(stand)) {
            seats.remove(p.getUUID());
            stand.discard();
            Msg.err(p, "Could not sit here.");
            return 0;
         } else {
            Msg.info(p, "You are sitting. Sneak to stand up.");
            return 1;
         }
      }
   }

   /** Call every server tick: removes stands whose rider got off and puts the rider back on top of the ground. */
   static void tick(MinecraftServer server) {
      if (!seats.isEmpty()) {
         for (UUID id : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive()) {
               seat.stand.discard();
               seats.remove(id);
            } else if (!seat.stand.isAlive() || p.getVehicle() != seat.stand) {
               seat.stand.discard();
               seats.remove(id);
               p.connection.teleport(seat.x, seat.y, seat.z, p.getYRot(), p.getXRot());
            }
         }
      }
   }

   /** Player disconnected. */
   static void stop(ServerPlayer p) {
      Seat seat = seats.remove(p.getUUID());
      if (seat != null) {
         p.stopRiding();
         seat.stand.discard();
      }
   }

   static boolean isLeftover(Entity e) {
      return e instanceof ArmorStand && e.getTags().contains(TAG) && !isTracked(e);
   }

   /** Removes seat stands left behind in the world (e.g. after a crash). */
   static void cleanupLeftover(Entity e) {
      if (e instanceof ArmorStand && e.getTags().contains(TAG) && !isTracked(e)) {
         e.discard();
      }
   }

   static List<UUID> sitters() {
      return new ArrayList<>(seats.keySet());
   }
}
