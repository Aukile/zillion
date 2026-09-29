package com.zillion.client;

import com.zillion.Zillion;
import com.zillion.client.anim.PlayerAnimHelper;
import com.zillion.client.render.CardRenderer;
import com.zillion.util.TickScheduler;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Client side card / player animation timeline of the henshin.
 * <ul>
 *   <li>1st key press (prepare): henshin_front; +{@value #HAND_TICK} card in the hand (palm),
 *       +{@value #MOUTH_TICK} card in the mouth, +{@value #BACK_TO_HAND_TICK} card taken from the mouth into the hand.</li>
 *   <li>2nd key press (henshin): henshin animation; the card stays in the hand the whole time
 *       (if the key is pressed before the card was taken out of the mouth, it goes to the hand immediately).</li>
 * </ul>
 */
public final class CardSequence {
    private CardSequence() {}

    public static final int HAND_TICK = 3;
    public static final int MOUTH_TICK = 18;
    public static final int BACK_TO_HAND_TICK = 35;

    public static void prepare(AbstractClientPlayer player) {
        int id = player.getId();
        TickScheduler.cancelClient(id);
        CardRenderer.hide(id);
        PlayerAnimHelper.play(player, Zillion.id("henshin_front"), true);
        TickScheduler.client(id, HAND_TICK, () -> CardRenderer.show(player, CardRenderer.Mode.HAND));
        TickScheduler.client(id, MOUTH_TICK, () -> CardRenderer.show(player, CardRenderer.Mode.MOUTH));
        TickScheduler.client(id, BACK_TO_HAND_TICK, () -> CardRenderer.show(player, CardRenderer.Mode.HENSHIN_HAND));
    }

    public static void henshin(AbstractClientPlayer player) {
        int id = player.getId();
        TickScheduler.cancelClient(id);
        PlayerAnimHelper.play(player, Zillion.id("henshin"), true);
        CardRenderer.show(player, CardRenderer.Mode.HENSHIN_HAND);
    }
}
