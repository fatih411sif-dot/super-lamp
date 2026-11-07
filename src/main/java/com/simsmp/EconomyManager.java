package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores each player's rupee balance (balances.json) and last known name (names.json). */
public final class EconomyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private static final Type NAME_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Path file;
    private final Path namesFile;
    private final SimConfig cfg;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private volatile boolean namesDirty;

    public EconomyManager(Path file, SimConfig cfg) {
        this.file = file;
        this.namesFile = file.resolveSibling("names.json");
        this.cfg = cfg;
    }

    public void load() {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                Map<String, Long> raw = GSON.fromJson(r, MAP_TYPE);
                if (raw != null) {
                    raw.forEach((k, v) -> {
                        try {
                            balances.put(UUID.fromString(k), v);
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                SimSmp.LOGGER.error("balances.json is unreadable; keeping a backup and starting fresh", e);
                try {
                    Files.move(file, file.resolveSibling("balances.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        if (Files.exists(namesFile)) {
            try (Reader r = Files.newBufferedReader(namesFile)) {
                Map<String, String> raw = GSON.fromJson(r, NAME_TYPE);
                if (raw != null) {
                    raw.forEach((k, v) -> {
                        try {
                            names.put(UUID.fromString(k), v);
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                SimSmp.LOGGER.warn("names.json is unreadable; names will be learned again as players join", e);
            }
        }
    }

    public synchronized void save() {
        if (dirty) {
            dirty = false;
            Map<String, Long> raw = new TreeMap<>();
            balances.forEach((k, v) -> raw.put(k.toString(), v));
            if (!writeAtomic(file, GSON.toJson(raw))) dirty = true;
        }
        if (namesDirty) {
            namesDirty = false;
            Map<String, String> raw = new TreeMap<>();
            names.forEach((k, v) -> raw.put(k.toString(), v));
            if (!writeAtomic(namesFile, GSON.toJson(raw))) namesDirty = true;
        }
    }

    private static boolean writeAtomic(Path target, String json) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            SimSmp.LOGGER.error("Could not save {}", target.getFileName(), e);
            return false;
        }
    }

    /** Gives new players the starting balance. */
    public void ensure(UUID id) {
        balances.computeIfAbsent(id, k -> {
            dirty = true;
            return cfg.startingBalance;
        });
    }

    // ------------------------------------------------------------------ names + leaderboard

    public void rememberName(UUID id, String name) {
        String old = names.put(id, name);
        if (!name.equals(old)) namesDirty = true;
    }

    public String nameOf(UUID id) {
        return names.getOrDefault(id, "Unknown");
    }

    /** Richest players first. */
    public List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> all = new ArrayList<>();
        balances.forEach((k, v) -> all.add(new AbstractMap.SimpleEntry<>(k, v)));
        all.sort(Comparator.<Map.Entry<UUID, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().toString()));
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    /** 1 = richest. */
    public int rankOf(UUID id) {
        long mine = get(id);
        int rank = 1;
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            if (e.getValue() > mine) rank++;
        }
        return rank;
    }

    // ------------------------------------------------------------------ balances

    public long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public boolean has(UUID id, long amount) {
        return get(id) >= amount;
    }

    public void add(UUID id, long amount) {
        balances.merge(id, amount, (a, b) -> {
            long s = a + b;
            if (((a ^ s) & (b ^ s)) < 0) s = Long.MAX_VALUE; // overflow guard
            return s;
        });
        dirty = true;
    }

    /** @return false (and changes nothing) if the player can't afford it. */
    public boolean take(UUID id, long amount) {
        if (amount < 0 || get(id) < amount) return false;
        balances.merge(id, -amount, Long::sum);
        dirty = true;
        return true;
    }

    public String fmt(long amount) {
        return cfg.currencySymbol + String.format(Locale.US, "%,d", amount);
    }
}
