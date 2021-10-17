package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin implements FlushableServer {
    @Shadow
    @Final
    protected LevelStorage.Session session;

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
}
