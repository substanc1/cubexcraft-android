// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package cubexcraft.mobileperf.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import cubexcraft.mobileperf.MobilePerf;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The last step of the governor where the world is still too heavy: the animated parts of block entities (machines, their
 * formed views) are drawn only so far — 48, then 32 blocks instead of 64. Their blocks stay (they are in the chunk geometry).
 * Sodium draws block entities through this method too. Off (limit 0) unless the governor asks.
 */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
    @Shadow public Camera camera;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void cubexMobile$farBlockEntity(BlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        int limitSq = MobilePerf.blockEntityLimitSq;
        if (limitSq == 0 || camera == null) return;
        Vec3 c = camera.getPosition();
        BlockPos p = be.getBlockPos();
        double dx = p.getX() + 0.5 - c.x;
        double dy = p.getY() + 0.5 - c.y;
        double dz = p.getZ() + 0.5 - c.z;
        if (dx * dx + dy * dy + dz * dz > limitSq) ci.cancel();
    }
}
