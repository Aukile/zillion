package net.ankrya.zillion;

import net.ankrya.zillion.item.ModItems;
import net.ankrya.zillion.network.TransformationNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Zillion.MODID)
public final class Zillion {
    public static final String MODID = "zillion";
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> RIDER_TAB = TABS.register("rider", () ->
            CreativeModeTab.builder().title(Component.translatable("itemGroup.zillion"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.DRIVER.get().getDefaultInstance())
                    // The three temporary pieces are deliberately not collectible creative items.
                    .displayItems((parameters, output) -> output.accept(ModItems.DRIVER.get()))
                    .build());

    public Zillion(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        TABS.register(modBus);
        modBus.addListener(TransformationNetwork::register);
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
