package com.zillion.client.render;

import com.zillion.item.ZillionDriverItem;
import net.minecraft.world.entity.EquipmentSlot;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

public class ZillionDriverRenderer extends GeoArmorRenderer<ZillionDriverItem> {
   public ZillionDriverRenderer() {
      super(new ZillionDriverModel());
   }

   protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
      super.applyBoneVisibilityBySlot(currentSlot);
      if (currentSlot == EquipmentSlot.LEGS) {
         this.setBoneVisible(this.body, true);
      }
   }
}
