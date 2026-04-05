package l3gh.inv.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public class InvSerializer {

    // ── online player (reads live in-memory inventory) ────────────────────────

    public static JsonObject serialize(ServerPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid",      player.getStringUUID());
        root.addProperty("name",      player.getName().getString());
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("source",    "live");

        root.add("inventory",  serializeSlots(player.getInventory(), 0, 40));
        root.add("enderchest", serializeSlots(player.getEnderChestInventory(), 0, 26));

        return root;
    }

    private static JsonArray serializeSlots(Container inv, int from, int to) {
        JsonArray arr = new JsonArray();
        for (int slot = from; slot <= to; slot++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            ItemStack stack = inv.getItem(slot);
            entry.add("item", stack.isEmpty() ? JsonNull.INSTANCE : serializeStack(stack));
            arr.add(entry);
        }
        return arr;
    }

    private static JsonObject serializeStack(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("id",    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        if (stack.hasTag()) {
            JsonElement nbtJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, stack.getTag());
            item.add("nbt", nbtJson);
        }
        return item;
    }

    // ── offline player (reads from raw NBT playerdata .dat file) ─────────────
    //
    // Playerdata .dat slot numbering differs from in-memory:
    //   0-8   = hotbar         (same)
    //   9-35  = main inv       (same)
    //   100   = feet armor     → we remap to slot 36
    //   101   = legs armor     → slot 37
    //   102   = chest armor    → slot 38
    //   103   = head armor     → slot 39
    //   -106  = offhand        → slot 40
    //   Enderchest is stored under "EnderItems" with slots 0-26 (same)

    public static JsonObject serializeFromDat(String uuid, CompoundTag nbt) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid",      uuid);
        root.addProperty("name",      ""); // no name in .dat; frontend uses its own name lookup
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("source",    "offline");

        root.add("inventory",  serializeInventoryFromNbt(nbt));
        root.add("enderchest", serializeEnderChestFromNbt(nbt));

        return root;
    }

    private static JsonArray serializeInventoryFromNbt(CompoundTag nbt) {
        // Build a slot→item map from the NBT list, then output in standard slot order 0-40
        JsonObject slotMap = new JsonObject();

        ListTag items = nbt.getList("Inventory", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF; // treat as unsigned first

            // remap .dat armor/offhand slots to our standard 36-40
            int outSlot;
            if      (slot == 100) outSlot = 36; // feet
            else if (slot == 101) outSlot = 37; // legs
            else if (slot == 102) outSlot = 38; // chest
            else if (slot == 103) outSlot = 39; // head
            else if (entry.getByte("Slot") == -106) outSlot = 40; // offhand (signed -106)
            else    outSlot = slot; // 0-35 map directly

            slotMap.add(String.valueOf(outSlot), serializeNbtItem(entry));
        }

        JsonArray arr = new JsonArray();
        for (int slot = 0; slot <= 40; slot++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            String key = String.valueOf(slot);
            entry.add("item", slotMap.has(key) ? slotMap.get(key) : JsonNull.INSTANCE);
            arr.add(entry);
        }
        return arr;
    }

    private static JsonArray serializeEnderChestFromNbt(CompoundTag nbt) {
        JsonObject slotMap = new JsonObject();

        ListTag items = nbt.getList("EnderItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF;
            slotMap.add(String.valueOf(slot), serializeNbtItem(entry));
        }

        JsonArray arr = new JsonArray();
        for (int slot = 0; slot <= 26; slot++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            String key = String.valueOf(slot);
            entry.add("item", slotMap.has(key) ? slotMap.get(key) : JsonNull.INSTANCE);
            arr.add(entry);
        }
        return arr;
    }

    private static JsonObject serializeNbtItem(CompoundTag entry) {
        JsonObject item = new JsonObject();
        item.addProperty("id",    entry.getString("id"));
        item.addProperty("count", entry.getByte("Count") & 0xFF);

        // item NBT data is stored under "tag" in .dat files
        if (entry.contains("tag", Tag.TAG_COMPOUND)) {
            CompoundTag tag = entry.getCompound("tag");
            JsonElement nbtJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
            item.add("nbt", nbtJson);
        }

        return item;
    }
}