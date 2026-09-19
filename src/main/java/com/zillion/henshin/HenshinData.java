package com.zillion.henshin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class HenshinData {
   public static final Codec<HenshinData> CODEC = RecordCodecBuilder.create(
      i -> i.group(
               Codec.BOOL.fieldOf("active").forGetter(d -> d.active),
               Codec.INT.fieldOf("tick").forGetter(d -> d.tick),
               Codec.BOOL.fieldOf("transformed").forGetter(d -> d.transformed),
               Codec.LONG.fieldOf("seed").forGetter(d -> d.seed)
            )
            .apply(i, HenshinData::new)
   );
   public boolean active;
   public int tick;
   public boolean transformed;
   public long seed;

   public HenshinData() {
   }

   public HenshinData(boolean active, int tick, boolean transformed, long seed) {
      this.active = active;
      this.tick = tick;
      this.transformed = transformed;
      this.seed = seed;
   }

   public boolean armorShouldBeLocked() {
      return this.transformed || this.active && this.tick >= HenshinTiming.ARMOR_TICK;
   }

   public void reset() {
      this.active = false;
      this.tick = 0;
      this.transformed = false;
   }
}
