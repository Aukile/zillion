package net.ankrya.zillion.network;

import java.util.UUID;
import java.util.function.Consumer;
import net.ankrya.zillion.transformation.TransformationManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common payloads deliberately contain no references to Minecraft client classes. */
public final class TransformationNetwork {
    private static Consumer<State> clientReceiver = state -> {};

    private TransformationNetwork() {}

    /** Installed only by the physical-client setup subscriber. */
    public static void setClientReceiver(Consumer<State> receiver) {
        clientReceiver = receiver;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        if (payload.start()) TransformationManager.start(player);
                        else TransformationManager.stop(player);
                    }
                }));
        registrar.playToClient(State.TYPE, State.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> clientReceiver.accept(payload)));
    }

    public record Request(boolean start) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("zillion", "transformation_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = new StreamCodec<>() {
            @Override public Request decode(RegistryFriendlyByteBuf buffer) { return new Request(buffer.readBoolean()); }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Request value) { buffer.writeBoolean(value.start()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** phase: 0 inactive, 1 transforming, 2 complete. Elapsed avoids cross-dimension clock assumptions. */
    public record State(UUID player, int phase, long startTime, int elapsed, long seed) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("zillion", "transformation_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf buffer) {
                return new State(buffer.readUUID(), buffer.readVarInt(), buffer.readLong(), buffer.readVarInt(), buffer.readLong());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, State value) {
                buffer.writeUUID(value.player());
                buffer.writeVarInt(value.phase());
                buffer.writeLong(value.startTime());
                buffer.writeVarInt(value.elapsed());
                buffer.writeLong(value.seed());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
