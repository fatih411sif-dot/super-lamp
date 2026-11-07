package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cosmetic ranks: a coloured tag in front of a player's name, shown above their head, in chat and in the tab list.
 * A rank gives NO benefit at all, it is only for show.
 *
 * It uses the vanilla scoreboard teams: Minecraft itself puts a team's prefix in front of the name everywhere, so
 * nobody needs a client mod. Every rank is one team called "rank_<id>". A player can only be in ONE team, so giving a
 * rank takes the player out of any other team they were in (for example one made with /team).
 *
 * The ranks live in ranks.json (edit it by hand, then /rank reload). Who has which rank is in playerranks.json.
 *
 * This file also holds /jumpscare (OP only): the Elder Guardian jump scare.
 */
public final class Ranks {
    /** One line of ranks.json. */
    public static final class Rank {
        /** Short name used in commands: a-z, 0-9 and _, at most 11 characters. */
        public String id;
        /** What is shown in front of the name, for example "[VIP]". You can put symbols in it. */
        public String text;
        /** Hex colour of the text. */
        public String color = "#FFFFFF";
        public boolean bold = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String TEAM_PREFIX = "rank_";
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,11}");

    private final Path ranksFile;
    private final Path playersFile;
    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    private final Map<String, String> holders = new HashMap<>(); // player uuid -> rank id

    public Ranks(Path dir) {
        this.ranksFile = dir.resolve("ranks.json");
        this.playersFile = dir.resolve("playerranks.json");
    }

    // ------------------------------------------------------------------ files

