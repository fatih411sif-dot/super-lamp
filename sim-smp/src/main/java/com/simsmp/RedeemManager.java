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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Redeem codes. Codes and their rewards live in redeem.json (editable by hand, or with the /rcode
 * commands); who already used which code lives in redeem_state.json.
 *
 * Rules: codes are not case sensitive, every player can use a code once, "maxUses" limits how many
 * players in total can use it (0 = unlimited), "expires" is a UTC time (empty = never).
 */
public final class RedeemManager {
    /** One reward item: only the item type and amount are kept (no enchantments or names). */
    public static final class Reward {
        public String item;
        public int amount = 1;
    }

    /** One line of redeem.json. */
    public static final class Code {
        public String code;
        public boolean enabled = true;
        public long money = 0;
        public List<Reward> items = new ArrayList<>();
        public int maxUses = 0;
        public String expires = ""; // "yyyy-MM-dd HH:mm" in UTC, empty = never
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    private static final int MAX_FAILS = 5;       // wrong codes allowed...
    private static final long WINDOW_MS = 60_000; // ...per minute

    private final EconomyManager eco;
    private final Path codesFile;
    private final Path stateFile;
    private final Map<String, Code> codes = new LinkedHashMap<>();   // key = upper-case code
    private final Map<String, Set<UUID>> used = new HashMap<>();
    private final Map<UUID, long[]> failures = new HashMap<>();      // uuid -> {count, window start}

    public RedeemManager(EconomyManager eco, Path dir) {
        this.eco = eco;
        this.codesFile = dir.resolve("redeem.json");
        this.stateFile = dir.resolve("redeem_state.json");
    }

    // ------------------------------------------------------------------ files

