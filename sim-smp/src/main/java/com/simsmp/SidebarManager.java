package com.simsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player sidebar scoreboard, sent with raw packets so every player sees their own numbers
 * without touching the real server scoreboard.
 */
public final class SidebarManager {
    private static final String OBJECTIVE_NAME = "sim_smp_side";
    private static final int LINES = 10;

    private final SimConfig cfg;
    private final EconomyManager eco;
    private final ClassManager classes;
    private final Objective objective; // detached: only ever sent in packets
    private final Map<UUID, String[]> cache = new HashMap<>();

    public SidebarManager(SimConfig cfg, EconomyManager eco, ClassManager classes) {
        this.cfg = cfg;
        this.eco = eco;
        this.classes = classes;
        Component title = Component.literal(cfg.scoreboardTitle)
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        this.objective = new Objective(new Scoreboard(), OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, title,
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    }

    /** Called once when a player joins. */
    public void show(MinecraftServer server, ServerPlayer p) {
        p.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
        p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        cache.remove(p.getUUID());
        update(server, p);
    }

    public void forget(ServerPlayer p) {
        cache.remove(p.getUUID());
    }

    /** Refreshes any line whose text changed. Called once per second. */
    public void update(MinecraftServer server, ServerPlayer p) {
        Component[] lines = build(server, p);
        String[] old = cache.computeIfAbsent(p.getUUID(), k -> new String[LINES]);
        for (int i = 0; i < LINES; i++) {
            String text = lines[i].getString();
            if (text.equals(old[i])) continue;
            old[i] = text;
            p.connection.send(new ClientboundSetScorePacket(
                    "sim_line_" + i,
                    OBJECTIVE_NAME,
                    LINES - i, // higher score = higher on screen
                    Optional.of(lines[i]),
                    Optional.of(BlankFormat.INSTANCE))); // hide the red numbers
        }
    }

    private Component[] build(MinecraftServer server, ServerPlayer p) {
        int ping = p.connection.latency();
        ChatFormatting pingColor = ping < 80 ? ChatFormatting.GREEN : ping < 150 ? ChatFormatting.YELLOW : ChatFormatting.RED;

        int hearts = Math.round(p.getMaxHealth() / 2f);
        int deaths = p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
        int playTicks = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));

        Component[] lines = new Component[LINES];
        lines[0] = Component.literal(" ");
        lines[1] = line("Player: ", Msg.name(p), ChatFormatting.WHITE);
        lines[2] = line("Money: ", eco.fmt(eco.get(p.getUUID())), ChatFormatting.GOLD);
        lines[3] = line("Class XP: ", ClassCommands.sidebarText(classes, p), ChatFormatting.LIGHT_PURPLE);
        lines[4] = line("Hearts: ", String.valueOf(hearts), ChatFormatting.RED);
        lines[5] = line("Deaths: ", String.valueOf(deaths), ChatFormatting.DARK_RED);
        lines[6] = line("Playtime: ", playtime(playTicks), ChatFormatting.AQUA);
        lines[7] = line("Ping: ", ping + "ms", pingColor);
        lines[8] = line("Online: ", server.getPlayerCount() + "/" + server.getMaxPlayers(), ChatFormatting.GREEN);
        lines[9] = Component.literal("  ");
        return lines;
    }

    private static MutableComponent line(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor));
    }

    private static String playtime(int ticks) {
        long minutes = ticks / 1200L;
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