    public synchronized void load() {
        ranks.clear();
        holders.clear();
        try {
            if (!Files.exists(ranksFile)) {
                Files.createDirectories(ranksFile.getParent());
                Files.writeString(ranksFile, GSON.toJson(defaults()));
            }
            try (Reader r = Files.newBufferedReader(ranksFile)) {
                Type t = new TypeToken<ArrayList<Rank>>() {}.getType();
                List<Rank> read = GSON.fromJson(r, t);
                if (read != null) {
                    for (Rank rank : read) {
                        if (rank == null || rank.id == null || rank.text == null || rank.text.isBlank()) continue;
                        rank.id = rank.id.trim().toLowerCase(Locale.ROOT);
                        if (!ID.matcher(rank.id).matches()) {
                            SimSmp.LOGGER.warn("ranks.json: skipped rank '{}' (the id must be a-z, 0-9 or _, at most 11 characters)", rank.id);
                            continue;
                        }
                        if (rank.color == null) rank.color = "#FFFFFF";
                        ranks.putIfAbsent(rank.id, rank);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("Could not read ranks.json - no ranks are active", e);
            ranks.clear();
        }

        if (Files.exists(playersFile)) {
            try (Reader r = Files.newBufferedReader(playersFile)) {
                Type t = new TypeToken<HashMap<String, String>>() {}.getType();
                Map<String, String> read = GSON.fromJson(r, t);
                if (read != null) holders.putAll(read);
            } catch (IOException | RuntimeException e) {
                SimSmp.LOGGER.error("playerranks.json is unreadable - keeping a copy and starting fresh", e);
                try {
                    Files.move(playersFile, playersFile.resolveSibling("playerranks.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        SimSmp.LOGGER.info("Ranks loaded: {} ranks, {} players with a rank", ranks.size(), holders.size());
    }

    private synchronized void saveHolders() {
        try {
            Files.createDirectories(playersFile.getParent());
            Path tmp = playersFile.resolveSibling("playerranks.json.tmp");
            Files.writeString(tmp, GSON.toJson(holders));
            try {
                Files.move(tmp, playersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, playersFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SimSmp.LOGGER.error("Could not save playerranks.json", e);
        }
    }

    // ------------------------------------------------------------------ reading

    public synchronized List<Rank> list() {
        return new ArrayList<>(ranks.values());
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(ranks.keySet());
    }

    public synchronized Rank rankOf(ServerPlayer p) {
        String id = holders.get(p.getUUID().toString());
        return id == null ? null : ranks.get(id);
    }

    /** The coloured tag, with a space after it. */
    public static Component tag(Rank r) {
        int rgb = 0xFFFFFF;
        try {
            rgb = Integer.parseInt(r.color.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (RuntimeException ignored) {
        }
        Style style = Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(r.bold);
        return Component.literal(r.text + " ").withStyle(style);
    }

    // ------------------------------------------------------------------ giving ranks

    /** @return an error text, or null when it worked. */
    public synchronized String set(MinecraftServer server, ServerPlayer p, String rankId) {
        String id = rankId.toLowerCase(Locale.ROOT);
        if (!ranks.containsKey(id)) return "No such rank: " + rankId + ". Type /rank list.";
        holders.put(p.getUUID().toString(), id);
        saveHolders();
        apply(server, p);
        return null;
    }

    /** @return an error text, or null when it worked. */
    public synchronized String remove(MinecraftServer server, ServerPlayer p) {
        if (holders.remove(p.getUUID().toString()) == null) return Msg.name(p) + " has no rank.";
        saveHolders();
        apply(server, p);
        return null;
    }

    /** Puts the player in the team of their rank (creating it when needed), or takes them out of it. */
    public void apply(MinecraftServer server, ServerPlayer p) {
        Scoreboard sb = server.getScoreboard();
        String entry = p.getScoreboardName();
        PlayerTeam current = sb.getPlayersTeam(entry);
        Rank rank = rankOf(p);

        if (rank == null) {
            // only ever leave one of OUR teams, never a team somebody else made
            if (current != null && current.getName().startsWith(TEAM_PREFIX)) sb.removePlayerFromTeam(entry, current);
            return;
        }
        PlayerTeam team = sb.getPlayerTeam(TEAM_PREFIX + rank.id);
        if (team == null) team = sb.addPlayerTeam(TEAM_PREFIX + rank.id);
        team.setPlayerPrefix(tag(rank)); // refreshed every time, so edits of ranks.json show up after /rank reload
        if (current != team) sb.addPlayerToTeam(entry, team);
    }

    public void applyAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) apply(server, p);
    }

    // ------------------------------------------------------------------ /jumpscare (OP only): Elder Guardian

    /**
     * The vanilla Elder Guardian curse: its face jumps at the screen together with its sound, then a short black
     * screen (Blindness without particles or an icon) and a skull as a title. Packets only: nothing changes in the
     * world and nobody needs a client mod.
     */
    private static void scare(ServerPlayer p) {
        p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT, 0.0f));
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 10));
        p.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("Something is watching you").withStyle(ChatFormatting.DARK_RED)));
        p.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("☠").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false));
    }

    private static int jumpscare(CommandSourceStack src, Collection<ServerPlayer> targets) {
        for (ServerPlayer p : targets) scare(p);
        src.sendSuccess(() -> Component.literal("Jumpscared " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    // ------------------------------------------------------------------ commands

    static void register(CommandDispatcher<CommandSourceStack> d, Ranks ranks) {
        // /jumpscare <players>   (OP only)
        d.register(Commands.literal("jumpscare")
                .requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(ctx -> jumpscare(ctx.getSource(), EntityArgument.getPlayers(ctx, "players")))));

        SuggestionProvider<CommandSourceStack> ids = (ctx, builder) -> SharedSuggestionProvider.suggest(ranks.ids(), builder);
        var op = Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER));

        d.register(Commands.literal("rank")
                // /rank -> your own rank
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Rank r = ranks.rankOf(p);
                    if (r == null) {
                        Msg.info(p, "You have no rank.");
                    } else {
                        p.sendSystemMessage(Component.literal("Your rank: ").withStyle(ChatFormatting.GRAY).append(tag(r)));
                    }
                    return 1;
                })
                // /rank list -> every rank, shown the way it looks in chat
                .then(Commands.literal("list").executes(ctx -> {
                    List<Rank> all = ranks.list();
                    if (all.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal("There are no ranks. Check ranks.json."), false);
                        return 0;
                    }
                    for (Rank r : all) {
                        ctx.getSource().sendSuccess(() -> Component.empty().append(tag(r))
                                .append(Component.literal("(" + r.id + ")").withStyle(ChatFormatting.DARK_GRAY)), false);
                    }
                    return all.size();
                }))
                // /rank set <player> <rank>   (OP)
                .then(Commands.literal("set").requires(op)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("rank", StringArgumentType.word()).suggests(ids)
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String rankId = StringArgumentType.getString(ctx, "rank");
                                            String error = ranks.set(ctx.getSource().getServer(), target, rankId);
                                            if (error != null) {
                                                ctx.getSource().sendFailure(Component.literal(error));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Gave " + Msg.name(target) + " the rank " + rankId.toLowerCase(Locale.ROOT) + "."), true);
                                            return 1;
                                        }))))
                // /rank remove <player>   (OP)
                .then(Commands.literal("remove").requires(op)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String error = ranks.remove(ctx.getSource().getServer(), target);
                                    if (error != null) {
                                        ctx.getSource().sendFailure(Component.literal(error));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Removed the rank of " + Msg.name(target) + "."), true);
                                    return 1;
                                })))
                // /rank reload   (OP) after editing ranks.json
                .then(Commands.literal("reload").requires(op).executes(ctx -> {
                    ranks.load();
                    ranks.applyAll(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "ranks.json reloaded: " + ranks.ids().size() + " rank(s)."), false);
                    return 1;
                })));
    }

    // ------------------------------------------------------------------ default ranks (same order and colours as the rank pack picture)

    private static void add(List<Rank> out, String id, String text, String color) {
        Rank r = new Rank();
        r.id = id;
        r.text = text;
        r.color = color;
        out.add(r);
    }

    private static List<Rank> defaults() {
        List<Rank> r = new ArrayList<>();
        add(r, "muted", "[MUTED]", "#A8A8A8");
        add(r, "player", "[PLAYER]", "#C8C8C8");
        add(r, "vip", "[VIP]", "#38B6FF");
        add(r, "pro", "[PRO]", "#3CDB5A");
        add(r, "mvp", "[MVP]", "#FFD83A");
        add(r, "elite", "[ELITE]", "#A64DFF");
        add(r, "helper", "[HELPER]", "#8AE1FF");
        add(r, "nitro", "[NITRO]", "#E040FB");
        add(r, "builder", "[BUILDER]", "#2FA866");
        add(r, "staff", "[STAFF]", "#FF5A4A");
        add(r, "mod", "[MOD]", "#FF9A1F");
        add(r, "supporter", "[SUPPORTER]", "#2EE06A");
        add(r, "admin", "[ADMIN]", "#FF3B3B");
        add(r, "owner", "[OWNER]", "#D61F1F");
        add(r, "developer", "[DEV]", "#3B82F6");
        add(r, "youtube", "[YOUTUBE]", "#FF2D2D");
        add(r, "twitch", "[TWITCH]", "#9146FF");
        add(r, "tiktok", "[TIKTOK]", "#25F4EE");
        return r;
    }
}
