package com.zillion;

import com.mojang.serialization.Codec;
import com.zillion.henshin.HenshinData;
import com.zillion.item.GazerZeroArmorItem;
import com.zillion.item.ZillionDriverItem;
import com.zillion.network.ZNetwork;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ArmorItem.Type;
import net.minecraft.world.item.ArmorMaterial.Layer;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister.DataComponents;
import net.neoforged.neoforge.registries.DeferredRegister.Items;

@Mod("zillion")
public final class Zillion {
   public static final String MODID = "zillion";
   public static final Items ITEMS = DeferredRegister.createItems("zillion");
   public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, "zillion");
   public static final DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "zillion");
   public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "zillion");
   public static final DeferredHolder<ArmorMaterial, ArmorMaterial> GAZERZERO_MATERIAL = ARMOR_MATERIALS.register(
      "gazerzero",
      () -> new ArmorMaterial(
            makeDefense(3, 6, 8, 3),
            15,
            SoundEvents.ARMOR_EQUIP_NETHERITE,
            () -> Ingredient.of(new ItemLike[]{net.minecraft.world.item.Items.NETHERITE_INGOT}),
            List.of(new Layer(id("gazerzero"))),
            3.0F,
            0.1F
         )
   );
   public static final DeferredHolder<ArmorMaterial, ArmorMaterial> DRIVER_MATERIAL = ARMOR_MATERIALS.register(
      "zillion_driver",
      () -> new ArmorMaterial(
            makeDefense(0, 0, 0, 0),
            0,
            SoundEvents.ARMOR_EQUIP_IRON,
            () -> Ingredient.of(new ItemLike[]{net.minecraft.world.item.Items.IRON_INGOT}),
            List.of(new Layer(id("zillion_driver"))),
            0.0F,
            0.0F
         )
   );
   public static final DeferredItem<GazerZeroArmorItem> GAZERZERO_HELMET = ITEMS.register(
      "gazerzero_helmet", () -> new GazerZeroArmorItem(Type.HELMET, new Properties().stacksTo(1).fireResistant())
   );
   public static final DeferredItem<GazerZeroArmorItem> GAZERZERO_CHESTPLATE = ITEMS.register(
      "gazerzero_chestplate", () -> new GazerZeroArmorItem(Type.CHESTPLATE, new Properties().stacksTo(1).fireResistant())
   );
   public static final DeferredItem<GazerZeroArmorItem> GAZERZERO_BOOTS = ITEMS.register(
      "gazerzero_boots", () -> new GazerZeroArmorItem(Type.BOOTS, new Properties().stacksTo(1).fireResistant())
   );
   public static final DeferredItem<ZillionDriverItem> ZILLION_DRIVER = ITEMS.register(
      "zillion_driver", () -> new ZillionDriverItem(new Properties().stacksTo(1).fireResistant())
   );
   public static final Supplier<DataComponentType<Boolean>> HENSHIN_BOUND = DATA_COMPONENTS.registerComponentType(
      "henshin_bound", builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL)
   );
   public static final Supplier<AttachmentType<HenshinData>> HENSHIN_DATA = ATTACHMENTS.register(
      "henshin", () -> AttachmentType.builder(HenshinData::new).serialize(HenshinData.CODEC).build()
   );

   public static ResourceLocation id(String path) {
      return ResourceLocation.fromNamespaceAndPath("zillion", path);
   }

   private static Map<Type, Integer> makeDefense(int boots, int leggings, int chest, int helmet) {
      EnumMap<Type, Integer> map = new EnumMap<>(Type.class);
      map.put(Type.BOOTS, boots);
      map.put(Type.LEGGINGS, leggings);
      map.put(Type.CHESTPLATE, chest);
      map.put(Type.HELMET, helmet);
      map.put(Type.BODY, chest);
      return map;
   }

   public Zillion(IEventBus modBus, ModContainer container) {
      ITEMS.register(modBus);
      ARMOR_MATERIALS.register(modBus);
      DATA_COMPONENTS.register(modBus);
      ATTACHMENTS.register(modBus);
      modBus.addListener(ZNetwork::register);
      modBus.addListener(Zillion::addCreative);
   }

   private static void addCreative(BuildCreativeModeTabContentsEvent event) {
      if (event.getTabKey() == CreativeModeTabs.COMBAT) {
         event.accept(ZILLION_DRIVER);
         event.accept(GAZERZERO_HELMET);
         event.accept(GAZERZERO_CHESTPLATE);
         event.accept(GAZERZERO_BOOTS);
      }
   }
}
