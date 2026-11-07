package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /sit (everyone): sit down on the spot. A server mod cannot add a real sitting animation, so it does what most
 * servers do: an invisible armor stand is put under the player and the player rides it. Riding makes the game show
 * the sitting pose. Sneak (shift) or /sit again to stand up; the seat then disappears.
 *
 * The seat is never saved with a purpose: leftovers from a crash are removed when they load (they carry a tag).
 */
final class Sit {
    private Sit() {}

    private static final String TAG = "sim_smp_seat";

    /** Who sits on which seat. */
    private static final Map<UUID, ArmorStand> seats = new HashMap<>();
    /** UUIDs of the seats in use, so a seat that is NOT in use (left over from a crash) can be recognised. */
    private static final Set<UUID> activeSeats = new HashSet<>();
    /** Old seats found while loading, removed on the next tick (an entity should not be removed while it loads). */
    private static final List<Entity> leftovers = new ArrayList<>();

    static void register(CommandDispatcher<CommandSourceStack> d, SimConfig cfg) {
        d.register(Commands.literal("sit").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            toggle(p, cfg);
            return 1;
        }));
    }

    private static void toggle(ServerPlayer p, SimConfig cfg) {
        UUID id = p.getUUID();
        if (seats.containsKey(id)) {
            ArmorStand seat = seats.get(id);
            p.stopRiding();
            removeSeat(id, seat);
            Msg.info(p, "You stood up.");
            return;
        }

        if (p.isPassenger()) {
            Msg.err(p, "You can't sit while you are riding something.");
            return;
        }
        if (p.isSpectator() || p.isSleeping() || p.getAbilities().flying) {
            Msg.err(p, "You can't sit right now.");
            return;
        }
        if (p.isInWater() || p.isInLava()) {
            Msg.err(p, "You can't sit in liquid.");
            return;
        }
        if (!(p.level() instanceof ServerLevel level)) return;
        if (level.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ())).isAir()) {
            Msg.err(p, "You need solid ground to sit on.");
            return;
        }

        ArmorStand seat = new ArmorStand(level, p.getX(), p.getY() + cfg.sitHeightOffset, p.getZ());
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.addTag(TAG);
        seats.put(id, seat);
        activeSeats.add(seat.getUUID()); // before the entity is added, so loading it does not count as a leftover
        level.addFreshEntity(seat);

        if (!p.startRiding(seat)) {
            removeSeat(id, seat);
            Msg.err(p, "You can't sit here.");
            return;
        }
        Msg.ok(p, "You are sitting. Sneak (shift) or type /sit to stand up.");
    }

    private static void removeSeat(UUID player, ArmorStand seat) {
        seats.remove(player);
        activeSeats.remove(seat.getUUID());
        seat.discard();
    }

    /** Called every server tick: removes the seat of everybody who stood up, left the game, or died. */
    static void tick(MinecraftServer server) {
        if (!leftovers.isEmpty()) {
            for (Entity e : leftovers) e.discard();
            leftovers.clear();
        }
        if (seats.isEmpty()) return;

        Iterator<Map.Entry<UUID, ArmorStand>> it = seats.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ArmorStand> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            ArmorStand seat = entry.getValue();
            if (p == null || !p.isAlive() || seat.isRemoved() || p.getVehicle() != seat) {
                activeSeats.remove(seat.getUUID());
                seat.discard();
                it.remove();
            }
        }
    }

    /** A player left the game while sitting. */
    static void forget(ServerPlayer p) {
        ArmorStand seat = seats.get(p.getUUID());
        if (seat != null) {
            p.stopRiding();
            removeSeat(p.getUUID(), seat);
        }
    }

    /** Called for every entity that loads (see ServerEntityEvents.ENTITY_LOAD): finds seats left over from a crash. */
    static void onEntityLoad(Entity entity) {
        if (entity instanceof ArmorStand && entity.getTags().contains(TAG) && !activeSeats.contains(entity.getUUID())) {
            leftovers.add(entity);
        }
    }
}
