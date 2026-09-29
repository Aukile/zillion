package com.zillion.client.anim;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.event.GeoRenderEvent;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies PlayerAnimator limb bending to GeckoLib armor.
 * <p>
 * Listens to {@link GeoRenderEvent.Armor.Pre}/{@link GeoRenderEvent.Armor.Post}: before the armor renders, the
 * animation's BEND value for each limb is either
 * <ul>
 *   <li>applied as a rotation to a dedicated lower-limb bone (e.g. {@code armorRightForearm}) if the model has one, or</li>
 *   <li>baked into the limb's mesh: every cube of the limb bone is sliced around the joint height and the lower part
 *       is rotated with a smooth blend, so a single-cube limb bends like the vanilla (bendy-lib) player limb.</li>
 * </ul>
 * All changes are reverted in the Post event, so the shared baked model is never left modified.
 * <p>
 * Ownership: several mods may implement this same feature. The player's associated data slot
 * {@link PlayerAnimHelper#GEO_ARMOR_BEND_OWNER_KEY} stores the layer that currently owns bending; only the mod whose
 * active layer is stored there performs the deformation, so the armor is never bent twice.
 */
@OnlyIn(Dist.CLIENT)
public final class GeoArmorBender {
    private static final Map<GeoArmorRenderer<?>, List<SavedBonePose>> SAVED_POSES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<GeoArmorRenderer<?>, List<SavedBoneMesh>> SAVED_MESHES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<GeoBone, List<GeoCube>> ACTIVE_MESHES = Collections.synchronizedMap(new IdentityHashMap<>());
    /** Joint sits this far below the limb pivot (vanilla limb = 12 px, joint at the middle). */
    private static final float JOINT_OFFSET = 6F / 16F;
    private static final float BLEND_RADIUS = 2F / 16F;
    private static final float EPSILON = 0.00001F;

    private static final String[] RIGHT_FOREARM = {"rightforearm", "right_forearm", "rightForearm", "armorRightForearm", "rightLowerArm", "right_lower_arm"};
    private static final String[] LEFT_FOREARM = {"leftforearm", "left_forearm", "leftForearm", "armorLeftForearm", "leftLowerArm", "left_lower_arm"};
    private static final String[] RIGHT_FORELEG = {"rightforeleg", "right_foreleg", "rightForeleg", "armorRightForeleg", "rightLowerLeg", "right_lower_leg"};
    private static final String[] LEFT_FORELEG = {"leftforeleg", "left_foreleg", "leftForeleg", "armorLeftForeleg", "leftLowerLeg", "left_lower_leg"};
    private static final String[] RIGHT_ARM = {"armorRightArm", "rightArm", "right_arm", "rightSleeve"};
    private static final String[] LEFT_ARM = {"armorLeftArm", "leftArm", "left_arm", "leftSleeve"};
    private static final String[] RIGHT_LEG = {"armorRightLeg", "rightLeg", "right_leg", "armorRightBoot", "rightBoot", "right_boot"};
    private static final String[] LEFT_LEG = {"armorLeftLeg", "leftLeg", "left_leg", "armorLeftBoot", "leftBoot", "left_boot"};

    private GeoArmorBender() {}

    /** Register the render event listeners. Call once from client setup. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(GeoArmorBender::onPre);
        NeoForge.EVENT_BUS.addListener(GeoArmorBender::onPost);
    }

    private static void onPre(GeoRenderEvent.Armor.Pre event) {
        GeoArmorRenderer<?> renderer = event.getRenderer();
        restore(renderer);
        if (!(event.getEntity() instanceof AbstractClientPlayer player) || !(player instanceof IAnimatedPlayer animated))
            return;
        AnimationApplier animation = animated.playerAnimator_getAnimation();
        if (!animation.isActive() || !ownsBend(player))
            return;
        applyAll(renderer, event.getModel(), animation, true);
    }

    private static void onPost(GeoRenderEvent.Armor.Post event) {
        restore(event.getRenderer());
    }

    /**
     * Manual variant for custom render paths that bypass the GeckoLib events (e.g. first-person arms).
     * Pair with {@link #restoreManually(GeoArmorRenderer)}.
     */
    public static void applyManually(GeoArmorRenderer<?> renderer, BakedGeoModel model, net.minecraft.world.entity.Entity entity) {
        restore(renderer);
        if (!(entity instanceof AbstractClientPlayer player) || !(player instanceof IAnimatedPlayer animated))
            return;
        AnimationApplier animation = animated.playerAnimator_getAnimation();
        if (!animation.isActive() || !ownsBend(player))
            return;
        applyAll(renderer, model, animation, false);
    }

    public static void restoreManually(GeoArmorRenderer<?> renderer) {
        restore(renderer);
    }

    /** Revert every leftover deformation (call on logout / dimension change). */
    public static void clearAll() {
        synchronized (ACTIVE_MESHES) {
            ACTIVE_MESHES.forEach((bone, cubes) -> {
                bone.getCubes().clear();
                bone.getCubes().addAll(cubes);
            });
            ACTIVE_MESHES.clear();
        }
        SAVED_POSES.clear();
        SAVED_MESHES.clear();
    }

    // ------------------------------------------------------------------------------------------------ ownership

    /**
     * True if this mod's animation layer is active and either already owns bending or can claim it
     * (nobody owns it / the owner layer is no longer active).
     */
    private static boolean ownsBend(AbstractClientPlayer player) {
        PlayerAnimationAccess.PlayerAssociatedAnimationData data = PlayerAnimationAccess.getPlayerAssociatedData(player);
        IAnimation own = data.get(PlayerAnimHelper.ANIMATION);
        if (own == null || !own.isActive())
            return false;
        IAnimation owner = data.get(PlayerAnimHelper.GEO_ARMOR_BEND_OWNER_KEY);
        if (owner == own)
            return true;
        if (owner == null || !owner.isActive()) {
            data.set(PlayerAnimHelper.GEO_ARMOR_BEND_OWNER_KEY, own);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ apply

    private static void applyAll(GeoArmorRenderer<?> renderer, BakedGeoModel model, AnimationApplier animation, boolean includeLegs) {
        List<SavedBonePose> poses = new ArrayList<>(4);
        List<SavedBoneMesh> meshes = new ArrayList<>(4);
        Set<GeoBone> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (hasCubes(model, "armorRightArm", "armorLeftArm")) {
            if (!bendByBone(model, animation, "rightArm", poses, visited, RIGHT_FOREARM))
                bendByMesh(renderer, model, animation, "rightArm", meshes, RIGHT_ARM);
            if (!bendByBone(model, animation, "leftArm", poses, visited, LEFT_FOREARM))
                bendByMesh(renderer, model, animation, "leftArm", meshes, LEFT_ARM);
        }
        if (includeLegs && hasCubes(model, "armorLeftLeg", "armorLeftBoot", "armorRightLeg", "armorRightBoot")) {
            if (!bendByBone(model, animation, "rightLeg", poses, visited, RIGHT_FORELEG))
                bendByMesh(renderer, model, animation, "rightLeg", meshes, RIGHT_LEG);
            if (!bendByBone(model, animation, "leftLeg", poses, visited, LEFT_FORELEG))
                bendByMesh(renderer, model, animation, "leftLeg", meshes, LEFT_LEG);
        }
        if (!poses.isEmpty())
            SAVED_POSES.put(renderer, poses);
        if (!meshes.isEmpty())
            SAVED_MESHES.put(renderer, meshes);
    }

    private static boolean hasCubes(BakedGeoModel model, String... bones) {
        for (String name : bones) {
            GeoBone bone = model.getBone(name).orElse(null);
            if (bone != null && hasVisibleGeometry(bone))
                return true;
        }
        return false;
    }

    /** Rotate a dedicated lower-limb bone. Returns false if the model has no such bone. */
    private static boolean bendByBone(BakedGeoModel model, AnimationApplier animation, String part,
                                      List<SavedBonePose> poses, Set<GeoBone> visited, String... boneNames) {
        List<GeoBone> bones = findBones(model, boneNames);
        bones.removeIf(b -> !hasVisibleGeometry(b));
        if (bones.isEmpty())
            return false;
        Pair<Float, Float> bend = animation.getBend(part);
        if (Math.abs(bend.getRight()) < EPSILON)
            return true;
        Vector3f euler = bendEuler(bend);
        for (GeoBone bone : bones) {
            if (visited.add(bone)) {
                poses.add(new SavedBonePose(bone));
                bone.updateRotation(bone.getRotX() - euler.x, bone.getRotY() - euler.y, bone.getRotZ() + euler.z);
            }
        }
        return true;
    }

    /** Bake the bend into the limb mesh (slice + rotate the lower half). */
    private static void bendByMesh(GeoArmorRenderer<?> renderer, BakedGeoModel model, AnimationApplier animation,
                                   String part, List<SavedBoneMesh> meshes, String... boneNames) {
        Pair<Float, Float> bend = animation.getBend(part);
        if (Math.abs(bend.getRight()) < EPSILON)
            return;
        Vector3f e = bendEuler(bend);
        Quaternionf rotation = new Quaternionf().rotationZYX(e.z, -e.y, -e.x);
        List<GeoBone> bones = rendererLimbBones(renderer, part);
        boolean mapped = !bones.isEmpty();
        if (!mapped)
            bones = findBones(model, boneNames);
        bones.removeIf(b -> !hasVisibleGeometry(b));
        if (bones.isEmpty() && !mapped)
            bones = guessLimbBones(model, part);
        for (GeoBone bone : bones) {
            float jointY = bone.getPivotY() / 16F - JOINT_OFFSET;
            Vector3f joint = new Vector3f(bone.getPivotX() / 16F, jointY, bone.getPivotZ() / 16F);
            deformTree(bone, joint, rotation, new Matrix4f(), meshes, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    /** Bend (axis angle around a horizontal axis) -> euler xyz used by both code paths. */
    private static Vector3f bendEuler(Pair<Float, Float> bend) {
        float axis = bend.getLeft();
        Quaternionf q = new Quaternionf().rotateAxis(bend.getRight(), (float) Math.cos(axis), 0, (float) Math.sin(axis));
        float x = (float) Math.atan2(2F * (q.w() * q.x() + q.y() * q.z()), 1F - 2F * (q.x() * q.x() + q.y() * q.y()));
        float y = (float) Math.asin(Mth.clamp(2F * (q.w() * q.y() - q.z() * q.x()), -1F, 1F));
        float z = (float) Math.atan2(2F * (q.w() * q.z() + q.x() * q.y()), 1F - 2F * (q.y() * q.y() + q.z() * q.z()));
        return new Vector3f(x, y, z);
    }

    // ------------------------------------------------------------------------------------------------ bone lookup

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<GeoBone> rendererLimbBones(GeoArmorRenderer<?> renderer, String part) {
        List<GeoBone> bones = new ArrayList<>(2);
        GeoArmorRenderer raw = renderer;
        GeoModel geoModel = raw.getGeoModel();
        switch (part) {
            case "rightArm" -> addUnique(bones, raw.getRightArmBone(geoModel));
            case "leftArm" -> addUnique(bones, raw.getLeftArmBone(geoModel));
            case "rightLeg" -> {
                addUnique(bones, raw.getRightLegBone(geoModel));
                addUnique(bones, raw.getRightBootBone(geoModel));
            }
            case "leftLeg" -> {
                addUnique(bones, raw.getLeftLegBone(geoModel));
                addUnique(bones, raw.getLeftBootBone(geoModel));
            }
            default -> { }
        }
        return bones;
    }

    private static void addUnique(List<GeoBone> bones, GeoBone bone) {
        if (bone != null && !bones.contains(bone))
            bones.add(bone);
    }

    private static List<GeoBone> findBones(BakedGeoModel model, String... names) {
        Set<String> wanted = new java.util.HashSet<>();
        for (String n : names)
            wanted.add(normalize(n));
        List<GeoBone> out = new ArrayList<>();
        for (GeoBone root : model.topLevelBones())
            collectNamed(root, wanted, out);
        return out;
    }

    private static void collectNamed(GeoBone bone, Set<String> wanted, List<GeoBone> out) {
        if (wanted.contains(normalize(bone.getName())))
            out.add(bone);
        for (GeoBone child : bone.getChildBones())
            collectNamed(child, wanted, out);
    }

    private static List<GeoBone> guessLimbBones(BakedGeoModel model, String part) {
        String side = part.startsWith("right") ? "right" : "left";
        String limb = part.endsWith("Arm") ? "arm" : "leg";
        List<GeoBone> all = new ArrayList<>();
        for (GeoBone root : model.topLevelBones())
            collectRenderable(root, all);
        List<GeoBone> named = new ArrayList<>();
        for (GeoBone b : all) {
            String n = normalize(b.getName());
            if (n.contains(side) && n.contains(limb) && !n.contains("fore") && !n.contains("lower"))
                named.add(b);
        }
        if (!named.isEmpty())
            return named;
        GeoBone best = null;
        float bestScore = Float.MAX_VALUE;
        float ex = side.equals("right") ? 5F : -5F, ey = limb.equals("arm") ? 22F : 12F;
        for (GeoBone b : all) {
            if ((side.equals("right") && b.getPivotX() <= 0) || (side.equals("left") && b.getPivotX() >= 0))
                continue;
            float score = Math.abs(b.getPivotX() - ex) + Math.abs(b.getPivotY() - ey) * 1.5F;
            if (score < bestScore) {
                best = b;
                bestScore = score;
            }
        }
        List<GeoBone> out = new ArrayList<>(1);
        if (best != null && bestScore < 10F)
            out.add(best);
        return out;
    }

    private static void collectRenderable(GeoBone bone, List<GeoBone> out) {
        if (!bone.getCubes().isEmpty() && isVisible(bone))
            out.add(bone);
        for (GeoBone child : bone.getChildBones())
            collectRenderable(child, out);
    }

    private static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    private static boolean isVisible(GeoBone bone) {
        if (bone.isHidden())
            return false;
        for (GeoBone p = bone.getParent(); p != null; p = p.getParent())
            if (p.isHidingChildren())
                return false;
        return true;
    }

    private static boolean hasVisibleGeometry(GeoBone bone) {
        if (!bone.getCubes().isEmpty() && isVisible(bone))
            return true;
        for (GeoBone child : bone.getChildBones())
            if (hasVisibleGeometry(child))
                return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ mesh deform

    private static void deformTree(GeoBone bone, Vector3f joint, Quaternionf rotation, Matrix4f boneTransform,
                                   List<SavedBoneMesh> meshes, Set<GeoBone> visited) {
        if (!visited.add(bone))
            return;
        List<GeoCube> original;
        synchronized (ACTIVE_MESHES) {
            List<GeoCube> stale = ACTIVE_MESHES.remove(bone);
            if (stale != null) {
                bone.getCubes().clear();
                bone.getCubes().addAll(stale);
            }
            original = List.copyOf(bone.getCubes());
        }
        if (!original.isEmpty() && isVisible(bone)) {
            List<GeoCube> deformed = new ArrayList<>(original.size());
            boolean changed = false;
            for (GeoCube cube : original) {
                GeoCube result = deformCube(cube, joint, rotation, boneTransform);
                deformed.add(result);
                changed |= result != cube;
            }
            if (changed) {
                synchronized (ACTIVE_MESHES) {
                    ACTIVE_MESHES.put(bone, original);
                }
                meshes.add(new SavedBoneMesh(bone, original));
                bone.getCubes().clear();
                bone.getCubes().addAll(deformed);
            }
        }
        for (GeoBone child : bone.getChildBones())
            deformTree(child, joint, rotation, new Matrix4f(boneTransform).mul(localBoneTransform(child)), meshes, visited);
    }

    private static GeoCube deformCube(GeoCube cube, Vector3f joint, Quaternionf rotation, Matrix4f boneTransform) {
        Matrix4f cubeTransform = new Matrix4f(boneTransform).mul(cubeTransform(cube));
        Matrix4f invCube = new Matrix4f(cubeTransform).invert();
        Matrix3f normalTransform = new Matrix3f(cubeTransform);
        Matrix3f invNormal = new Matrix3f(cubeTransform).invert();
        List<GeoQuad> out = new ArrayList<>();
        boolean changed = false;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null)
                continue;
            List<BendVertex> polygon = new ArrayList<>(quad.vertices().length);
            for (GeoVertex v : quad.vertices()) {
                Vector3f p = cubeTransform.transformPosition(new Vector3f(v.position()));
                polygon.add(new BendVertex(p, v.texU(), v.texV()));
                changed |= weight(p.y(), joint.y()) > EPSILON;
            }
            if (!changed && out.isEmpty()) {
                out.add(quad);
                continue;
            }
            Vector3f normal = normalTransform.transform(new Vector3f(quad.normal())).normalize();
            for (List<BendVertex> slice : slice(polygon, joint.y()))
                emit(slice, normal, quad.direction(), joint, rotation, invCube, invNormal, out);
        }
        if (!changed)
            return cube;
        return new GeoCube(out.toArray(GeoQuad[]::new), cube.pivot(), cube.rotation(), cube.size(), cube.inflate(), cube.mirror());
    }

    private static Matrix4f cubeTransform(GeoCube cube) {
        float px = (float) cube.pivot().x() / 16F, py = (float) cube.pivot().y() / 16F, pz = (float) cube.pivot().z() / 16F;
        return new Matrix4f().translate(px, py, pz)
                .rotateZ((float) cube.rotation().z()).rotateY((float) cube.rotation().y()).rotateX((float) cube.rotation().x())
                .translate(-px, -py, -pz);
    }

    private static Matrix4f localBoneTransform(GeoBone bone) {
        float px = bone.getPivotX() / 16F, py = bone.getPivotY() / 16F, pz = bone.getPivotZ() / 16F;
        return new Matrix4f()
                .translate(-bone.getPosX() / 16F, bone.getPosY() / 16F, bone.getPosZ() / 16F)
                .translate(px, py, pz)
                .rotateZ(bone.getRotZ()).rotateY(bone.getRotY()).rotateX(bone.getRotX())
                .scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ())
                .translate(-px, -py, -pz);
    }

    private static List<List<BendVertex>> slice(List<BendVertex> polygon, float jointY) {
        float[] cuts = {jointY - BLEND_RADIUS, jointY - BLEND_RADIUS * 0.5F, jointY, jointY + BLEND_RADIUS * 0.5F, jointY + BLEND_RADIUS};
        List<List<BendVertex>> slices = new ArrayList<>(6);
        for (int i = 0; i <= cuts.length; i++) {
            List<BendVertex> s = polygon;
            if (i > 0)
                s = clip(s, cuts[i - 1], true);
            if (i < cuts.length)
                s = clip(s, cuts[i], false);
            s = dedupe(s);
            if (s.size() >= 3)
                slices.add(s);
        }
        return slices;
    }

    private static List<BendVertex> clip(List<BendVertex> in, float boundary, boolean keepAbove) {
        List<BendVertex> out = new ArrayList<>();
        if (in.isEmpty())
            return out;
        BendVertex prev = in.get(in.size() - 1);
        boolean prevIn = keepAbove ? prev.position().y() >= boundary - 1e-6F : prev.position().y() <= boundary + 1e-6F;
        for (BendVertex cur : in) {
            boolean curIn = keepAbove ? cur.position().y() >= boundary - 1e-6F : cur.position().y() <= boundary + 1e-6F;
            if (curIn != prevIn) {
                float den = cur.position().y() - prev.position().y();
                float t = Math.abs(den) < 1e-6F ? 0F : (boundary - prev.position().y()) / den;
                out.add(new BendVertex(new Vector3f(prev.position()).lerp(cur.position(), t),
                        prev.u() + (cur.u() - prev.u()) * t, prev.v() + (cur.v() - prev.v()) * t));
            }
            if (curIn)
                out.add(cur);
            prev = cur;
            prevIn = curIn;
        }
        return out;
    }

    private static List<BendVertex> dedupe(List<BendVertex> in) {
        List<BendVertex> out = new ArrayList<>(in.size());
        for (BendVertex v : in)
            if (out.isEmpty() || out.get(out.size() - 1).position().distanceSquared(v.position()) > 1e-10F)
                out.add(v);
        if (out.size() > 1 && out.get(0).position().distanceSquared(out.get(out.size() - 1).position()) <= 1e-10F)
            out.remove(out.size() - 1);
        return out;
    }

    private static void emit(List<BendVertex> polygon, Vector3f sourceNormal, Direction direction, Vector3f joint,
                             Quaternionf rotation, Matrix4f invCube, Matrix3f invNormal, List<GeoQuad> out) {
        List<BendVertex> bent = new ArrayList<>(polygon.size());
        float avg = 0;
        for (BendVertex v : polygon) {
            float w = weight(v.position().y(), joint.y());
            avg += w;
            Vector3f p = new Vector3f(v.position()).sub(joint).rotate(new Quaternionf().slerp(rotation, w)).add(joint);
            invCube.transformPosition(p);
            bent.add(new BendVertex(p, v.u(), v.v()));
        }
        avg /= polygon.size();
        Vector3f normal = new Vector3f(sourceNormal).rotate(new Quaternionf().slerp(rotation, avg));
        invNormal.transform(normal).normalize();
        if (bent.size() == 4) {
            out.add(quad(bent.get(0), bent.get(1), bent.get(2), bent.get(3), normal, direction));
        } else {
            for (int i = 1; i + 1 < bent.size(); i++)
                out.add(quad(bent.get(0), bent.get(i), bent.get(i + 1), bent.get(i + 1), normal, direction));
        }
    }

    private static GeoQuad quad(BendVertex a, BendVertex b, BendVertex c, BendVertex d, Vector3f normal, Direction dir) {
        return new GeoQuad(new GeoVertex[]{
                new GeoVertex(a.position(), a.u(), a.v()), new GeoVertex(b.position(), b.u(), b.v()),
                new GeoVertex(c.position(), c.u(), c.v()), new GeoVertex(d.position(), d.u(), d.v())
        }, new Vector3f(normal), dir);
    }

    /** 0 above the blend zone, 1 below it, smoothstep in between. */
    private static float weight(float y, float jointY) {
        float t = Mth.clamp((jointY + BLEND_RADIUS - y) / (BLEND_RADIUS * 2F), 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    // ------------------------------------------------------------------------------------------------ restore

    private static void restore(GeoArmorRenderer<?> renderer) {
        List<SavedBonePose> poses = SAVED_POSES.remove(renderer);
        if (poses != null)
            for (SavedBonePose p : poses)
                p.restore();
        List<SavedBoneMesh> meshes = SAVED_MESHES.remove(renderer);
        if (meshes != null)
            for (SavedBoneMesh m : meshes)
                m.restore();
    }

    private record BendVertex(Vector3f position, float u, float v) {}

    private record SavedBonePose(GeoBone bone, float x, float y, float z) {
        SavedBonePose(GeoBone bone) {
            this(bone, bone.getRotX(), bone.getRotY(), bone.getRotZ());
        }

        void restore() {
            this.bone.updateRotation(this.x, this.y, this.z);
        }
    }

    private record SavedBoneMesh(GeoBone bone, List<GeoCube> cubes) {
        SavedBoneMesh(GeoBone bone, List<GeoCube> cubes) {
            this.bone = bone;
            this.cubes = List.copyOf(cubes);
        }

        void restore() {
            synchronized (ACTIVE_MESHES) {
                ACTIVE_MESHES.remove(this.bone);
            }
            this.bone.getCubes().clear();
            this.bone.getCubes().addAll(this.cubes);
        }
    }
}
