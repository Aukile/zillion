package net.ankrya.zillion.mixin;

import net.ankrya.zillion.transformation.TransformationManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers inventory, containers, shift transfer, hotbar swaps, throws, pickup-all and drag distribution. */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow protected abstract void resetQuickCraft();

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void zillion$protectArmor(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (!TransformationManager.isActive(player)) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        boolean blocked = slot != null && TransformationManager.isLockedSlot(player, slot);
        // Shift-click can automatically fill a presently empty protected armor slot.
        if (type == ClickType.QUICK_MOVE && slot != null && !slot.getItem().isEmpty()) {
            EquipmentSlot equipment = player.getEquipmentSlotForItem(slot.getItem());
            blocked |= equipment == EquipmentSlot.HEAD || equipment == EquipmentSlot.CHEST
                    || equipment == EquipmentSlot.LEGS || equipment == EquipmentSlot.FEET;
        }
        // A drag may have begun before the transformation packet arrived. Reset its saved slot set.
        // Cancel armor drags rather than permitting their final packet to bypass the direct-slot guard.
        if (type == ClickType.QUICK_CRAFT) {
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                EquipmentSlot equipment = player.getEquipmentSlotForItem(carried);
                blocked |= equipment == EquipmentSlot.HEAD || equipment == EquipmentSlot.CHEST
                    || equipment == EquipmentSlot.LEGS || equipment == EquipmentSlot.FEET;
            }
        }
        // Double-click collection can otherwise pull matching belt copies out of equipment slots.
        if (type == ClickType.PICKUP_ALL) {
            ItemStack carried = menu.getCarried();
            blocked |= !carried.isEmpty() && (TransformationManager.isTemporary(carried)
                    || carried.is(net.ankrya.zillion.item.ModItems.DRIVER.get()));
        }
        if (blocked) {
            resetQuickCraft();
            ci.cancel();
            if (player instanceof ServerPlayer serverPlayer) TransformationManager.refreshInventory(serverPlayer);
        }
    }
}
