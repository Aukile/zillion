package com.zillion.item;

import com.zillion.Zillion;
import com.zillion.client.render.GazerZeroArmorRenderer;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ArmorItem.Type;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar;
import software.bernie.geckolib.util.GeckoLibUtil;

public class GazerZeroArmorItem extends ArmorItem implements GeoItem {
   private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

   public GazerZeroArmorItem(Type type, Properties properties) {
      super(Zillion.GAZERZERO_MATERIAL, type, properties);
   }

   public static boolean isBound(ItemStack stack) {
      return stack.getItem() instanceof GazerZeroArmorItem && (Boolean)stack.getOrDefault(Zillion.HENSHIN_BOUND.get(), false);
   }

   public boolean isFoil(ItemStack stack) {
      return false;
   }

   public boolean isEnchantable(ItemStack stack) {
      return false;
   }

   public boolean onDroppedByPlayer(ItemStack item, Player player) {
      return !isBound(item);
   }

   public boolean canEquip(ItemStack stack, EquipmentSlot armorType, LivingEntity entity) {
      return isBound(stack) && super.canEquip(stack, armorType, entity);
   }

   public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
      tooltip.add(Component.translatable("tooltip.zillion.gazerzero").withStyle(ChatFormatting.DARK_GREEN));
   }

   public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
      consumer.accept(
         new GeoRenderProvider() {
            private GazerZeroArmorRenderer renderer;

            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(
               @Nullable T livingEntity, ItemStack itemStack, @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original
            ) {
               if (this.renderer == null) {
                  this.renderer = new GazerZeroArmorRenderer();
               }

               return this.renderer;
            }
         }
      );
   }

   public void registerControllers(ControllerRegistrar controllers) {
   }

   public AnimatableInstanceCache getAnimatableInstanceCache() {
      return this.cache;
   }
}
