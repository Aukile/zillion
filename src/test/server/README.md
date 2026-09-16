# Transformation server acceptance tests

The dependency-free `TransformationSafetyTest.java` exercises the actual production escrow transfer arithmetic and shared timeline constants. It is not a replacement for the Minecraft acceptance tests below.

## Integration

- Mod constructor: `ModItems.register(modEventBus)`.
- Mod constructor: `modEventBus.addListener(TransformationNetwork::register)`.
- `TransformationManager` registers itself on the game event bus through `@EventBusSubscriber`; no explicit initialization is required.
- `TransformationKeys` autonomously registers keys, installs the render-cache consumer and clears it on disconnect. The common networking/manager classes never import client classes.
- Language keys: `key.zillion.transform`, `key.zillion.deform`, `key.categories.zillion` (category value `Zillion`).
- Driver registry ID: `zillion:zillion_driver`. Transient IDs: `gazerzero_helmet`, `gazerzero_chestplate`, `gazerzero_boots` in namespace `zillion`.

## Required real-server matrix

Use a dedicated server and two clients (A transforms, B watches). Test both survival and creative. Before each test name/enchant/damage A's original helmet, chest armor/elytra and boots so exact restoration is distinguishable from fresh copies.

1. Wear/remove the driver in LEGS before transforming; normal armor, shift-click and right-click continue to work.
2. G starts once. Repeated G does not restart. At tick 84 the three temporary pieces appear; at tick 160 the state completes. H cancels at ticks 1, 83, 84, 159 and after 160; originals return with their exact components, once only.
3. While both transforming and complete, attempt armor pickup, throw, shift-click, hotbar-number swap, drag into armor slots, double-click collection, armor/elytra right-click swap and creative direct slot writes. The belt plus all three armor slots remain locked, including the empty slots before tick 84. Ordinary inventory operations still work.
4. Begin an armor drag immediately before G, then finish it during transformation. Its previously collected armor slot set must not bypass the lock.
5. Fill the inventory, use `/item replace entity A armor.head with minecraft:diamond_helmet`, and repeat on chest, feet and legs. By the next server player tick the whole transformation ends and every temporary piece disappears. Replacement equipment remains; original overflow stays in persistent pending returns. Free a main inventory slot or the original armor slot: receive exactly one original, even in creative mode.
6. Copy temporary gear into inventory/cursor using commands. Wrong-session/owner gear is purged. A transient ItemEntity is rejected on spawn/load. Temporary gear never becomes an ordinary collectible drop.
7. Repeat death during each phase with `keepInventory false` and `true`. With false, originals and belt follow vanilla death drops; with true, they survive. Temporary pieces never drop. Also test a forced slot replacement plus full inventory immediately followed by death: pending originals drop when keepInventory is false and remain pending otherwise.
8. Log out/in while starting and complete. Restart the server after logout. Originals restore once; stale temporary equipment is removed. Disconnecting does not leave a client visual cache.
9. Change dimension or return from the End while active. The policy intentionally ends the session and restores originals instead of transporting an unfinished escrow transaction. Respawn/clone do not return originals twice.
10. B begins tracking A at ticks 40, 100 and after completion. B receives current phase, elapsed, original start time and seed. A and B see the same seeded effect; late tracking never starts at zero. Stop is broadcast to both, and a completed cache remains capped at 160.
11. Test server save/restart while active without a normal logout, then login: persistent escrow recovers once, including a full inventory with pending originals.
12. Dedicated-server startup must load no `net.minecraft.client` class from networking or transformation code. Launch once with mixin audit/debug logging to verify all mandatory injection targets apply on NeoForge 21.1.250.

## Standalone PowerShell test command

Run from the project root with Java 21 (this does not start Gradle):

```powershell
& E:/mcc/java21/bin/javac.exe -d src/test/server/.classes src/main/java/net/ankrya/zillion/transformation/EscrowTransfer.java src/main/java/net/ankrya/zillion/transformation/TransformationTimeline.java src/test/server/TransformationSafetyTest.java
& E:/mcc/java21/bin/java.exe -cp src/test/server/.classes TransformationSafetyTest
```

An abrupt operating-system failure during disk writes has the same save-atomicity limitations as vanilla player data. Inventory and escrow are kept in the same player save; there is no separate external escrow file to become inconsistent.
