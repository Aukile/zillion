package net.ankrya.zillion.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;

/** Physical-client-only implementation; the common item instantiates it lazily. */
public final class RiderArmorRenderProvider implements GeoRenderProvider {
    private final boolean driver;
    private RiderArmorRenderer renderer;

    public RiderArmorRenderProvider(boolean driver) {
        this.driver = driver;
    }

    @Override
    public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(T wearer, ItemStack stack,
            EquipmentSlot slot, HumanoidModel<T> original) {
        if (this.renderer == null) {
            this.renderer = new RiderArmorRenderer(this.driver);
        }
        // 4.8.2's HumanoidArmorLayer mixin calls the full prepForRender overload itself.
        return this.renderer;
    }
}
