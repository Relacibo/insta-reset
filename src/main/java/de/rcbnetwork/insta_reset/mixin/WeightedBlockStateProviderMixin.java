package de.rcbnetwork.insta_reset.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.collection.WeightedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.stateprovider.WeightedBlockStateProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(WeightedBlockStateProvider.class)
public class WeightedBlockStateProviderMixin {
    @Unique
    private final Object statesLock = new Object();

    @Final
    @Shadow
    private WeightedList<BlockState> states;

    @Inject(method = "getBlockState(Ljava/util/Random;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;", at = @At("HEAD"), cancellable = true)
    public void replaceGetBlockState(Random random, BlockPos pos, CallbackInfoReturnable<BlockState> info) {
        synchronized (statesLock) {
            info.setReturnValue((BlockState) this.states.pickRandom(random));
        }
    }
}
