package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The prize pool of /gacha. Every prize has a "chance" (a weight: higher = drops more often) and a rarity
 * label. Edit config/sim_smp/gacha.json to change items, amounts and chances.
 */
public final class Gacha {
    public enum Rarity {
        COMMON("Common", ChatFormatting.WHITE),
        UNCOMMON("Uncommon", ChatFormatting.GREEN),
        RARE("Rare", ChatFormatting.AQUA),
        EPIC("Epic", ChatFormatting.LIGHT_PURPLE),
        LEGENDARY("Legendary", ChatFormatting.GOLD);

        public final String label;
        public final ChatFormatting color;

        Rarity(String label, ChatFormatting color) {
            this.label = label;
            this.color = color;
        }

        static Rarity parse(String s) {
            if (s != null) {
                for (Rarity r : values()) {
                    if (r.name().equalsIgnoreCase(s.trim())) return r;
                }
            }
            return COMMON;
        }
    }

    /** One line of gacha.json. */
    public static final class Entry {
        public String item;
        public int amount = 1;
        public int chance = 100;
        public String rarity = "COMMON";
    }

    public record Prize(ItemStack stack, Rarity rarity) {}

    private record Slot(Item item, int amount, int chance, Rarity rarity) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private List<Entry> pool = new ArrayList<>();
    private List<Slot> slots; // resolved on first use

    public Gacha(Path file) {
        this.file = file;
    }

    public void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Type t = new TypeToken<ArrayList<Entry>>() {}.getType();
                    List<Entry> read = GSON.fromJson(r, t);
                    if (read != null) pool = read;
                }
            } else {
                pool = defaultPool();
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(pool));
            }
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("Could not read gacha.json, using the default prize pool", e);
            pool = defaultPool();
        }
    }

    private synchronized List<Slot> slots() {
        if (slots == null) {
            List<Slot> out = new ArrayList<>();
            for (Entry e : pool) {
                if (e == null || e.chance <= 0) continue;
                Item item = ItemIds.find(e.item);
                if (item == null) {
                    SimSmp.LOGGER.warn("gacha.json: unknown item '{}' skipped", e.item);
                    continue;
                }
                int max = new ItemStack(item).getMaxStackSize();
                out.add(new Slot(item, Math.max(1, Math.min(e.amount, max)), e.chance, Rarity.parse(e.rarity)));
            }
            slots = out;
        }
        return slots;
    }

    public boolean isEmpty() {
        return slots().isEmpty();
    }

    /** Picks a prize using the chances. */
    public Prize roll() {
        List<Slot> s = slots();
        if (s.isEmpty()) return null;
        long total = 0;
        for (Slot x : s) total += x.chance();
        long r = ThreadLocalRandom.current().nextLong(total);
        for (Slot x : s) {
            r -= x.chance();
            if (r < 0) return prize(x);
        }
        return prize(s.get(s.size() - 1));
    }

    /** A random prize icon for the spinning animation (ignores the chances). */
    public ItemStack randomIcon() {
        List<Slot> s = slots();
        Slot x = s.get(ThreadLocalRandom.current().nextInt(s.size()));
        return new ItemStack(x.item(), x.amount());
    }

    private static Prize prize(Slot x) {
        return new Prize(new ItemStack(x.item(), x.amount()), x.rarity());
    }

    /** Drop rate of every rarity in percent. */
    public Map<Rarity, Double> rates() {
        Map<Rarity, Double> out = new LinkedHashMap<>();
        for (Rarity r : Rarity.values()) out.put(r, 0.0);
        long total = 0;
        for (Slot x : slots()) total += x.chance();
        if (total <= 0) return out;
        for (Slot x : slots()) {
            out.merge(x.rarity(), 100.0 * x.chance() / total, Double::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------ default pool

    /** Adds the items of one rarity; {@code total} is shared equally between them. */
    private static void tier(List<Entry> out, Rarity rarity, int total, String... items) {
        int each = Math.max(1, total / items.length);
        for (String spec : items) {
            String[] parts = spec.split("=");
            Entry e = new Entry();
            e.item = "minecraft:" + parts[0];
            e.amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            e.chance = each;
            e.rarity = rarity.name();
            out.add(e);
        }
    }

    /**
     * Default odds per pull: Common 70%, Uncommon 22%, Rare 6.5%, Epic 1.4%, Legendary 0.1%.
     * (The "chance" numbers add up to 100000, so 1000 = 1%.)
     */
    private static List<Entry> defaultPool() {
        List<Entry> p = new ArrayList<>();
        tier(p, Rarity.COMMON, 70000,
                "bread=8", "torch=16", "oak_log=16", "cobblestone=32", "sand=32", "arrow=16", "string=8", "bone=8",
                "carrot=16", "potato=16", "cooked_beef=6", "glass=16", "redstone=8", "coal=8", "raw_copper=8",
                "raw_iron=4", "apple=4", "wheat=16", "sugar_cane=16", "cookie=8");
        tier(p, Rarity.UNCOMMON, 22000,
                "iron_ingot=8", "gold_ingot=4", "lapis_lazuli=8", "quartz=8", "ender_pearl=2", "blaze_rod=2",
                "slime_ball=4", "experience_bottle=4", "golden_apple=1", "golden_carrot=8", "obsidian=4",
                "gunpowder=8", "glowstone=8", "copper_ingot=16");
        tier(p, Rarity.RARE, 6500,
                "diamond=1", "emerald=2", "ancient_debris=1", "shulker_shell=1", "nautilus_shell=1", "name_tag=1",
                "saddle=1", "iron_block=2", "gold_block=1", "experience_bottle=16", "ender_chest=1",
                "enchanting_table=1", "anvil=1", "crying_obsidian=4");
        tier(p, Rarity.EPIC, 1400,
                "diamond_sword=1", "diamond_pickaxe=1", "diamond_chestplate=1", "diamond_helmet=1", "diamond_block=1",
                "netherite_scrap=2", "totem_of_undying=1", "enchanted_golden_apple=1", "trident=1",
                "heart_of_the_sea=1", "lodestone=1", "respawn_anchor=1");
        tier(p, Rarity.LEGENDARY, 100,
                "netherite_ingot=1", "nether_star=1", "elytra=1", "beacon=1", "mace=1", "heavy_core=1");
        return p;
    }
}
