package net.ankrya.zillion.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.ankrya.zillion.network.TransformationNetwork;
import net.ankrya.zillion.transformation.TransformationManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Client-only, autonomous key and cache registration; G starts and H always cancels/deforms. */
@EventBusSubscriber(modid = "zillion", value = Dist.CLIENT)
public final class TransformationKeys {
    public static final KeyMapping TRANSFORM = new KeyMapping("key.zillion.transform", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G, "key.categories.zillion");
    public static final KeyMapping DEFORM = new KeyMapping("key.zillion.deform", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H, "key.categories.zillion");

    private TransformationKeys() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean canSend = minecraft.player != null && minecraft.getConnection() != null && minecraft.screen == null;
        while (TRANSFORM.consumeClick()) {
            if (canSend) PacketDistributor.sendToServer(new TransformationNetwork.Request(true));
        }
        while (DEFORM.consumeClick()) {
            if (canSend) PacketDistributor.sendToServer(new TransformationNetwork.Request(false));
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTransformationState.clear();
    }

    @EventBusSubscriber(modid = "zillion", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(TRANSFORM);
            event.register(DEFORM);
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                TransformationNetwork.setClientReceiver(ClientTransformationState::accept);
                TransformationManager.setClientActiveLookup(player -> ClientTransformationState.isActive(player.getUUID()));
            });
        }
    }
}
