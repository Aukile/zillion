package net.ankrya.zillion.client.model;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** Separate UV layouts: free-flight drone = 32x32, exact armor attachment = 128x128. */
public final class WingmanModel extends GeoModel<WingmanVisual> {
    private final boolean attachment;

    public WingmanModel(boolean attachment) {
        this.attachment = attachment;
    }

    @Override
    public ResourceLocation getModelResource(WingmanVisual visual) {
        return RiderArmorModel.id(this.attachment ? "geo/gazerzero.geo.json" : "geo/wingman.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(WingmanVisual visual) {
        return this.attachment ? RiderArmorModel.ARMOR_TEXTURE : RiderArmorModel.id("textures/item/entity/wingman.png");
    }

    @Override
    public ResourceLocation getAnimationResource(WingmanVisual visual) {
        return RiderArmorModel.id(this.attachment ? "animations/gazerzero.animation.json" : "animations/wingman.animation.json");
    }
}
