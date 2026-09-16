package net.ankrya.zillion;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

@EventBusSubscriber(modid = Zillion.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class Config {
    private Config() {}

    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
    }
}
