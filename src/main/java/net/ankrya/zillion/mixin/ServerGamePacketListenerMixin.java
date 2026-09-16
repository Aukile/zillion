package net.ankrya.zillion.mixin;

import net.ankrya.zillion.transformation.TransformationManager;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Creative writes bypass normal menu clicks entirely. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void zillion$protectCreative(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        // Vanilla first calls ensureRunningOnSameThread. Our HEAD hook must not touch state off-thread.
        if (!player.serverLevel().getServer().isSameThread()) return;
        int slot = packet.slotNum();
        if (TransformationManager.isTemporary(packet.itemStack())
                || (TransformationManager.isActive(player) && slot >= 5 && slot <= 8)) {
            ci.cancel();
            TransformationManager.refreshInventory(player);
        }
    }
}
