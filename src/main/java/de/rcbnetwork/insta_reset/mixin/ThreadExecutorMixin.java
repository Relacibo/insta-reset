package de.rcbnetwork.insta_reset.mixin;

import net.minecraft.util.thread.ThreadExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ThreadExecutor.class)
public class ThreadExecutorMixin {
    @Unique
    private final Object runTasksLock = new Object();

    @Shadow
    private int executionsInProgress;

    @Shadow
    protected boolean runTask() {
        return false;
    }

    @Shadow
    protected void waitForTasks() {
    }

    @Inject(method = "runTasks(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
    private void replaceRunTasks(BooleanSupplier stopCondition, CallbackInfo info) {
        synchronized (runTasksLock) {
            ++this.executionsInProgress;

            try {
                while (!stopCondition.getAsBoolean()) {
                    if (!this.runTask()) {
                        this.waitForTasks();
                    }
                }
            } finally {
                --this.executionsInProgress;
            }
        }
        info.cancel();
    }
}
