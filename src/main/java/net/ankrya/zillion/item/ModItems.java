package net.ankrya.zillion.item;

import net.minecraft.world.item.ArmorItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The driver is ordinary, wearable leg armor until the server starts a transformation. */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("zillion");
    public static final DeferredItem<RiderArmorItem> DRIVER = ITEMS.register("zillion_driver",
            () -> new RiderArmorItem(ArmorItem.Type.LEGGINGS, true));
    public static final DeferredItem<RiderArmorItem> HELMET = ITEMS.register("gazerzero_helmet",
            () -> new RiderArmorItem(ArmorItem.Type.HELMET, false));
    public static final DeferredItem<RiderArmorItem> CHESTPLATE = ITEMS.register("gazerzero_chestplate",
            () -> new RiderArmorItem(ArmorItem.Type.CHESTPLATE, false));
    public static final DeferredItem<RiderArmorItem> BOOTS = ITEMS.register("gazerzero_boots",
            () -> new RiderArmorItem(ArmorItem.Type.BOOTS, false));

    private ModItems() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
