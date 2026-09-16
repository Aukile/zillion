package net.ankrya.zillion.mixin;

import net.ankrya.zillion.transformation.TransformationManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla armor right-click swaps do not pass through container slot clicks. */
@Mixin({ArmorItem.class, ElytraItem.class})
public abstract class ArmorItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void zillion$preventSwap(Level level, Player player, InteractionHand hand,
                                     CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (TransformationManager.isActive(player))
            cir.setReturnValue(InteractionResultHolder.fail(player.getItemInHand(hand)));
    }
}
