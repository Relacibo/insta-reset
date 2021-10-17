package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import de.rcbnetwork.insta_reset.interfaces.InitiallyPauseableServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin implements FlushableServer, InitiallyPauseableServer {
    @Shadow
    @Final
    protected LevelStorage.Session session;

    @Shadow
    @Final
    Thread serverThread;

    @Shadow
    private void tick(BooleanSupplier supplier) {  }

    @Unique
    private AtomicReference<Boolean> _shouldFlush = new AtomicReference<>(false);

    @Override
    public boolean shouldFlush() {
        return _shouldFlush.get();
    }

    @Override
    public void setShouldFlush(boolean shouldFlush) {
        _shouldFlush.set(shouldFlush);
    }

    @Unique
    private final Object flushLock = new Object();

    @Override
    public Object getFlushLock() {
        return flushLock;
    }

    @Unique
    private AtomicReference<Boolean> pausingInitially = new AtomicReference<>(false);

    @Override
    public boolean isPausingInitially() {
        return pausingInitially.get();
    }

    @Override
    public void unpause() {
        this.pausingInitially.set(false);
    }

    // kill save on the shutdown
    @Redirect(method = "shutdown", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0))
    private boolean getWorldsInjectOne(Iterator<ServerWorld> iterator) {
        if (!this.shouldFlush()) {
            return iterator.hasNext();
        }
        while (iterator.hasNext()) {
            iterator.next().savingDisabled = true;
        }
        return false;
    }

    @Redirect(method = "shutdown", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 1))
    private boolean getWorldsInjectTwo(Iterator<ServerWorld> iterator) {
        if (!this.shouldFlush()) {
            return iterator.hasNext();
        }

        Thread thread = new Thread(() -> {
            synchronized (this.getFlushLock()) {
                while (iterator.hasNext()) {
                    ServerWorld world = iterator.next();
                    if (world != null) {
                        try {
                            world.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                try {
                    this.session.deleteSessionLock();
                } catch (IOException ignored) {
                }
            }
        });
        thread.start();
        return false;
    }

    @Redirect(method = "shutdown", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;saveAllPlayerData()V"))
    private void shutdownPlayerSaveInject(PlayerManager playerManager) {
        if (!this.shouldFlush()) {
            playerManager.saveAllPlayerData();
        }
    }

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void extendInit(CallbackInfo info) {
        this.pausingInitially.set(InstaReset.instance().isModRunning());
    }

    @Redirect(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tick(Ljava/util/function/BooleanSupplier;)V", ordinal = 0))
    private void conditionalTick(MinecraftServer server, BooleanSupplier supplier) {
        if (!this.pausingInitially.get()) {
            this.tick(supplier);
        }
    }
}
