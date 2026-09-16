package net.ankrya.zillion.client.model;

import net.ankrya.zillion.item.RiderArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** Uses the original assets, including the driver's separate root detail bone. */
public final class RiderArmorModel extends GeoModel<RiderArmorItem> {
    public static final ResourceLocation ARMOR_TEXTURE = id("textures/item/armor/gazerzero.png");
    private final ResourceLocation geometry;
    private final ResourceLocation texture;
    private final ResourceLocation animation;
    private final ResourceLocation emissive;

    public RiderArmorModel(boolean driver) {
        String name = driver ? "zillion_driver" : "gazerzero";
        this.geometry = id("geo/" + name + ".geo.json");
        this.texture = id("textures/item/armor/" + name + ".png");
        this.animation = id("animations/" + name + ".animation.json");
        this.emissive = id("textures/item/armor/" + name + "_red_emissive.png");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("zillion", path);
    }

    @Override
    public ResourceLocation getModelResource(RiderArmorItem item) {
        return this.geometry;
    }

    @Override
    public ResourceLocation getTextureResource(RiderArmorItem item) {
        return this.texture;
    }

    @Override
    public ResourceLocation getAnimationResource(RiderArmorItem item) {
        return this.animation;
    }

    public ResourceLocation emissiveTexture() {
        return this.emissive;
    }
}
