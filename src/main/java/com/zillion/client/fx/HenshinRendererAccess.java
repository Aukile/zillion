package com.zillion.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

public final class HenshinRendererAccess {
   private HenshinRendererAccess() {
   }

   public static void quad(
      VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float r, float g, float b, float a
   ) {
      HenshinRenderer.quad(vc, m, x0, y0, x1, y1, u0, v0, u1, v1, r, g, b, a);
   }
}