    public synchronized void load() {
        codes.clear();
        used.clear();

        try {
            if (Files.exists(codesFile)) {
                try (Reader r = Files.newBufferedReader(codesFile)) {
                    Type t = new TypeToken<ArrayList<Code>>() {}.getType();
                    List<Code> read = GSON.fromJson(r, t);
                    if (read != null) {
                        for (Code c : read) {
                            if (c == null || c.code == null || c.code.isBlank()) continue;
                            if (c.items == null) c.items = new ArrayList<>();
                            if (c.expires == null) c.expires = "";
                            c.code = key(c.code);
                            codes.put(c.code, c);
                            validate(c);
                        }
                    }
                }
            } else {
                Code example = new Code();
                example.code = "EXAMPLE";
                example.enabled = false; // switched off so nobody can use it by accident
                example.money = 500;
                Reward r = new Reward();
                r.item = "minecraft:diamond";
                r.amount = 2;
                example.items.add(r);
                example.maxUses = 100;
                example.expires = "2030-01-01 00:00";
                codes.put(example.code, example);
                saveCodes();
            }
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("Could not read redeem.json - no codes are active", e);
            codes.clear();
        }

        if (Files.exists(stateFile)) {
            try (Reader r = Files.newBufferedReader(stateFile)) {
                Type t = new TypeToken<HashMap<String, List<String>>>() {}.getType();
                Map<String, List<String>> raw = GSON.fromJson(r, t);
                if (raw != null) {
                    raw.forEach((k, list) -> {
                        Set<UUID> set = new HashSet<>();
                        for (String s : list) {
                            try {
                                set.add(UUID.fromString(s));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        used.put(k.toUpperCase(Locale.ROOT), set);
                    });
                }
            } catch (IOException | RuntimeException e) {
                SimSmp.LOGGER.error("redeem_state.json is unreadable - keeping a copy and starting fresh", e);
                try {
                    Files.move(stateFile, stateFile.resolveSibling("redeem_state.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        SimSmp.LOGGER.info("Redeem codes loaded: {}", codes.size());
    }

    private void validate(Code c) {
        for (Reward r : c.items) {
            if (r == null || ItemIds.find(r.item) == null) {
                SimSmp.LOGGER.warn("redeem.json: code {} has an unknown item '{}' (it will be skipped)", c.code, r == null ? null : r.item);
            }
        }
        if (expiresAt(c) == -1) {
            SimSmp.LOGGER.warn("redeem.json: code {} has an invalid \"expires\" ('{}'); use yyyy-MM-dd HH:mm. The code counts as expired.",
                    c.code, c.expires);
        }
    }

    private synchronized void saveCodes() {
        writeAtomic(codesFile, GSON.toJson(new ArrayList<>(codes.values())));
    }

    private synchronized void saveState() {
        Map<String, List<String>> raw = new HashMap<>();
        used.forEach((k, set) -> {
            List<String> list = new ArrayList<>();
            for (UUID id : set) list.add(id.toString());
            raw.put(k, list);
        });
        writeAtomic(stateFile, GSON.toJson(raw));
    }

    private static void writeAtomic(Path target, String json) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SimSmp.LOGGER.error("Could not save {}", target.getFileName(), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 0 = never expires, -1 = invalid text (treated as expired), otherwise the time in millis (UTC). */
    private static long expiresAt(Code c) {
        if (c.expires == null || c.expires.isBlank()) return 0;
        try {
            return LocalDateTime.parse(c.expires.trim(), TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    private static boolean isExpired(Code c, long now) {
        long exp = expiresAt(c);
        return exp == -1 || (exp > 0 && now >= exp);
    }

    private int usesOf(String key) {
        Set<UUID> set = used.get(key);
        return set == null ? 0 : set.size();
    }

    // ------------------------------------------------------------------ players: /redeem

    public synchronized void redeem(ServerPlayer p, String raw) {
        UUID id = p.getUUID();
        long now = System.currentTimeMillis();

        long[] f = failures.get(id);
        if (f != null && f[0] >= MAX_FAILS && now - f[1] < WINDOW_MS) {
            Msg.err(p, "Too many wrong codes. Try again in " + ((WINDOW_MS - (now - f[1]) + 999) / 1000) + "s.");
            return;
        }

        String key = key(raw);
        Code c = codes.get(key);
        if (c == null || !c.enabled) {
            if (f == null || now - f[1] >= WINDOW_MS) failures.put(id, new long[]{1, now});
            else f[0]++;
            Msg.err(p, "That code is not valid.");
            return;
        }
        if (isExpired(c, now)) {
            Msg.err(p, "This code has expired.");
            return;
        }
        Set<UUID> set = used.computeIfAbsent(key, k -> new HashSet<>());
        if (set.contains(id)) {
            Msg.err(p, "You already used this code.");
            return;
        }
        if (c.maxUses > 0 && set.size() >= c.maxUses) {
            Msg.err(p, "This code has reached its use limit.");
            return;
        }

        // all checks passed: record the use first, then pay out
        set.add(id);
        failures.remove(id);
        saveState();

        List<String> parts = new ArrayList<>();
        if (c.money > 0) {
            eco.add(id, c.money);
            parts.add(eco.fmt(c.money));
        }
        for (Reward r : c.items) {
            if (r == null) continue;
            Item item = ItemIds.find(r.item);
            if (item == null) continue;
            int total = Math.max(1, r.amount);
            int max = new ItemStack(item).getMaxStackSize();
            for (int left = total; left > 0; ) {
                int n = Math.min(left, max);
                ItemGiver.give(p, new ItemStack(item, n));
                left -= n;
            }
            parts.add(total + "x " + Msg.pretty(r.item));
        }

        SimSmp.LOGGER.info("{} redeemed code {}", Msg.name(p), key);
        Msg.ok(p, "Code " + key + " redeemed! You received: " + (parts.isEmpty() ? "nothing" : String.join(", ", parts)) + ".");
    }

    // ------------------------------------------------------------------ admins: /rcode (each returns an error text, or null when it worked)

    public synchronized List<String> codeNames() {
        return new ArrayList<>(codes.keySet());
    }

    public synchronized String create(String raw, long money) {
        if (!NAME.matcher(raw.trim()).matches()) return "A code must be 3-32 characters: letters, numbers, _ or -.";
        String key = key(raw);
        if (codes.containsKey(key)) return "Code " + key + " already exists.";
        Code c = new Code();
        c.code = key;
        c.money = Math.max(0, money);
        codes.put(key, c);
        saveCodes();
        return null;
    }

    public synchronized String delete(String raw) {
        String key = key(raw);
        if (codes.remove(key) == null) return "No such code: " + key;
        used.remove(key);
        saveCodes();
        saveState();
        return null;
    }

    public synchronized String setMoney(String raw, long money) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        c.money = Math.max(0, money);
        saveCodes();
        return null;
    }

    public synchronized String setMaxUses(String raw, int maxUses) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        c.maxUses = Math.max(0, maxUses);
        saveCodes();
        return null;
    }

    /** hours <= 0 removes the expiry. */
    public synchronized String setExpireHours(String raw, int hours) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        c.expires = hours <= 0 ? "" : LocalDateTime.now(ZoneOffset.UTC).plusHours(hours).format(TIME);
        saveCodes();
        return null;
    }

    public synchronized String setEnabled(String raw, boolean enabled) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        c.enabled = enabled;
        saveCodes();
        return null;
    }

    public synchronized String addItem(String raw, String itemId, int amount) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        if (ItemIds.find(itemId) == null) return "Unknown item: " + itemId;
        String id = ItemIds.normalize(itemId);
        for (Reward r : c.items) {
            if (r != null && id.equals(ItemIds.normalize(r.item))) {
                r.amount += Math.max(1, amount);
                saveCodes();
                return null;
            }
        }
        Reward r = new Reward();
        r.item = id;
        r.amount = Math.max(1, amount);
        c.items.add(r);
        saveCodes();
        return null;
    }

    public synchronized String clearItems(String raw) {
        Code c = codes.get(key(raw));
        if (c == null) return "No such code: " + key(raw);
        c.items.clear();
        saveCodes();
        return null;
    }

    public synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Code c : codes.values()) {
            String status = !c.enabled ? "OFF" : isExpired(c, now) ? "EXPIRED" : "on";
            out.add(c.code + "  [" + status + "]  " + eco.fmt(c.money) + " + " + c.items.size() + " item(s)  used "
                    + usesOf(c.code) + (c.maxUses > 0 ? "/" + c.maxUses : ""));
        }
        return out;
    }

    /** Details of one code, or null if it does not exist. */
    public synchronized List<String> info(String raw) {
        Code c = codes.get(key(raw));
        if (c == null) return null;
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        out.add("Code: " + c.code + (c.enabled ? " (enabled)" : " (DISABLED)"));
        out.add("Money: " + eco.fmt(c.money));
        List<String> items = new ArrayList<>();
        for (Reward r : c.items) {
            if (r != null) items.add(r.amount + "x " + Msg.pretty(r.item));
        }
        out.add("Items: " + (items.isEmpty() ? "none" : String.join(", ", items)));
        out.add("Used: " + usesOf(c.code) + (c.maxUses > 0 ? " of " + c.maxUses : " (no limit)"));
        out.add("Expires: " + (c.expires == null || c.expires.isBlank() ? "never" : c.expires + " UTC")
                + (isExpired(c, now) ? "  -> EXPIRED" : ""));
        return out;
    }
}
