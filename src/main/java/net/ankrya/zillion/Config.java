package net.ankrya.zillion;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only presentation settings. Gameplay timing remains server authoritative. */
@EventBusSubscriber(modid = Zillion.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.IntValue QUALITY = BUILDER
            .comment("Effect quality: 0 low, 1 balanced, 2 high. All transformation stages are retained.")
            .defineInRange("quality", 2, 0, 2);
    private static final ModConfigSpec.IntValue CURVES = BUILDER
            .comment("Maximum energy ribbons per transforming player.")
            .defineInRange("vortexCurves", 28, 8, 64);
    private static final ModConfigSpec.IntValue DISTANCE = BUILDER
            .comment("Maximum transformation effect distance in blocks.")
            .defineInRange("effectDistance", 48, 8, 128);
    private static final ModConfigSpec.BooleanValue BLOOM = BUILDER
            .comment("Isolated effect bloom. Disable if an external shader pack conflicts with custom framebuffers.")
            .define("bloomEnabled", true);
    private static final ModConfigSpec.DoubleValue STRENGTH = BUILDER
            .defineInRange("bloomStrength", 0.7, 0.0, 2.0);
    private static final ModConfigSpec.DoubleValue FIRST_PERSON = BUILDER
            .comment("Brightness multiplier for your own effects in first person.")
            .defineInRange("firstPersonIntensity", 0.32, 0.0, 1.0);
    public static final ModConfigSpec SPEC = BUILDER.build();
    public static int quality = 2;
    public static int vortexCurves = 28;
    public static int effectDistance = 48;
    public static boolean bloomEnabled = true;
    public static double bloomStrength = 0.7;
    public static double firstPersonIntensity = 0.32;

    private Config() {}

    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        quality = QUALITY.get();
        vortexCurves = CURVES.get();
        effectDistance = DISTANCE.get();
        bloomEnabled = BLOOM.get();
        bloomStrength = STRENGTH.get();
        firstPersonIntensity = FIRST_PERSON.get();
    }
}
