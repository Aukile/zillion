package net.ankrya.zillion.client;

import net.ankrya.zillion.Zillion;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/** Hides only the vanilla player model; Geo armor layers remain renderable during transformation. */
@EventBusSubscriber(modid = Zillion.MODID, value = Dist.CLIENT)
public final class TransformationPlayerVisibility {
    private TransformationPlayerVisibility() {}

    @SubscribeEvent
    public static void beforePlayer(RenderPlayerEvent.Pre event) {
        if (ClientTransformationState.isActive(event.getEntity().getUUID())) {
            float ticks = ClientTransformationState.ticks(event.getEntity().getUUID(), 0);
            // Keep the player visible during the sigil and drone summon. Hide only
            // once the armor scan actually begins, so the suit never covers a blank body.
            if (ticks >= net.ankrya.zillion.client.fx.FxMath.ARMOR_START) {
                event.getRenderer().getModel().setAllVisible(false);
            }
        }
    }

    @SubscribeEvent
    public static void afterPlayer(RenderPlayerEvent.Post event) {
        event.getRenderer().getModel().setAllVisible(true);
    }
}
