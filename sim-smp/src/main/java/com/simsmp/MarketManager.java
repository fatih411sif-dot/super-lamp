package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-to-player market. Players list the item in their hand with /market sell <price>;
 * anyone can browse and buy from the /market GUI. Listings are saved in market.json.
 */
public final class MarketManager {
    public record Listing(long id, UUID seller, String sellerName, ItemStack stack, long price) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SimConfig cfg;
    private final EconomyManager eco;
    private final Path file;
    private final List<Listing> listings = new ArrayList<>();
    private long nextId = 1;
    private MinecraftServer server;

    public MarketManager(SimConfig cfg, EconomyManager eco, Path file) {
        this.cfg = cfg;
        this.eco = eco;
        this.file = file;
    }

    // ------------------------------------------------------------------ persistence

    /** Must run after the server started (needs the registries to read items). */
    public void load(MinecraftServer server) {
        this.server = server;
        if (!Files.exists(file)) return;
        try {
            Files.copy(file, file.resolveSibling("market.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            nextId = root.get("nextId").getAsLong();
            for (JsonElement e : root.getAsJsonArray("listings")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    Optional<ItemStack> stack = net.minecraft.world.item.ItemStack.CODEC.parse(ops, o.get("item")).result();
                    if (stack.isEmpty() || stack.get().isEmpty()) continue;
                    listings.add(new Listing(
                            o.get("id").getAsLong(),
                            UUID.fromString(o.get("seller").getAsString()),
                            o.get("sellerName").getAsString(),
                            stack.get(),
                            o.get("price").getAsLong()));
                } catch (RuntimeException ex) {
                    SimSmp.LOGGER.warn("Skipping an unreadable market listing (see market.json.bak)", ex);
                }
            }
        } catch (IOException | RuntimeException ex) {
            SimSmp.LOGGER.error("Could not read market.json (a backup is in market.json.bak)", ex);
        }
    }

    public synchronized void save() {
        if (server == null) return;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        JsonObject root = new JsonObject();
        root.addProperty("nextId", nextId);
        JsonArray arr = new JsonArray();
        for (Listing l : listings) {
            Optional<JsonElement> item = ItemStack.CODEC.encodeStart(ops, l.stack()).result();
            if (item.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id());
            o.addProperty("seller", l.seller().toString());
            o.addProperty("sellerName", l.sellerName());
            o.addProperty("price", l.price());
            o.add("item", item.get());
            arr.add(o);
        }
        root.add("listings", arr);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("market.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SimSmp.LOGGER.error("Could not save market.json", e);
        }
    }

    // ------------------------------------------------------------------ queries

    /** Newest first. mineOnly = only this player's listings. */
    public List<Listing> view(boolean mineOnly, UUID me) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings) {
            if (!mineOnly || l.seller().equals(me)) out.add(l);
        }
        Collections.reverse(out);
        return out;
    }

    public Listing find(long id) {
        for (Listing l : listings) {
            if (l.id() == id) return l;
        }
        return null;
    }

    private static String label(Listing l) {
        return l.stack().getCount() + "x " + l.stack().getHoverName().getString();
    }

    // ------------------------------------------------------------------ actions

    /** /market sell <price>: lists the stack in the player's main hand. */
    public void list(ServerPlayer p, long price) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) {
            Msg.err(p, "Hold the item you want to sell in your main hand.");
            return;
        }
        long mine = listings.stream().filter(l -> l.seller().equals(p.getUUID())).count();
        if (mine >= cfg.maxListingsPerPlayer) {
            Msg.err(p, "You already have " + mine + " listings (max " + cfg.maxListingsPerPlayer + "). Take one back in /market first.");
            return;
        }
        ItemStack copy = held.copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Listing l = new Listing(nextId++, p.getUUID(), Msg.name(p), copy, price);
        listings.add(l);
        save();
        Msg.ok(p, "Listed " + label(l) + " for " + eco.fmt(price) + ". Open /market to see it.");
    }

    public boolean buy(ServerPlayer buyer, long id) {
        Listing l = find(id);
        if (l == null) {
            Msg.err(buyer, "That listing is no longer available.");
            return false;
        }
        if (l.seller().equals(buyer.getUUID())) {
            Msg.err(buyer, "That's your own listing. Switch to \"My listings\" to take it back.");
            return false;
        }
        if (!eco.take(buyer.getUUID(), l.price())) {
            Msg.err(buyer, "You need " + eco.fmt(l.price()) + " but only have " + eco.fmt(eco.get(buyer.getUUID())) + ".");
            return false;
        }
        listings.remove(l);
        long tax = l.price() * Math.max(0, Math.min(100, cfg.marketTaxPercent)) / 100;
        long payout = l.price() - tax;
        eco.add(l.seller(), payout);
        ItemGiver.give(buyer, l.stack().copy());
        save();

        Msg.ok(buyer, "You bought " + label(l) + " for " + eco.fmt(l.price()) + ".");
        if (server != null) {
            ServerPlayer seller = server.getPlayerList().getPlayer(l.seller());
            if (seller != null) {
                Msg.ok(seller, Msg.name(buyer) + " bought your " + label(l) + ". You received " + eco.fmt(payout)
                        + (tax > 0 ? " (fee " + eco.fmt(tax) + ")" : "") + ".");
            }
        }
        return true;
    }

    /** Seller takes an unsold listing back. */
    public void cancel(ServerPlayer p, long id) {
        Listing l = find(id);
        if (l == null) {
            Msg.err(p, "That listing is no longer available.");
            return;
        }
        if (!l.seller().equals(p.getUUID())) {
            Msg.err(p, "That isn't your listing.");
            return;
        }
        listings.remove(l);
        ItemGiver.give(p, l.stack().copy());
        save();
        Msg.ok(p, "You took back " + label(l) + ".");
    }
}
