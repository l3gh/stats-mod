package l3gh.inv.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public class InvSerializer {

    /**
     * Serialises a player's full inventory state to JSON.
     *
     * Slot layout:
     *   inventory[0-8]   = hotbar
     *   inventory[9-35]  = main inventory
     *   inventory[36-39] = armor (feet=36, legs=37, chest=38, head=39)
     *   inventory[40]    = offhand
     *   enderchest[0-26] = enderchest (3 rows × 9 cols)
     *
     * Shulker boxes appear as normal items. Their contents are inside
     * nbt.BlockEntityTag.Items — the frontend reads that for the popup.
     */
    public static JsonObject serialize(ServerPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid",      player.getStringUUID());
        root.addProperty("name",      player.getName().getString());
        root.addProperty("timestamp", System.currentTimeMillis());

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
        // Mojang mappings: BuiltInRegistries instead of Registries, getKey() instead of getId()
        item.addProperty("id",    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());

        // Mojang mappings: hasTag() / getTag() instead of hasNbt() / getNbt()
        if (stack.hasTag()) {
            CompoundTag nbt = stack.getTag();
            JsonElement nbtJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, nbt);
            item.add("nbt", nbtJson);
        }

        return item;
    }
}
