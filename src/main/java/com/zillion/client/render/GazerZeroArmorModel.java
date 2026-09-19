package com.zillion.client.render;

import com.zillion.Zillion;
import com.zillion.item.GazerZeroArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class GazerZeroArmorModel extends GeoModel<GazerZeroArmorItem> {
   private static final ResourceLocation MODEL = Zillion.id("geo/gazerzero.geo.json");
   private static final ResourceLocation TEXTURE = Zillion.id("textures/item/armor/gazerzero.png");
   private static final ResourceLocation ANIM = Zillion.id("animations/gazerzero.animation.json");

   public ResourceLocation getModelResource(GazerZeroArmorItem animatable) {
      return MODEL;
   }

   public ResourceLocation getTextureResource(GazerZeroArmorItem animatable) {
      return TEXTURE;
   }

   public ResourceLocation getAnimationResource(GazerZeroArmorItem animatable) {
      return ANIM;
   }
}
