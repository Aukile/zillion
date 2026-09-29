package com.zillion.henshin;

import com.zillion.Zillion;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server side manager for delayed (scheduled) player sounds.
 * <ul>
 *   <li>{@link #schedule} - reserve a sound to be played at the player's position after N ticks.</li>
 *   <li>{@link #cancel} / {@link #cancelAll} - cancel a reservation; a cancelled sound is never played.</li>
 *   <li>{@link #stop} / {@link #stopAll} - cancel the reservation AND stop the sound if it is already playing
 *       (for the player and everyone tracking them).</li>
 * </ul>
 * Henshin specific "gazerzero_login":
 * <ul>
 *   <li>2nd henshin key press ({@link HenshinServer#tryStart}): a not-yet-played reservation is cancelled.</li>
 *   <li>stopped (if playing) when henshin.ogg starts ({@link #playHenshin}), when the Zillion Driver is taken off,
 *       and on death; reservations are dropped on logout.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Zillion.MODID)
public final class DelayedSoundManager {
    private DelayedSoundManager() {}

    private static final class Pending {
        final Supplier<SoundEvent> sound;
        final SoundSource source;
        final float volume, pitch;
        int remaining;

        Pending(Supplier<SoundEvent> sound, SoundSource source, float volume, float pitch, int delay) {
            this.sound = sound;
            this.source = source;
            this.volume = volume;
            this.pitch = pitch;
            this.remaining = delay;
        }

        ResourceLocation id() {
            return sound.get().getLocation();
        }
    }

    private static final Map<UUID, List<Pending>> PENDING = new HashMap<>();

    // ------------------------------------------------------------------ reservation API

    /** Reserve {@code sound} to play at the player after {@code delayTicks} ticks (PLAYERS source, volume/pitch 1). */
    public static void schedule(ServerPlayer player, Supplier<SoundEvent> sound, int delayTicks) {
        schedule(player, sound, delayTicks, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /** Reserve a sound. A delay of 0 or less plays it on the next server tick. */
    public static void schedule(ServerPlayer player, Supplier<SoundEvent> sound, int delayTicks,
                                SoundSource source, float volume, float pitch) {
        PENDING.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
               .add(new Pending(sound, source, volume, pitch, Math.max(0, delayTicks)));
    }

    /** Cancel all reservations of {@code sound} for this player. @return true if something was cancelled */
    public static boolean cancel(ServerPlayer player, Supplier<SoundEvent> sound) {
        List<Pending> list = PENDING.get(player.getUUID());
        if (list == null)
            return false;
        ResourceLocation id = sound.get().getLocation();
        boolean removed = list.removeIf(p -> p.id().equals(id));
        if (list.isEmpty())
            PENDING.remove(player.getUUID());
        return removed;
    }

    /** Cancel every reservation of this player. */
    public static void cancelAll(ServerPlayer player) {
        PENDING.remove(player.getUUID());
    }

    /** @return true if {@code sound} is currently reserved for this player */
    public static boolean isScheduled(ServerPlayer player, Supplier<SoundEvent> sound) {
        List<Pending> list = PENDING.get(player.getUUID());
        if (list == null)
            return false;
        ResourceLocation id = sound.get().getLocation();
        for (Pending p : list) {
            if (p.id().equals(id))
                return true;
        }
        return false;
    }

    /** Cancel the reservation and stop the sound if it is already playing (player + all trackers). */
    public static void stop(ServerPlayer player, Supplier<SoundEvent> sound, SoundSource source) {
        cancel(player, sound);
        sendStop(player, new ClientboundStopSoundPacket(sound.get().getLocation(), source));
    }

    public static void stop(ServerPlayer player, Supplier<SoundEvent> sound) {
        stop(player, sound, SoundSource.PLAYERS);
    }

    /** Cancel all reservations of the player and stop the sounds that were reserved through this manager. */
    public static void stopAll(ServerPlayer player) {
        List<Pending> list = PENDING.remove(player.getUUID());
        if (list == null)
            return;
        for (Pending p : list)
            sendStop(player, new ClientboundStopSoundPacket(p.id(), p.source));
    }

    private static void sendStop(ServerPlayer player, ClientboundStopSoundPacket packet) {
        if (player.level() instanceof ServerLevel level)
            level.getChunkSource().broadcastAndSend(player, packet);
        else if (player.connection != null)
            player.connection.send(packet);
    }

    /**
     * Play a sound attached to the entity: clients get a ClientboundSoundEntityPacket and play an
     * EntityBoundSoundInstance, so the sound source moves with the player (instead of a fixed block position).
     */
    public static void playOnEntity(ServerPlayer player, SoundEvent sound, SoundSource source, float volume, float pitch) {
        player.level().playSound(null, player, sound, source, volume, pitch);
    }

    // ------------------------------------------------------------------ henshin helpers

    /** Reserve the "gazerzero_login" sound (pre-henshin pose). Replaces an older reservation of it. */
    public static void scheduleLogin(ServerPlayer player) {
        cancel(player, Zillion.LOGIN_SOUND);
        schedule(player, Zillion.LOGIN_SOUND, HenshinServer.LOGIN_SOUND_DELAY);
    }

    /** Only cancel the "gazerzero_login" reservation (a login sound that is already playing keeps playing). */
    public static void cancelLogin(ServerPlayer player) {
        cancel(player, Zillion.LOGIN_SOUND);
    }

    /**
     * Play henshin.ogg on the player (follows the player) and stop "gazerzero_login" at the same moment.
     * Called by {@link HenshinServer#startTransformation}.
     */
    public static void playHenshin(ServerPlayer player) {
        stopLogin(player);
        playOnEntity(player, Zillion.HENSHIN_SOUND.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /** Cancel the "gazerzero_login" reservation and stop it if it is already playing. */
    public static void stopLogin(ServerPlayer player) {
        stop(player, Zillion.LOGIN_SOUND);
    }

    // ------------------------------------------------------------------ ticking / events

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty())
            return;
        MinecraftServer server = event.getServer();
        List<Runnable> toPlay = new ArrayList<>();
        Iterator<Map.Entry<UUID, List<Pending>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Pending>> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) { // offline -> drop
                it.remove();
                continue;
            }
            Iterator<Pending> pit = e.getValue().iterator();
            while (pit.hasNext()) {
                Pending p = pit.next();
                if (--p.remaining > 0)
                    continue;
                pit.remove();
                if (!player.isRemoved() && player.isAlive())
                    toPlay.add(() -> playOnEntity(player, p.sound.get(), p.source, p.volume, p.pitch));
            }
            if (e.getValue().isEmpty())
                it.remove();
        }
        toPlay.forEach(Runnable::run); // played outside the iteration (a sound callback may schedule again)
    }

    /** Zillion Driver taken off -> stop / cancel the login sound. */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.LEGS || !(event.getEntity() instanceof ServerPlayer player))
            return;
        if (event.getFrom().is(Zillion.ZILLION_DRIVER.get()) && !event.getTo().is(Zillion.ZILLION_DRIVER.get()))
            stopLogin(player);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            stopLogin(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            cancelAll(player);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
