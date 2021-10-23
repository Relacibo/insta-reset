package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import joptsimple.internal.Rows;
import net.minecraft.util.thread.MessageListener;
import net.minecraft.util.thread.ThreadExecutor;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@Mixin(ThreadExecutor.class)
public abstract class ThreadExecutorMixin<R extends Runnable> implements MessageListener<R>, Executor {
    @Shadow
    public abstract void runTasks(BooleanSupplier stopCondition);

    @Unique
    private final Object tasksLock = new Object();

    @Shadow
    private int executionsInProgress;

    @Shadow
    protected boolean runTask() {
        return false;
    }

    @Shadow
    protected void waitForTasks() {
    }

    /*@Inject(method = "submitAsync", at = @At("HEAD"))
    private void replaceSubmitAsync(Runnable runnable, CallbackInfoReturnable<CompletableFuture<Void>> info) {
        synchronized (tasksLock) {
            CompletableFuture<Void> ret = CompletableFuture.supplyAsync(() -> {
                runnable.run();
                return null;
            }, this);
            info.setReturnValue(ret);
        }
    }*/
    /*@Inject(method = "cancelTasks", at = @At("HEAD"), cancellable = true)
    private void extendCancelTasks(CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        this.runTasks();

        info.cancel();
    }*/

    @Inject(method = "runTasks()V", at = @At("HEAD"), cancellable = true)
    private void replaceRunTasks(CallbackInfo info) {
        synchronized (tasksLock) {
            while (this.runTask()) {
            }
        }
        info.cancel();
    }

    @Inject(method = "runTasks(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
    private void replaceRunTasks(BooleanSupplier stopCondition, CallbackInfo info) {
        synchronized (tasksLock) {
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

    @Shadow
    public void execute(@NotNull Runnable command) {

    }

    @Shadow
    public String getName() {
        return null;
    }

    @Shadow
    public void send(R message) {

    }
}
