package com.zillion.henshin;

/**
 * The whole transformation timeline, in ticks (20 ticks = 1 s). Single source of truth for server and client.
 */
public final class HenshinTiming {
    private HenshinTiming() {}

    // emblem (pentagon ring image): grows behind the back, rises above the head while flipping flat,
    // then sinks from the head to the feet and vanishes
    public static final float EMBLEM_START = 0;
    public static final float EMBLEM_GROW_END = 12;
    public static final float EMBLEM_RISE_END = 26;
    public static final float EMBLEM_DESCEND_END = 58;
    public static final float EMBLEM_TOP_Y = 2.35f;
    public static final float EMBLEM_BOTTOM_Y = -0.05f;
    // ground ring expanding from the feet
    public static final float GROUND_RING = 10;
    // drones appear when the sinking emblem passes the waist (see EMBLEM descent curve, y ~ 0.78)
    public static final float DRONE_SPAWN = 45;
    public static final float DRONE_SPAWN_STAGGER = 2;
    public static final float DRONE_FLASH = 14;      // white spawn glow -> green
    public static final float DRONE_COUNT = 5;
    // whirlwind of lines + inner airflow builds up, envelops the player, then contracts
    public static final float WIND_START = 84;
    public static final float WIND_FULL = 126;
    public static final float CONTRACT_START = 128;
    public static final float CONTRACT_END = 142;
    // armor is equipped by the server at this tick (green tech-filter shader from here on)
    public static final int ARMOR_TICK = 140;
    public static final float ARMOR_FADE_IN = 22;
    public static final float HOLO_END = 184;
    // docking: all drones reach their hover spot in front of their slot at the same time,
    // push out a little (anticipation), then slam back into the armor together
    public static final float DOCK_START = 150;
    public static final float DOCK_APPROACH = 22;
    public static final float DOCK_HOLD = 3;
    public static final float DOCK_ANTICIPATE = 5;
    public static final float DOCK_SLAM = 4;
    public static final float DOCK_END = DOCK_START + DOCK_APPROACH + DOCK_HOLD + DOCK_ANTICIPATE + DOCK_SLAM; // 184
    public static final float DOCK_HOVER_DIST = 0.30f;    // blocks in front of the slot
    public static final float DOCK_ANTICIPATE_DIST = 0.05f;
    // finale: expanding chest rings, sliding line patterns, glowing lines
    public static final float FINALE_START = 186;
    public static final float FINALE_END = 250;
    // energy scarf flutters, then the real scarf model appears
    public static final float SCARF_FX_START = 190;
    public static final float SCARF_APPEAR = 224;
    // server marks the player as transformed
    public static final int END_TICK = 254;
    // one extra flash of the glowing lines after everything finished
    public static final float AFTER_FLASH_START = END_TICK + 8;
    public static final float AFTER_FLASH_END = END_TICK + 30;
    // client keeps the instance alive a bit longer for fade-outs
    public static final float CLIENT_END = END_TICK + 40;
}
