package com.zillion.client.anim;

import com.zillion.Zillion;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

/**
 * Thin client-side wrapper around PlayerAnimator for this mod.
 * <ul>
 *   <li>{@link #ANIMATION} – the mod's animation layer (registered in {@link #init()}).</li>
 *   <li>{@link #play(AbstractClientPlayer, ResourceLocation, boolean)} / {@link #stop(AbstractClientPlayer, int)}.</li>
 *   <li>{@link #GEO_ARMOR_BEND_OWNER_KEY} – shared key (same id other rider mods use) that records which animation
 *       layer currently "owns" GeckoLib armor bending, so several mods implementing the same bending never apply
 *       it twice to the same armor.</li>
 * </ul>
 * Animations are loaded by PlayerAnimator from {@code assets/zillion/player_animation/*.json}.
 */
@OnlyIn(Dist.CLIENT)
public final class PlayerAnimHelper {
    /** Shared owner key – must match other mods that implement GeckoLib armor bending. */
    public static final ResourceLocation GEO_ARMOR_BEND_OWNER_KEY =
            ResourceLocation.fromNamespaceAndPath("player_animator", "geo_armor_bend_owner");
    /** This mod's animation layer id. */
    public static final ResourceLocation ANIMATION = Zillion.id("animation");

    private static final float EPSILON = 0.00001F;

    private PlayerAnimHelper() {}

    /** Register the animation layer factory. Call once from client setup (mod bus). */
    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ANIMATION, 100, player -> new ModifierLayer<>());
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static ModifierLayer<IAnimation> layer(AbstractClientPlayer player) {
        IAnimation anim = PlayerAnimationAccess.getPlayerAssociatedData(player).get(ANIMATION);
        return anim instanceof ModifierLayer<?> l ? (ModifierLayer<IAnimation>) l : null;
    }

    /** Whether this mod's layer is currently playing something. */
    public static boolean isPlaying(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = layer(player);
        if (layer == null)
            return false;
        IAnimation anim = layer.getAnimation();
        return anim != null && anim.isActive();
    }

    /**
     * Play a registered player animation ({@code assets/<ns>/player_animation/<path>.json}) on this mod's layer,
     * with a short fade-in. Also claims GeckoLib armor bend ownership for the layer.
     *
     * @param override replace a running animation; if false a running animation is kept
     * @return true if the animation was started
     */
    public static boolean play(AbstractClientPlayer player, ResourceLocation animationId, boolean override) {
        ModifierLayer<IAnimation> layer = layer(player);
        if (layer == null)
            return false;
        KeyframeAnimation data = PlayerAnimationRegistry.getAnimation(animationId) instanceof KeyframeAnimation k ? k : null;
        if (data == null)
            return false;
        IAnimation current = layer.getAnimation();
        if (current != null && current.isActive() && !override)
            return false;
        AbstractFadeModifier fade = AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE);
        layer.replaceAnimationWithFade(fade, new LimbBendAnimation(new KeyframeAnimationPlayer(data)));
        PlayerAnimationAccess.getPlayerAssociatedData(player).set(GEO_ARMOR_BEND_OWNER_KEY, layer);
        return true;
    }

    /** Fade the current animation on this mod's layer out. */
    public static void stop(AbstractClientPlayer player, int fadeTicks) {
        ModifierLayer<IAnimation> layer = layer(player);
        if (layer != null && layer.isActive())
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fadeTicks, Ease.INOUTSINE), null);
    }

    /**
     * Wraps a keyframe animation so that the BEND channel of a limb also incorporates the rotation of an optional
     * "forearm" / "foreleg" sub-bone of the animation (Blockbench rigs that animate lower limbs as separate bones).
     * This is what feeds {@link GeoArmorBender}.
     */
    static final class LimbBendAnimation implements IAnimation {
        private final IAnimation delegate;

        LimbBendAnimation(IAnimation delegate) {
            this.delegate = delegate;
        }

        @Override
        public void tick() {
            this.delegate.tick();
        }

        @Override
        public boolean isActive() {
            return this.delegate.isActive();
        }

        @Override
        public void setupAnim(float tickDelta) {
            this.delegate.setupAnim(tickDelta);
        }

        @Override
        public FirstPersonMode getFirstPersonMode(float tickDelta) {
            return this.delegate.getFirstPersonMode(tickDelta);
        }

        @Override
        public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
            return this.delegate.getFirstPersonConfiguration(tickDelta);
        }

        @Override
        public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
            Vec3f transformed = this.delegate.get3DTransform(modelName, type, tickDelta, value0);
            if (type != TransformType.BEND)
                return transformed;
            String[] lowerParts = lowerPartNames(modelName);
            if (lowerParts == null)
                return transformed;
            Vec3f lower = null;
            for (String name : lowerParts) {
                Vec3f candidate = this.delegate.get3DTransform(name, TransformType.ROTATION, tickDelta, Vec3f.ZERO);
                if (lengthSquared(candidate) > EPSILON * EPSILON) {
                    lower = candidate;
                    break;
                }
            }
            return lower == null ? transformed : combineBendAndLowerRotation(transformed, lower);
        }

        @Nullable
        private static String[] lowerPartNames(String modelName) {
            return switch (modelName) {
                case "rightArm" -> new String[]{"rightForearm", "rightforearm", "right_forearm"};
                case "leftArm" -> new String[]{"leftForearm", "leftforearm", "left_forearm"};
                case "rightLeg" -> new String[]{"rightForeleg", "rightforeleg", "right_foreleg"};
                case "leftLeg" -> new String[]{"leftForeleg", "leftforeleg", "left_foreleg"};
                default -> null;
            };
        }
    }

    private static float lengthSquared(Vec3f v) {
        return v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ();
    }

    /** Combine a bend (axis, angle) with an extra lower-limb euler rotation into a single swing bend (axis, angle). */
    private static Vec3f combineBendAndLowerRotation(Vec3f bend, Vec3f lower) {
        float bendAxis = bend.getX();
        Quaternionf total = new Quaternionf()
                .rotateAxis(bend.getY(), (float) Math.cos(bendAxis), 0, (float) Math.sin(bendAxis))
                .mul(new Quaternionf().rotationZYX(lower.getZ(), lower.getY(), lower.getX()))
                .normalize();
        float twistLen = (float) Math.sqrt(total.y() * total.y() + total.w() * total.w());
        Quaternionf twist = twistLen > EPSILON
                ? new Quaternionf(0, total.y() / twistLen, 0, total.w() / twistLen)
                : new Quaternionf();
        Quaternionf swing = new Quaternionf(total).mul(new Quaternionf(twist).conjugate()).normalize();
        if (swing.w() < 0)
            swing.set(-swing.x(), -swing.y(), -swing.z(), -swing.w());
        float sinHalf = (float) Math.sqrt(swing.x() * swing.x() + swing.z() * swing.z());
        if (sinHalf < EPSILON)
            return Vec3f.ZERO;
        float axis = (float) Math.atan2(swing.z(), swing.x());
        float angle = 2F * (float) Math.atan2(sinHalf, swing.w());
        return new Vec3f(axis, angle, 0);
    }
}
