package net.ankrya.zillion.transformation;

import java.util.UUID;
import java.util.function.Predicate;
import net.ankrya.zillion.item.ModItems;
import net.ankrya.zillion.network.TransformationNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Main-thread-only authority and durable escrow. Automatically registered on the game event bus.
 * Logout, death, dimension transfer and cloning end the session rather than carrying a half-finished
 * transaction into another player instance. Originals and pending returns survive those transitions.
 */
@EventBusSubscriber(modid = "zillion")
public final class TransformationManager {
    private static final String DATA = "zillion_transformation";
    private static final String MARKER = "zillion_session";
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.FEET};
    private static Predicate<Player> clientActive = player -> false;

    private TransformationManager() {}

    /** Client installs a delegate without making the common class link to client-only classes. */
    public static void setClientActiveLookup(Predicate<Player> lookup) { clientActive = lookup; }

    public static boolean isActive(Player player) {
        return player.level().isClientSide ? clientActive.test(player) : data(player).getInt("phase") != 0;
    }

    private static CompoundTag data(Player player) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(DATA, Tag.TAG_COMPOUND)) persistent.put(DATA, new CompoundTag());
        return persistent.getCompound(DATA);
    }

    /** Transient item types are never legitimate inventory items, even if a command strips their tag. */
    public static boolean isTemporary(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(ModItems.HELMET.get()) || stack.is(ModItems.CHESTPLATE.get()) || stack.is(ModItems.BOOTS.get())) return true;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().contains(MARKER);
    }

    public static boolean isLockedSlot(Player player, Slot slot) {
        int index = slot.getContainerSlot();
        return isActive(player) && slot.container == player.getInventory() && index >= 36 && index <= 39;
    }

    public static void start(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || isActive(player)) return;
        CompoundTag state = data(player);
        if (!state.getList("escrow", Tag.TAG_COMPOUND).isEmpty()) stop(player);
        long now = player.serverLevel().getGameTime();
        if (now < state.getLong("nextStart")) return;
        purgeEscaped(player);
        retryPending(player);
        if (!state.getList("pending", Tag.TAG_COMPOUND).isEmpty()) return;
        if (!player.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.DRIVER.get())) return;

        // Commit escrow before removing any originals. All mutations run in this one server task.
        ListTag escrow = new ListTag();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack original = player.getItemBySlot(slot);
            if (!original.isEmpty()) escrow.add(saved(player, slot, original));
        }
        state.put("escrow", escrow);
        state.putUUID("session", UUID.randomUUID());
        state.putLong("seed", player.getRandom().nextLong());
        state.putLong("start", now);
        state.putLong("lastTick", now);
        state.putInt("elapsed", 0);
        state.putInt("phase", 1);
        state.putBoolean("armored", false);
        for (EquipmentSlot slot : ARMOR) player.setItemSlot(slot, ItemStack.EMPTY);
        synchronize(player);
        refreshInventory(player);
    }

    /** Idempotent: escrow is detached before restoration, and overflow stays in persistent pending NBT. */
    public static void stop(ServerPlayer player) {
        CompoundTag state = data(player);
        boolean wasActive = state.getInt("phase") != 0;
        state.putInt("phase", 0);
        state.putBoolean("armored", false);
        state.remove("session");
        if (wasActive) state.putLong("nextStart", player.serverLevel().getGameTime() + 10);
        purgeEscaped(player);
        ListTag escrow = state.getList("escrow", Tag.TAG_COMPOUND);
        state.remove("escrow");
        ListTag pending = state.getList("pending", Tag.TAG_COMPOUND);
        pending.addAll(escrow);
        state.put("pending", pending);
        retryPending(player);
        if (wasActive) {
            synchronize(player);
            refreshInventory(player);
        }
    }

    private static CompoundTag saved(ServerPlayer player, EquipmentSlot slot, ItemStack stack) {
        CompoundTag entry = new CompoundTag();
        entry.putString("slot", slot.getName());
        entry.put("stack", stack.save(player.registryAccess()));
        return entry;
    }

    private static EquipmentSlot returnSlot(CompoundTag entry) {
        for (EquipmentSlot slot : ARMOR) if (slot.getName().equals(entry.getString("slot"))) return slot;
        return EquipmentSlot.HEAD;
    }

    private static void retryPending(ServerPlayer player) {
        CompoundTag state = data(player);
        ListTag pending = state.getList("pending", Tag.TAG_COMPOUND);
        if (pending.isEmpty()) return;
        state.remove("pending");
        ListTag remaining = new ListTag();
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag entry = pending.getCompound(i);
            ItemStack original = ItemStack.parseOptional(player.registryAccess(), entry.getCompound("stack"));
            if (original.isEmpty() || isTemporary(original)) continue;
            EquipmentSlot slot = returnSlot(entry);
            if (!isActive(player) && player.getItemBySlot(slot).isEmpty()) {
                player.setItemSlot(slot, original);
            } else {
                // Avoid Inventory.add: creative players can have its full-inventory branch consume
                // the remainder. This explicit transfer never deletes an uninserted original.
                returnToInventory(player, original);
                if (!original.isEmpty()) remaining.add(saved(player, slot, original));
            }
        }
        if (!remaining.isEmpty()) state.put("pending", remaining);
    }

    private static void returnToInventory(ServerPlayer player, ItemStack remainder) {
        // First merge identical components, then use empty main-inventory slots. Armor/offhand slots
        // are not overflow storage. Keep the remainder for the next retry even in creative mode.
        for (int pass = 0; pass < 2 && !remainder.isEmpty(); pass++) {
            for (int i = 0; i < 36 && !remainder.isEmpty(); i++) {
                ItemStack current = player.getInventory().getItem(i);
                if (pass == 0) {
                    if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, remainder)) continue;
                    int amount = EscrowTransfer.movableCount(remainder.getCount(), current.getCount(),
                            Math.min(current.getMaxStackSize(), player.getInventory().getMaxStackSize()));
                    if (amount > 0) {
                        current.grow(amount);
                        remainder.shrink(amount);
                    }
                } else if (current.isEmpty()) {
                    int amount = EscrowTransfer.movableCount(remainder.getCount(), 0,
                            Math.min(remainder.getMaxStackSize(), player.getInventory().getMaxStackSize()));
                    player.getInventory().setItem(i, remainder.split(amount));
                }
            }
        }
        player.getInventory().setChanged();
    }

    private static ItemStack temporary(ServerPlayer player, ItemStack stack) {
        CompoundTag mark = new CompoundTag();
        mark.putUUID(MARKER, data(player).getUUID("session"));
        mark.putUUID("zillion_owner", player.getUUID());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        return stack;
    }

    private static void equip(ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.HEAD, temporary(player, new ItemStack(ModItems.HELMET.get())));
        player.setItemSlot(EquipmentSlot.CHEST, temporary(player, new ItemStack(ModItems.CHESTPLATE.get())));
        player.setItemSlot(EquipmentSlot.FEET, temporary(player, new ItemStack(ModItems.BOOTS.get())));
        data(player).putBoolean("armored", true);
        refreshInventory(player);
    }

    private static boolean validPiece(ServerPlayer player, EquipmentSlot slot, ItemStack stack) {
        if (stack.getCount() != 1) return false;
        boolean rightItem = switch (slot) {
            case HEAD -> stack.is(ModItems.HELMET.get());
            case CHEST -> stack.is(ModItems.CHESTPLATE.get());
            case FEET -> stack.is(ModItems.BOOTS.get());
            default -> false;
        };
        if (!rightItem) return false;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        CompoundTag tag = custom.copyTag();
        CompoundTag state = data(player);
        return tag.hasUUID(MARKER) && tag.hasUUID("zillion_owner") && state.hasUUID("session")
                && tag.getUUID(MARKER).equals(state.getUUID("session"))
                && tag.getUUID("zillion_owner").equals(player.getUUID());
    }

    private static boolean intact(ServerPlayer player) {
        if (!data(player).hasUUID("session")) return false;
        if (!player.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.DRIVER.get())) return false;
        boolean armored = data(player).getBoolean("armored");
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getItemBySlot(slot);
            if (armored ? !validPiece(player, slot, stack) : !stack.isEmpty()) return false;
        }
        return true;
    }

    /** Remove copied/escaped pieces without touching legitimate escrow or a freely worn driver. */
    private static void purgeEscaped(ServerPlayer player) {
        boolean active = isActive(player);
        boolean armored = data(player).getBoolean("armored");
        boolean changed = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isTemporary(stack)) continue;
            EquipmentSlot slot = switch (i) {
                case 36 -> EquipmentSlot.FEET;
                case 38 -> EquipmentSlot.CHEST;
                case 39 -> EquipmentSlot.HEAD;
                default -> EquipmentSlot.MAINHAND;
            };
            if (!(active && armored && validPiece(player, slot, stack))) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        // Also clear escaped pieces in an open external inventory before they can be transferred.
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container != player.getInventory() && isTemporary(slot.getItem())) {
                slot.set(ItemStack.EMPTY);
                changed = true;
            }
        }
        if (isTemporary(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        if (player.containerMenu != player.inventoryMenu && isTemporary(player.inventoryMenu.getCarried())) {
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        if (changed) refreshInventory(player);
    }

    public static void refreshInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastFullState();
    }

    private static TransformationNetwork.State snapshot(ServerPlayer player) {
        CompoundTag state = data(player);
        return new TransformationNetwork.State(player.getUUID(), state.getInt("phase"), state.getLong("start"),
                state.getInt("elapsed"), state.getLong("seed"));
    }

    public static void synchronize(ServerPlayer player) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, snapshot(player));
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag state = data(player);
        if (isActive(player)) {
            // Forced changes from commands or other mods terminate the whole session, never repair pieces.
            if (!player.isAlive() || player.isSpectator() || !intact(player)) {
                stop(player);
                return;
            }
            long now = player.serverLevel().getGameTime();
            long delta = Math.max(0, now - state.getLong("lastTick"));
            state.putLong("lastTick", now);
            int elapsed = (int) Math.min(TransformationTimeline.DURATION_TICKS, state.getInt("elapsed") + delta);
            state.putInt("elapsed", elapsed);
            if (!state.getBoolean("armored") && elapsed >= TransformationTimeline.ARMOR_TICK) equip(player);
            if (elapsed >= TransformationTimeline.DURATION_TICKS && state.getInt("phase") == 1) {
                state.putInt("phase", 2);
                synchronize(player);
            } else if (player.tickCount % 20 == 0) synchronize(player);
        } else {
            // This also recovers escrow after an interrupted save or an older mod session.
            if (!state.getList("escrow", Tag.TAG_COMPOUND).isEmpty()) stop(player);
            else retryPending(player);
        }
        purgeEscaped(player);
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target)
            PacketDistributor.sendToPlayer(viewer, snapshot(target));
    }

    @SubscribeEvent
    public static void stopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target)
            PacketDistributor.sendToPlayer(viewer, new TransformationNetwork.State(target.getUUID(), 0, 0, 0, 0));
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player);
            synchronize(player);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) stop(player);
    }

    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player);
            synchronize(player);
        }
    }

    @SubscribeEvent
    public static void clonePlayer(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // The original player is no longer an inventory return destination. Copy its durable transaction
        // and restore into the new player exactly once; ordinary death already settled escrow before drops.
        CompoundTag old = data(event.getOriginal());
        player.getPersistentData().put(DATA, old.copy());
        old.remove("escrow");
        old.remove("pending");
        old.putInt("phase", 0);
        stop(player);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) synchronize(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Restore before vanilla drops the inventory. Vanilla then owns keepInventory and vanishing rules.
        stop(player);
        if (!player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY) && !player.isSpectator()) {
            CompoundTag state = data(player);
            ListTag pending = state.getList("pending", Tag.TAG_COMPOUND);
            state.remove("pending");
            for (int i = 0; i < pending.size(); i++) {
                ItemStack stack = ItemStack.parseOptional(player.registryAccess(), pending.getCompound(i).getCompound("stack"));
                if (!stack.isEmpty() && !isTemporary(stack)
                        && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP))
                    player.drop(stack, true, false);
            }
        }
    }

    @SubscribeEvent
    public static void transientItemSpawn(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && isTemporary(item.getItem())) {
            event.setCanceled(true);
            item.discard();
        }
    }
}
