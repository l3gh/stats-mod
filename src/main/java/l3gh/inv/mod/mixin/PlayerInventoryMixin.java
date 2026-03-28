package l3gh.inv.mod.mixin;

import l3gh.inv.mod.InvMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Inventory.setChanged() — Mojang mappings name for markDirty().
 * Called whenever any slot in the player's inventory changes.
 * Gated on ServerPlayer so this never fires client-side.
 */
@Mixin(Inventory.class)
public class PlayerInventoryMixin {

    @Shadow public Player player;

    @Inject(method = "setChanged", at = @At("TAIL"))
    private void onSetChanged(CallbackInfo ci) {
        if (this.player instanceof ServerPlayer sp) {
            InvMod.onInventoryChanged(sp);
        }
    }
}
