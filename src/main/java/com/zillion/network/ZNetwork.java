package com.zillion.network;

import com.zillion.Zillion;
import com.zillion.client.ClientHenshinHandler;
import com.zillion.henshin.HenshinServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ZNetwork {
   private ZNetwork() {
   }

   public static void register(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("1");
      registrar.playToServer(ZNetwork.HenshinKeyPayload.TYPE, ZNetwork.HenshinKeyPayload.CODEC, (payload, context) -> {
         if (context.player() instanceof ServerPlayer sp) {
            HenshinServer.tryStart(sp);
         }
      });
      registrar.playToClient(ZNetwork.HenshinStatePayload.TYPE, ZNetwork.HenshinStatePayload.CODEC, (payload, context) -> ClientHenshinHandler.handle(payload));
   }

   public static record HenshinKeyPayload() implements CustomPacketPayload {
      public static final Type<ZNetwork.HenshinKeyPayload> TYPE = new Type(Zillion.id("henshin_key"));
      public static final StreamCodec<RegistryFriendlyByteBuf, ZNetwork.HenshinKeyPayload> CODEC = StreamCodec.unit(new ZNetwork.HenshinKeyPayload());

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   public static record HenshinStatePayload(int entityId, int state, long seed) implements CustomPacketPayload {
      public static final Type<ZNetwork.HenshinStatePayload> TYPE = new Type(Zillion.id("henshin_state"));
      public static final StreamCodec<RegistryFriendlyByteBuf, ZNetwork.HenshinStatePayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.VAR_INT,
         ZNetwork.HenshinStatePayload::entityId,
         ByteBufCodecs.VAR_INT,
         ZNetwork.HenshinStatePayload::state,
         ByteBufCodecs.VAR_LONG,
         ZNetwork.HenshinStatePayload::seed,
         ZNetwork.HenshinStatePayload::new
      );
      public static final int START = 0;
      public static final int ARMOR = 1;
      public static final int FINISH = 2;
      public static final int RESET = 3;

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }
}
