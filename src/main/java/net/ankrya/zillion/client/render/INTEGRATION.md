# GazerZero renderer integration (GeckoLib 4.8.2)

All Java implementation files in this directory are client-only. `RiderArmorItem` creates its
provider lazily through GeckoLib. The exact published **4.8.2 sources jar** was checked:
`GeoRenderProvider.getGeoArmorRenderer`, not `getHumanoidArmorModel`, is the provider entrypoint.
GeckoLib's armor injection calls the full `prepForRender` overload itself.

## Armor

- Register `new RiderArmorItem(ArmorItem.Type.LEGGINGS, true)` for the driver.
- HEAD/CHEST/FEET items use their normal `ArmorItem.Type` and `false`.
- The driver remains visible during transformation. Its LEGS slot explicitly renders
  `armorBody`; separate root `bone` receives the complete body pivot transformation.
- Suit geometry appears at tick 84. The FEET item renders the full original leg meshes.
- A real `FxRenderTypes.hologram(texture)` pass runs during [84,150), fading during [134,150).
  It calls the documented `FxShaders.draw(BufferSource, RenderType, seconds, strength, Runnable)`
  scope with transformationTick/20 seconds and strength 1; vertex alpha carries the fade.
  The scan shader must respect vertex alpha. Outline-wrapped buffers skip this extra scan pass.
- All modified bone visibility/position/rotation/scale is reset from the original snapshots
  for every wearer and slot. Original geometry and animation resources remain untouched.

### Required red emission masks (resource generation owned by integration)

Provide these transparent PNGs with exactly the original texture dimensions/UV positions:

- `assets/zillion/textures/item/armor/gazerzero_red_emissive.png` (128x128)
- `assets/zillion/textures/item/armor/zillion_driver_red_emissive.png` (32x32)

Preserve only the intended red pixels from each source texture, keep their original RGB,
and set all non-emitting pixels fully transparent. For an initial precise texel mask,
select alpha>0, R>=100, R>1.5*G, R>1.35*B; then inspect the result against the originals.
No whole-texture tint is used. The renderer uses `RenderType.eyes` at full brightness,
which persists after the transformation. Missing masks are skipped instead of rendering
missing-texture pink. Mask existence is checked through the resource manager, so resource
reloads need no extra texture lifecycle code. Bloom of these red eyes is not implemented here;
the parent's emission pipeline can add it if desired.

## Free-flight drone

`WingmanRenderer.renderDrone(PoseStack, MultiBufferSource, int light, float time, int argb)`

- The stack is already at the desired drone pivot with the desired orientation/scale.
- Units are blocks. No camera/world/player translation is added and buffers are not flushed.
- It renders the original 32x32-UV independent geometry, retaining its static Z=25 degree pose.
- The authored root pivot is centered at the supplied origin, rather than adding the stock
  GeoObjectRenderer's (0.5,0.51,0.5) block translation.
- `time` is continuous ticks, `argb=0xFFFFFFFF` means unchanged texture.

## Exact moving-body docking (indices 0..4 = wingman1..wingman5)

`WingmanDocking.dockTick(index)` returns **114,120,126,132,138**. Flight approach begins at 104.
Indices 0/1/2 belong to CHEST, 3/4 to FEET. Armor wingman bones stay hidden until these ticks.
Stop drawing a detached drone when its dock tick is reached to avoid double geometry.

Choose one target delivery mechanism:

1. **Immediate, inherently fresh:** `WingmanDocking.setListener((player,target,buffers,light) -> ...)`.
   Called during the actual armor base pass for each relevant target, including hidden
   wingman bones. Never reads another wearer's state or a previous render's bone matrices.
2. **Later in this frame:** call `WingmanDocking.beginFrame(uniqueFrameId)` exactly once BEFORE
   entity rendering, then query `WingmanDocking.current(UUID,index,uniqueFrameId)` AFTER entities.
   It returns Optional.empty when the player/slot was not rendered this frame. Call `clear()`
   on world/disconnect cleanup. The listener works without enabling this cache.

`Target.pose()` and `normal()` are defensive copies in the SAME render coordinate system as
armor's incoming PoseStack. They include the full player model pose, root/entity scaling,
all ancestor transformations, the docking bone rotation, and its pivot. They are NOT an
approximate height offset and are NOT inherently absolute-world coordinates.
`target.worldPosition(renderToWorld)` converts the pivot with an integration-supplied inverse
world-to-render matrix (including the camera translation). `target.applyTo(stack)` replaces
its top matrices; push/pop the stack around this call.

### Geometry-identical final approach

`WingmanRenderer.renderDetached(PoseStack, MultiBufferSource, int light, float time, int argb, int index)`

This draws the **corresponding actual armor wingman mesh and 128x128 UVs**, centered at its
pivot, without applying its bone rotation a second time. Applying `target.applyTo(stack)`
and calling this at the final approach endpoint produces exactly the vertices of the
attached armor bone. During approach interpolate the position and orientation of this pivot
pose. The attached and independent assets are not interchangeable meshes/UVs: crossfade from
`renderDrone` to `renderDetached` early in approach (e.g. 104..110), with matching moving
pivot poses, then render only the exact attachment mesh before its dock tick.

Do not keep a Target for a later frame, multiply its already-complete matrix onto another
camera matrix, or add player yaw twice. For first-person / culled players there is no armor
render target: integration must omit docking geometry or provide its own first-person pose.
The renderer does not invent a stale fallback.

## Validation

No Gradle build is run by this implementation task. Four asset-contract tests passed via
`py -3 -B src/test/python/test_rider_models.py` (full boot legs, driver root, docking pivots,
and separate drone UV layout). API signatures and render lifecycle were checked against the
published NeoForge GeckoLib 4.8.2 source artifact. Integration owns the combined compile and
in-game checks (movement, crouch, multiple wearers, reload, first person).
