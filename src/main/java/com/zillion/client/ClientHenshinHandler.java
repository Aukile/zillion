package com.zillion.client;

import com.zillion.Zillion;
import com.zillion.client.fx.HenshinInstance;
import com.zillion.henshin.HenshinData;
import com.zillion.item.GazerZeroArmorItem;
import com.zillion.network.ZNetwork;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import org.jetbrains.annotations.Nullable;

public final class ClientHenshinHandler {
   private static final Int2ObjectMap<HenshinInstance> INSTANCES = new Int2ObjectOpenHashMap();

   private ClientHenshinHandler() {
   }

   public static void handle(ZNetwork.HenshinStatePayload payload) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level != null) {
         Entity entity = mc.level.getEntity(payload.entityId());
         switch (payload.state()) {
            case 0:
               if (entity instanceof LivingEntity living) {
                  INSTANCES.put(payload.entityId(), new HenshinInstance(living, payload.seed()));
               }
               break;
            case 1:
               HenshinInstance instx = (HenshinInstance)INSTANCES.get(payload.entityId());
               if (instx != null) {
                  instx.onArmorEquipped();
               }
               break;
            case 2:
               HenshinInstance inst = (HenshinInstance)INSTANCES.get(payload.entityId());
               if (inst != null) {
                  inst.onFinished();
               }
               break;
            case 3:
               INSTANCES.remove(payload.entityId());
         }
      }
   }

   public static void tick() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null) {
         INSTANCES.clear();
      } else {
         Iterator<HenshinInstance> it = INSTANCES.values().iterator();

         while (it.hasNext()) {
            HenshinInstance inst = it.next();
            inst.tick();
            if (inst.isDone() || inst.entity.isRemoved() || inst.entity.level() != mc.level) {
               it.remove();
            }
         }
      }
   }

   @Nullable
   public static HenshinInstance get(Entity entity) {
      return (HenshinInstance)INSTANCES.get(entity.getId());
   }

   public static boolean shouldHidePlayerModel(LivingEntity entity) {
      return wearingGazerZero(entity);
   }

   public static boolean wearingGazerZero(LivingEntity entity) {
      return entity.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof GazerZeroArmorItem
         || entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof GazerZeroArmorItem
         || entity.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof GazerZeroArmorItem;
   }

   public static boolean isLocked(Player player) {
      HenshinData data = (HenshinData)player.getData(Zillion.HENSHIN_DATA);
      if (data.armorShouldBeLocked()) {
         return true;
      } else {
         for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.FEET}) {
            if (GazerZeroArmorItem.isBound(player.getItemBySlot(slot))) {
               return true;
            }
         }

         return false;
      }
   }

   @SubscribeEvent
   public static void onLogout(LoggingOut event) {
      INSTANCES.clear();
   }
}
