package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Daily shop: every player sees their own random selection of items, which changes every
 * {@code dailyShopHours} hours (default 5). Which items can appear, how often, and the
 * price are set in dailyshop.json ("chance" = weight: higher means it shows up more often).
 *
 * The selection is derived from (player, time period), so nothing about the offers needs to be
 * stored; only which offers a player already bought in the current period is saved.
 */
public final class DailyShop {
    /** One line of dailyshop.json. */
    public static final class Entry {
        public String item;
        public int amount = 1;
        public long price = 100;
        public int chance = 10;
    }

    public record Offer(String id, ItemStack stack, long price) {}

    /** What gets saved between restarts. */
    private static final class State {
        long period = -1;
        Map<String, List<String>> bought = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final int MAX_SLOTS = 25;

    private final SimConfig cfg;
    private final EconomyManager eco;
    private final Path poolFile;
    private final Path stateFile;

    private List<Entry> pool = new ArrayList<>();
    private List<Entry> valid; // pool entries whose item really exists (resolved on first use)
    private long statePeriod = -1;
    private final Map<UUID, Set<String>> bought = new HashMap<>();
    private boolean dirty;

    public DailyShop(SimConfig cfg, EconomyManager eco, Path dir) {
        this.cfg = cfg;
        this.eco = eco;
        this.poolFile = dir.resolve("dailyshop.json");
        this.stateFile = dir.resolve("dailyshop_state.json");
    }

    // ------------------------------------------------------------------ files

