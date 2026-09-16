package net.ankrya.zillion.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.ankrya.zillion.network.TransformationNetwork;
import net.ankrya.zillion.transformation.TransformationTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Render-only cache. Local game-time anchors allow late tracking and differing dimension clocks. */
public final class ClientTransformationState {
    private static final Map<UUID, Entry> STATES = new HashMap<>();
    private static ClientLevel level;

    private ClientTransformationState() {}

    public static void accept(TransformationNetwork.State state) {
        ClientLevel current = currentLevel();
        if (current == null) return;
        if (state.phase() == 0) {
            STATES.remove(state.player());
            return;
        }
        int elapsed = Math.clamp(state.elapsed(), 0, TransformationTimeline.DURATION_TICKS);
        if (state.phase() == 2) elapsed = TransformationTimeline.DURATION_TICKS;
        STATES.put(state.player(), new Entry(state.phase(), state.startTime(), elapsed, state.seed(), current.getGameTime()));
    }

    public static boolean isActive(UUID player) {
        return currentLevel() != null && STATES.containsKey(player);
    }

    /** -1 when inactive; completed sessions stay at 160 rather than restarting an animation. */
    public static float ticks(UUID player, float partialTick) {
        ClientLevel current = currentLevel();
        Entry entry = STATES.get(player);
        if (current == null || entry == null) return -1;
        if (entry.phase == 2) return TransformationTimeline.DURATION_TICKS;
        long sinceSync = Math.max(0, current.getGameTime() - entry.receivedAt);
        return Math.min(TransformationTimeline.DURATION_TICKS,
                entry.elapsed + sinceSync + Math.clamp(partialTick, 0.0F, 1.0F));
    }

    public static long seed(UUID player) {
        currentLevel();
        Entry entry = STATES.get(player);
        return entry == null ? 0 : entry.seed;
    }

    public static void clear() {
        STATES.clear();
        level = null;
    }

    private static ClientLevel currentLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != level) {
            STATES.clear();
            level = current;
        }
        return current;
    }

    private record Entry(int phase, long serverStartTime, int elapsed, long seed, long receivedAt) {}
}
