package com.zillion.henshin;

import com.zillion.Zillion;
import com.zillion.item.GazerZeroArmorItem;
import com.zillion.network.ZNetwork;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side transformation logic.
 * <ul>
 *   <li>Key press + Zillion Driver worn + not transformed  -> sequence starts.</li>
 *   <li>At {@link HenshinTiming#ARMOR_TICK} the bound Gazer Zero armor is put on (HEAD / CHEST / FEET).</li>
 *   <li>Bound armor can't be taken off or swapped. If one piece is removed anyway (commands, /clear, creative...),
 *       all other bound pieces vanish and the player is un-transformed.</li>
 *   <li>Bound armor never drops (death drops are purged, {@code onDroppedByPlayer} is denied).</li>
 *   <li>While transformed the Driver is locked as well.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Zillion.MODID)
public final class HenshinServer {
    private HenshinServer() {}

    public static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.FEET};

    public static HenshinData data(Player player) {
        return player.getData(Zillion.HENSHIN_DATA);
    }

    public static boolean wearingDriver(LivingEntity entity) {
        return entity.getItemBySlot(EquipmentSlot.LEGS).is(Zillion.ZILLION_DRIVER.get());
    }

    public static boolean isLocked(Player player) {
        return data(player).armorShouldBeLocked();
    }

    /** Called when the client's henshin key is pressed. */
    public static void tryStart(ServerPlayer player) {
        HenshinData data = data(player);
        if (data.active || data.transformed)
            return;
        if (!wearingDriver(player))
            return;
        // all three armor slots must be free so the armor can materialise
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!player.getItemBySlot(slot).isEmpty())
                return;
        }
        data.active = true;
        data.tick = 0;
        data.transformed = false;
        data.seed = player.getRandom().nextLong();
        lockDriver(player, true);
        broadcast(player, ZNetwork.HenshinStatePayload.START, data.seed);
        player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.6F);
    }

    private static void broadcast(ServerPlayer player, int state, long seed) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ZNetwork.HenshinStatePayload(player.getId(), state, seed));
    }

    private static ItemStack bound(ServerPlayer player, ItemStack stack) {
        stack.set(Zillion.HENSHIN_BOUND.get(), true);
        setLocked(player, stack, true);
        return stack;
    }

    /**
     * Vanilla refuses to take armor out of an armor slot (or quick-swap it) when the stack carries an enchantment with
     * the {@code prevent_armor_change} effect - binding curse. We (ab)use exactly that, hidden from the tooltip.
     */
    public static void setLocked(Player player, ItemStack stack, boolean locked) {
        if (stack.isEmpty())
            return;
        ItemEnchantments current = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current.withTooltip(false));
        mutable.removeIf(h -> h.is(Enchantments.BINDING_CURSE));
        if (locked)
            mutable.set(player.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.BINDING_CURSE), 1);
        ItemEnchantments result = mutable.toImmutable();
        if (result.isEmpty())
            stack.remove(DataComponents.ENCHANTMENTS);
        else
            stack.set(DataComponents.ENCHANTMENTS, result);
    }

    private static void lockDriver(ServerPlayer player, boolean locked) {
        ItemStack driver = player.getItemBySlot(EquipmentSlot.LEGS);
        if (driver.is(Zillion.ZILLION_DRIVER.get())) {
            setLocked(player, driver, locked);
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static void equipArmor(ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.HEAD, bound(player, new ItemStack(Zillion.GAZERZERO_HELMET.get())));
        player.setItemSlot(EquipmentSlot.CHEST, bound(player, new ItemStack(Zillion.GAZERZERO_CHESTPLATE.get())));
        player.setItemSlot(EquipmentSlot.FEET, bound(player, new ItemStack(Zillion.GAZERZERO_BOOTS.get())));
        player.inventoryMenu.broadcastChanges();
    }

    /** Remove every bound piece and reset the state. */
    public static void untransform(ServerPlayer player, boolean notify) {
        boolean removed = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (GazerZeroArmorItem.isBound(player.getItemBySlot(slot))) {
                player.setItemSlot(slot, ItemStack.EMPTY);
                removed = true;
            }
        }
        // bound pieces that got moved into the inventory by some means
        purgeBound(player.getInventory().items);
        purgeBound(player.getInventory().offhand);
        HenshinData data = data(player);
        boolean wasSomething = data.active || data.transformed;
        data.reset();
        lockDriver(player, false);
        // drivers that ended up in the inventory keep no lock either
        for (ItemStack s : player.getInventory().items)
            if (s.is(Zillion.ZILLION_DRIVER.get()))
                setLocked(player, s, false);
        if (notify && (removed || wasSomething))
            broadcast(player, ZNetwork.HenshinStatePayload.RESET, 0);
        player.inventoryMenu.broadcastChanges();
    }

    private static void purgeBound(NonNullList<ItemStack> list) {
        for (int i = 0; i < list.size(); i++) {
            if (GazerZeroArmorItem.isBound(list.get(i)))
                list.set(i, ItemStack.EMPTY);
        }
    }

    // ---------------------------------------------------------------- ticking
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        HenshinData data = data(player);

        if (data.active) {
            data.tick++;
            if (!wearingDriver(player)) {
                untransform(player, true);
                return;
            }
            if (data.tick == HenshinTiming.ARMOR_TICK) {
                equipArmor(player);
                broadcast(player, ZNetwork.HenshinStatePayload.ARMOR, data.seed);
                player.level().playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.0F, 1.4F);
            }
            if (data.tick >= HenshinTiming.END_TICK) {
                data.active = false;
                data.transformed = true;
                broadcast(player, ZNetwork.HenshinStatePayload.FINISH, data.seed);
            }
        }

        // integrity check: transformed => all three bound pieces present, otherwise everything disappears
        if (data.armorShouldBeLocked() && data.tick > HenshinTiming.ARMOR_TICK) {
            boolean complete = true;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                if (!GazerZeroArmorItem.isBound(player.getItemBySlot(slot))) {
                    complete = false;
                    break;
                }
            }
            if (!complete || !wearingDriver(player))
                untransform(player, true);
        }
        else if (!data.active && !data.transformed) {
            // stray bound armor (e.g. picked up via commands) is never allowed to exist
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                if (GazerZeroArmorItem.isBound(player.getItemBySlot(slot))) {
                    untransform(player, true);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- equipment change safety net
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (event.getSlot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR)
            return;
        HenshinData data = data(player);
        if (!data.armorShouldBeLocked() || data.tick <= HenshinTiming.ARMOR_TICK)
            return;
        // a bound piece was replaced/removed -> the whole suit vanishes (never lands anywhere)
        if (GazerZeroArmorItem.isBound(event.getFrom()) && !GazerZeroArmorItem.isBound(event.getTo())) {
            untransform(player, true);
        }
    }

    // ---------------------------------------------------------------- no drops, ever
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        event.getDrops().removeIf(e -> GazerZeroArmorItem.isBound(e.getItem()));
        if (event.getEntity() instanceof ServerPlayer player)
            data(player).reset();
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            event.getEntity().getData(Zillion.HENSHIN_DATA).reset();
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            untransform(player, true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        HenshinData data = data(player);
        if (data.active) // sequence interrupted by logout -> just cancel it
            untransform(player, true);
        else if (data.transformed)
            broadcast(player, ZNetwork.HenshinStatePayload.FINISH, data.seed);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer target) || !(event.getEntity() instanceof ServerPlayer watcher))
            return;
        HenshinData data = data(target);
        if (data.transformed)
            PacketDistributor.sendToPlayer(watcher, new ZNetwork.HenshinStatePayload(target.getId(), ZNetwork.HenshinStatePayload.FINISH, data.seed));
    }

    /** Item entities carrying bound armor are removed on spawn (belt & braces). */
    @SubscribeEvent
    public static void onItemSpawn(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && GazerZeroArmorItem.isBound(item.getItem()))
            event.setCanceled(true);
    }
}
