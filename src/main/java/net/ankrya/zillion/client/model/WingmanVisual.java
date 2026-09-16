package net.ankrya.zillion.client.model;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Render-only object, never a world entity or a registered item. */
public final class WingmanVisual implements GeoAnimatable {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private float time;

    public void setTime(float time) { this.time = time; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return this.cache; }

    @Override
    public double getTick(Object relatedObject) { return this.time; }
}