    public void load() {
        // item pool (written with defaults the first time, so you can edit it)
        try {
            if (Files.exists(poolFile)) {
                try (Reader r = Files.newBufferedReader(poolFile)) {
                    Type t = new TypeToken<ArrayList<Entry>>() {}.getType();
                    List<Entry> read = GSON.fromJson(r, t);
                    if (read != null) pool = read;
                }
            } else {
                pool = defaultPool();
                Files.createDirectories(poolFile.getParent());
                Files.writeString(poolFile, GSON.toJson(pool));
            }
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("Could not read dailyshop.json, using the default item pool", e);
            pool = defaultPool();
        }

        // purchases of the current period
        if (Files.exists(stateFile)) {
            try (Reader r = Files.newBufferedReader(stateFile)) {
                State s = GSON.fromJson(r, State.class);
                if (s != null && s.period == currentPeriod()) {
                    statePeriod = s.period;
                    for (Map.Entry<String, List<String>> e : s.bought.entrySet()) {
                        try {
                            bought.put(UUID.fromString(e.getKey()), new HashSet<>(e.getValue()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                SimSmp.LOGGER.warn("dailyshop_state.json is unreadable; purchases of this period are forgotten", e);
            }
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        dirty = false;
        State s = new State();
        s.period = statePeriod;
        bought.forEach((k, v) -> s.bought.put(k.toString(), new ArrayList<>(v)));
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling("dailyshop_state.json.tmp");
            Files.writeString(tmp, GSON.toJson(s));
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty = true;
            SimSmp.LOGGER.error("Could not save dailyshop_state.json", e);
        }
    }

    // ------------------------------------------------------------------ time

    private long intervalMillis() {
        return Math.max(1, cfg.dailyShopHours) * 3_600_000L;
    }

    private long currentPeriod() {
        return System.currentTimeMillis() / intervalMillis();
    }

    public long millisUntilRefresh() {
        long interval = intervalMillis();
        return interval - (System.currentTimeMillis() % interval);
    }

    private void rollover() {
        long period = currentPeriod();
        if (period != statePeriod) {
            statePeriod = period;
            bought.clear();
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ offers

    private static Item resolve(String id) {
        return ItemIds.find(id);
    }

    private synchronized List<Entry> validEntries() {
        if (valid == null) {
            valid = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Entry e : pool) {
                if (e == null || e.chance <= 0 || e.price < 0) continue;
                if (resolve(e.item) == null) {
                    SimSmp.LOGGER.warn("dailyshop.json: unknown item '{}' skipped", e.item);
                    continue;
                }
                String key = ItemIds.normalize(e.item);
                if (seen.add(key)) valid.add(e);
            }
        }
        return valid;
    }

    /** This player's offers for the current period (same list every time within a period). */
    public synchronized List<Offer> offersFor(UUID player) {
        rollover();
        List<Entry> candidates = new ArrayList<>(validEntries());
        int slots = Math.max(1, Math.min(MAX_SLOTS, cfg.dailyShopSlots));

        long seed = player.getMostSignificantBits() * 31 + player.getLeastSignificantBits();
        seed = seed * 0x9E3779B97F4A7C15L + statePeriod * 0xC2B2AE3D27D4EB4FL;
        Random random = new Random(seed);

        List<Offer> out = new ArrayList<>();
        while (out.size() < slots && !candidates.isEmpty()) {
            long total = 0;
            for (Entry e : candidates) total += e.chance;
            long roll = (long) (random.nextDouble() * total);
            Entry picked = candidates.get(candidates.size() - 1);
            for (Entry e : candidates) {
                roll -= e.chance;
                if (roll < 0) {
                    picked = e;
                    break;
                }
            }
            candidates.remove(picked);

            Item item = resolve(picked.item);
            ItemStack stack = new ItemStack(item, 1);
            int amount = Math.max(1, Math.min(picked.amount, stack.getMaxStackSize()));
            stack.setCount(amount);
            out.add(new Offer(ItemIds.normalize(picked.item), stack, picked.price));
        }
        return out;
    }

    public synchronized boolean isBought(UUID player, String id) {
        Set<String> set = bought.get(player);
        return set != null && set.contains(id);
    }

    public synchronized boolean buy(ServerPlayer p, String id) {
        Offer offer = null;
        for (Offer o : offersFor(p.getUUID())) {
            if (o.id().equals(id)) {
                offer = o;
                break;
            }
        }
        if (offer == null) {
            Msg.err(p, "That offer is gone - the shop has refreshed.");
            return false;
        }
        if (isBought(p.getUUID(), id)) {
            Msg.err(p, "You already bought that today.");
            return false;
        }
        if (!eco.take(p.getUUID(), offer.price())) {
            Msg.err(p, "You need " + eco.fmt(offer.price()) + " but only have " + eco.fmt(eco.get(p.getUUID())) + ".");
            return false;
        }
        bought.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(id);
        dirty = true;
        ItemGiver.give(p, offer.stack().copy());
        save();
        Msg.ok(p, "You bought " + offer.stack().getCount() + "x " + offer.stack().getHoverName().getString()
                + " for " + eco.fmt(offer.price()) + ".");
        return true;
    }

    // ------------------------------------------------------------------ default pool

    private static Entry e(String item, int amount, long price, int chance) {
        Entry x = new Entry();
        x.item = "minecraft:" + item;
        x.amount = amount;
        x.price = price;
        x.chance = chance;
        return x;
    }

    /** chance: common 100, uncommon 40, rare 10, epic 3. Edit dailyshop.json to change anything. */
    private static List<Entry> defaultPool() {
        List<Entry> p = new ArrayList<>();
        // ---- common
        p.add(e("bread", 16, 40, 100));
        p.add(e("cooked_beef", 16, 80, 100));
        p.add(e("cooked_porkchop", 16, 70, 100));
        p.add(e("torch", 32, 30, 100));
        p.add(e("oak_log", 32, 100, 100));
        p.add(e("oak_planks", 64, 110, 100));
        p.add(e("cobblestone", 64, 80, 100));
        p.add(e("sand", 64, 80, 100));
        p.add(e("glass", 32, 60, 100));
        p.add(e("coal", 32, 200, 100));
        p.add(e("arrow", 32, 90, 100));
        p.add(e("string", 16, 60, 100));
        p.add(e("bone", 16, 50, 100));
        p.add(e("leather", 8, 60, 100));
        p.add(e("sugar_cane", 32, 70, 100));
        p.add(e("wheat", 32, 90, 100));
        p.add(e("carrot", 32, 70, 100));
        p.add(e("potato", 32, 70, 100));
        p.add(e("apple", 8, 50, 100));
        p.add(e("cookie", 16, 40, 100));
        p.add(e("book", 4, 60, 100));
        // ---- uncommon
        p.add(e("iron_ingot", 16, 220, 40));
        p.add(e("copper_ingot", 32, 200, 40));
        p.add(e("gold_ingot", 8, 140, 40));
        p.add(e("redstone", 32, 180, 40));
        p.add(e("lapis_lazuli", 16, 120, 40));
        p.add(e("quartz", 16, 110, 40));
        p.add(e("gunpowder", 8, 80, 40));
        p.add(e("slime_ball", 8, 120, 40));
        p.add(e("ender_pearl", 4, 160, 40));
        p.add(e("blaze_rod", 4, 110, 40));
        p.add(e("experience_bottle", 8, 240, 40));
        p.add(e("golden_carrot", 16, 150, 40));
        p.add(e("golden_apple", 1, 200, 40));
        p.add(e("obsidian", 8, 130, 40));
        p.add(e("glowstone", 16, 120, 40));
        p.add(e("sea_lantern", 4, 100, 40));
        p.add(e("honey_bottle", 4, 80, 40));
        p.add(e("cake", 1, 60, 40));
        p.add(e("bucket", 1, 90, 40));
        p.add(e("hopper", 1, 140, 40));
        p.add(e("name_tag", 1, 180, 40));
        p.add(e("saddle", 1, 200, 40));
        p.add(e("shield", 1, 150, 40));
        p.add(e("bow", 1, 120, 40));
        p.add(e("crossbow", 1, 160, 40));
        p.add(e("iron_sword", 1, 160, 40));
        p.add(e("iron_pickaxe", 1, 220, 40));
        p.add(e("iron_chestplate", 1, 330, 40));
        // ---- rare
        p.add(e("diamond", 2, 300, 10));
        p.add(e("emerald", 4, 380, 10));
        p.add(e("diamond_sword", 1, 650, 10));
        p.add(e("diamond_pickaxe", 1, 700, 10));
        p.add(e("diamond_axe", 1, 700, 10));
        p.add(e("diamond_shovel", 1, 300, 10));
        p.add(e("diamond_helmet", 1, 700, 10));
        p.add(e("diamond_chestplate", 1, 1100, 10));
        p.add(e("diamond_leggings", 1, 1000, 10));
        p.add(e("diamond_boots", 1, 600, 10));
        p.add(e("ancient_debris", 1, 450, 10));
        p.add(e("netherite_scrap", 1, 500, 10));
        p.add(e("shulker_shell", 2, 500, 10));
        p.add(e("nautilus_shell", 1, 260, 10));
        p.add(e("heart_of_the_sea", 1, 800, 10));
        p.add(e("trident", 1, 900, 10));
        p.add(e("ender_chest", 1, 350, 10));
        p.add(e("enchanting_table", 1, 450, 10));
        p.add(e("anvil", 1, 250, 10));
        p.add(e("lodestone", 1, 600, 10));
        p.add(e("respawn_anchor", 1, 550, 10));
        p.add(e("crying_obsidian", 4, 250, 10));
        p.add(e("tnt", 8, 280, 10));
        p.add(e("wind_charge", 16, 400, 10));
        p.add(e("breeze_rod", 4, 380, 10));
        // ---- epic
        p.add(e("netherite_ingot", 1, 2500, 3));
        p.add(e("totem_of_undying", 1, 1800, 3));
        p.add(e("elytra", 1, 4500, 3));
        p.add(e("enchanted_golden_apple", 1, 2000, 3));
        p.add(e("beacon", 1, 3500, 3));
        p.add(e("nether_star", 1, 6000, 3));
        p.add(e("netherite_upgrade_smithing_template", 1, 1500, 3));
        p.add(e("music_disc_pigstep", 1, 1200, 3));
        p.add(e("heavy_core", 1, 2000, 3));
        p.add(e("mace", 1, 3000, 3));
        return p;
    }
}
