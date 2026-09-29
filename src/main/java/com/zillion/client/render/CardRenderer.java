package com.zillion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zillion.Zillion;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import org.joml.Quaternionf;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * Draws the "Sirius" card ({@code textures/sirius.png}) directly on a player - not an item. Two modes:
 * <ul>
 *   <li>{@link Mode#HAND}: pinched between the fingers of the right hand (follows the right arm incl. player animations).</li>
 *   <li>{@link Mode#MOUTH}: held by one corner in front of the mouth (follows the head).</li>
 * </ul>
 * The quad is drawn with a no-cull render type so it is visible from both sides. The card disappears automatically
 * when the player takes the Zillion Driver off.
 */
public final class CardRenderer {
    /** HAND = pre-henshin (+3 ticks, card pinched in front of the hand); MOUTH = held in the mouth;
     *  HENSHIN_HAND = taken from the mouth (+35 ticks) and held during the henshin (card in the palm). */
    public enum Mode { HAND, MOUTH, HENSHIN_HAND }

    public static final ResourceLocation TEXTURE = Zillion.id("textures/sirius.png");
    /** card size in model pixels (1/16 block) */
    // sirius.png is a 16x16 square texture (card art occupies x 2..14, y 1..16), so the quad must be square too,
    // otherwise the card is stretched. Visible card = 3 x 3.75 px.
    private static final float CARD_W = 4.0f;
    private static final float CARD_H = 4.0f;
    /** transparent margin of the texture, in model pixels (2/16 and 1/16 of the quad) */
    private static final float PAD_X = CARD_W * 2f / 16f;
    private static final float PAD_Y = CARD_H * 1f / 16f;

    private static final Int2ObjectMap<Mode> CARDS = new Int2ObjectOpenHashMap<>();

    private CardRenderer() {}

    public static void show(Player player, Mode mode) {
        if (player == null || player.isRemoved())
            return;
        CARDS.put(player.getId(), mode);
    }

    public static void hide(int entityId) {
        CARDS.remove(entityId);
    }

    public static void clearAll() {
        CARDS.clear();
    }

    public static Mode get(Entity entity) {
        return CARDS.get(entity.getId());
    }

    /** Mod-bus: attach the layer to both player renderers (default / slim). */
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof PlayerRenderer renderer)
                renderer.addLayer(new Layer(renderer));
        }
    }

    // ------------------------------------------------------------------ render layer
    public static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            Mode mode = CARDS.get(player.getId());
            if (mode == null)
                return;
            if (!player.getItemBySlot(EquipmentSlot.LEGS).is(Zillion.ZILLION_DRIVER.get())) {
                CARDS.remove(player.getId()); // driver taken off -> card stops rendering
                return;
            }
            PlayerModel<AbstractClientPlayer> model = getParentModel();
            poseStack.pushPose();
            if (mode == Mode.HAND) {
                // pre-henshin (1st key press, +3 ticks): card pinched between the fingers, sticking out in front of the hand
                model.rightArm.translateAndRotate(poseStack);
                applyForearmBend(poseStack, player); // follow the bent forearm (bendy-lib)
                poseStack.translate(-0.6f / 16f, 10.2f / 16f, -1.8f / 16f);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90f)); // lie along the forearm direction, face outwards
                poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
                poseStack.translate(0, -CARD_H * 0.35f / 16f, 0); // the "held" corner stays in the hand
            }
            else if (mode == Mode.HENSHIN_HAND) {
                // taken from the mouth (+35 ticks of the prepare) and held during the henshin: card in the palm
                model.rightArm.translateAndRotate(poseStack);
                applyForearmBend(poseStack, player); // follow the bent forearm (bendy-lib)
                // Right arm box (arm space): x -3..1, y -2..10 (hand end at y=10), z -2..2.
                // Palm side = inner face x=+1 (towards the body); outer face x=-3 is the back of the hand.
                poseStack.translate(1.1f / 16f, 10.0f / 16f, 0.0f);
                poseStack.mulPose(Axis.YP.rotationDegrees(90f)); // card plane parallel to the palm
                // visible top edge 2px inside the hand (y=8), card extends towards/beyond the fingertips
                poseStack.translate(0, (CARD_H * 0.5f - PAD_Y - 2.0f) / 16f, 0);
            }
            else {
                // follow the head. Head pivot = neck; model space: -Y up, -Z forward (face plane z=-4),
                // head centre x=0. Pivot = mouth: horizontally centred, ~2px above the chin, just in front of the face.
                model.head.translateAndRotate(poseStack);
                poseStack.translate(-1.5f / 16f, -0.8f / 16f, -4.4f / 16f); // shifted 1.5px to the player's right
                poseStack.mulPose(Axis.XP.rotationDegrees(-35f)); // rotate about X: free end lifted forward (tilted)
                poseStack.mulPose(Axis.ZP.rotationDegrees(-40f)); // slanted
                // shift so the card's upper corner (-hw, -hh) sits in the mouth, card body hangs down diagonally
                poseStack.translate((CARD_W * 0.5f - PAD_X - 0.6f) / 16f, (CARD_H * 0.5f - PAD_Y - 0.6f) / 16f, 0);
            }
            drawCard(poseStack, buffers, light);
            poseStack.popPose();
        }

        /** bendy-lib elbow joint in right-arm space (6px below the shoulder pivot, same as GeoArmorBender). */
        private static final float ELBOW_Y = 4.0f / 16f;

        /**
         * Apply the PlayerAnimator BEND of the right arm (forearm rotation around the elbow) to the pose stack, so
         * that anything drawn afterwards follows the bent lower arm / hand.
         */
        private static void applyForearmBend(PoseStack poseStack, AbstractClientPlayer player) {
            if (!(player instanceof IAnimatedPlayer animated))
                return;
            AnimationApplier animation = animated.playerAnimator_getAnimation();
            if (animation == null || !animation.isActive())
                return;
            Pair<Float, Float> bend = animation.getBend("rightArm");
            float angle = bend.getRight();
            if (Math.abs(angle) < 1.0E-4f)
                return;
            float axis = bend.getLeft();
            poseStack.translate(0, ELBOW_Y, 0);
            poseStack.mulPose(new Quaternionf().rotateAxis(angle, (float) Math.cos(axis), 0, (float) Math.sin(axis)));
            poseStack.translate(0, -ELBOW_Y, 0);
        }

        private static void drawCard(PoseStack poseStack, MultiBufferSource buffers, int light) {
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(TEXTURE)); // back-face culled: front/back quads never overlap (no mirrored double image)
            PoseStack.Pose pose = poseStack.last();
            float hw = CARD_W / 32f, hh = CARD_H / 32f;
            // front (facing -Z in local space)
            vertex(vc, pose, -hw, -hh, 0, 0, 0, 0, 0, -1, light);
            vertex(vc, pose, -hw, hh, 0, 0, 1, 0, 0, -1, light);
            vertex(vc, pose, hw, hh, 0, 1, 1, 0, 0, -1, light);
            vertex(vc, pose, hw, -hh, 0, 1, 0, 0, 0, -1, light);
            // back (mirrored so the artwork reads correctly from behind too, proper normal for lighting)
            vertex(vc, pose, hw, -hh, 0, 0, 0, 0, 0, 1, light);
            vertex(vc, pose, hw, hh, 0, 0, 1, 0, 0, 1, light);
            vertex(vc, pose, -hw, hh, 0, 1, 1, 0, 0, 1, light);
            vertex(vc, pose, -hw, -hh, 0, 1, 0, 0, 0, 1, light);
        }

        private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                                   float u, float v, float nx, float ny, float nz, int light) {
            vc.addVertex(pose, x, y, z)
              .setColor(255, 255, 255, 255)
              .setUv(u, v)
              .setOverlay(OverlayTexture.NO_OVERLAY)
              .setLight(light)
              .setNormal(pose, nx, ny, nz);
        }
    }
}
