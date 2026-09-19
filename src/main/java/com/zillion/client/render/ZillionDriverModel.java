package com.zillion.client.render;

import com.zillion.Zillion;
import com.zillion.item.ZillionDriverItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ZillionDriverModel extends GeoModel<ZillionDriverItem> {
   private static final ResourceLocation MODEL = Zillion.id("geo/zillion_driver.geo.json");
   private static final ResourceLocation TEXTURE = Zillion.id("textures/item/armor/zillion_driver.png");
   private static final ResourceLocation ANIM = Zillion.id("animations/zillion_driver.animation.json");

   public ResourceLocation getModelResource(ZillionDriverItem animatable) {
      return MODEL;
   }

   public ResourceLocation getTextureResource(ZillionDriverItem animatable) {
      return TEXTURE;
   }

   public ResourceLocation getAnimationResource(ZillionDriverItem animatable) {
      return ANIM;
   }
}
