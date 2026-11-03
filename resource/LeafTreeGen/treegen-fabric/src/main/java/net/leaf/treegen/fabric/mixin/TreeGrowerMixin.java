package net.leaf.treegen.fabric.mixin;

import io.github.dailystruggle.rtp.fabric.unobf.server.FabricModServerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link TreeGrower#growTree} on Fabric so natural random-tick and bonemeal sapling
 * growth fires {@link FabricModServerAccessor#onTreeGrow}.
 */
@Mixin(TreeGrower.class)
public abstract class TreeGrowerMixin {

  @Inject(method = "growTree", at = @At("HEAD"), cancellable = true)
  private void leaftreegen$onGrowTree(
      ServerLevel level,
      ChunkGenerator chunkGenerator,
      BlockPos pos,
      BlockState state,
      RandomSource random,
      CallbackInfoReturnable<Boolean> cir) {
    if (FabricModServerAccessor.onTreeGrow(level, pos, state)) {
      cir.setReturnValue(Boolean.TRUE);
    }
  }
}
