package net.ankrya.zillion.item;

import net.ankrya.zillion.client.render.RiderArmorRenderProvider;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/** The driver occupies LEGS; the FEET item contains both complete leg meshes. */
public class RiderArmorItem extends ArmorItem implements GeoItem {
    private final boolean driver;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public RiderArmorItem(ArmorItem.Type type, boolean driver) {
        super(ArmorMaterials.NETHERITE, type, new Properties().stacksTo(1).fireResistant());
        this.driver = driver;
    }

    public boolean isDriver() {
        return this.driver;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The supplied animation assets are static. Pose comes from the wearer each render.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        // GeckoLib invokes this lazily on the physical client only. No client object in fields.
        consumer.accept(new RiderArmorRenderProvider(this.driver));
    }
}
