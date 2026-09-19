package com.zillion.client;

import com.mojang.blaze3d.platform.InputConstants.Type;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.zillion.Zillion;
import com.zillion.client.fx.HenshinRenderer;
import com.zillion.client.render.ZRenderTypes;
import com.zillion.network.ZNetwork;
import java.io.IOException;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(
   modid = "zillion",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class ZillionClient {
   public static final String KEY_CATEGORY = "key.categories.zillion";
   public static final KeyMapping HENSHIN_KEY = new KeyMapping("key.zillion.henshin", KeyConflictContext.IN_GAME, Type.KEYSYM, 72, "key.categories.zillion");

   private ZillionClient() {
   }

   @SubscribeEvent
   public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
      event.register(HENSHIN_KEY);
      NeoForge.EVENT_BUS.addListener(ZillionClient::onClientTick);
      NeoForge.EVENT_BUS.register(HenshinRenderer.class);
      NeoForge.EVENT_BUS.register(ClientHenshinHandler.class);
   }

   @SubscribeEvent
   public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
      event.registerShader(
         new ShaderInstance(event.getResourceProvider(), Zillion.id("rendertype_zillion_hologram"), DefaultVertexFormat.NEW_ENTITY),
         s -> ZRenderTypes.hologramShader = s
      );
      event.registerShader(
         new ShaderInstance(event.getResourceProvider(), Zillion.id("rendertype_zillion_energy"), DefaultVertexFormat.POSITION_TEX_COLOR),
         s -> ZRenderTypes.energyShader = s
      );
      event.registerShader(
         new ShaderInstance(event.getResourceProvider(), Zillion.id("rendertype_zillion_circle"), DefaultVertexFormat.POSITION_TEX_COLOR),
         s -> ZRenderTypes.circleShader = s
      );
      event.registerShader(
         new ShaderInstance(event.getResourceProvider(), Zillion.id("rendertype_zillion_glowtex"), DefaultVertexFormat.POSITION_TEX_COLOR),
         s -> ZRenderTypes.glowTexShader = s
      );
   }

   private static void onClientTick(Post event) {
      Minecraft mc = Minecraft.getInstance();

      while (HENSHIN_KEY.consumeClick()) {
         if (mc.player != null && mc.level != null) {
            PacketDistributor.sendToServer(new ZNetwork.HenshinKeyPayload(), new CustomPacketPayload[0]);
         }
      }

      ClientHenshinHandler.tick();
   }
}
