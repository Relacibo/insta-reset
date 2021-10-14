package de.rcbnetwork.insta_reset.mixin;

import com.google.common.collect.ImmutableList;
import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.PregeneratingPartialLevel;
import de.rcbnetwork.insta_reset.interfaces.MinecraftServerCustomInterface;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.entity.boss.BossBarManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorderListener;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.UnmodifiableLevelProperties;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements MinecraftServerCustomInterface {
    @Shadow
    Map<RegistryKey<World>, ServerWorld> worlds;

    @Shadow
    DataCommandStorage dataCommandStorage;

    @Shadow
    SaveProperties saveProperties;

    @Shadow
    RegistryTracker.Modifiable dimensionTracker;

    @Shadow
    Executor workerExecutor;

    @Shadow
    LevelStorage.Session session;

    @Shadow
    void initScoreboard(PersistentStateManager persistentStateManager) {}

    @Shadow
    PlayerManager getPlayerManager() { return null; }

    @Shadow
    BossBarManager getBossBarManager() { return null; }

    @Shadow
    static Logger LOGGER;

    @Shadow
    public void runServer() {}

    @Inject(method = "startServer", at = @At("HEAD"), cancellable = true)
    public static <S extends MinecraftServer> void replaceStartServer(Function<Thread, S> serverFactory, CallbackInfoReturnable info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        AtomicReference<S> atomicReference = new AtomicReference();
        Thread thread = new Thread(() -> {
            try {
                InstaReset.instance().getCurrentLevel().serverThread.join();
            } catch (InterruptedException e) {
                LOGGER.error(e);
            }
            ((MinecraftServerCustomInterface)atomicReference.get()).runServer();
        }, "Server thread");
        thread.setUncaughtExceptionHandler((threadx, throwable) -> {
            LOGGER.error((Object)throwable);
        });
        MinecraftServer minecraftServer = (MinecraftServer)serverFactory.apply(thread);
        atomicReference.set((S)minecraftServer);
        thread.start();
        info.setReturnValue(minecraftServer);
    }

    @Inject(method = "createWorlds", at = @At("HEAD"), cancellable = true)
    void replaceCreateWorlds(WorldGenerationProgressListener worldGenerationProgressListener, CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        PregeneratingPartialLevel partialLevel = InstaReset.instance().getCurrentLevel();
        ServerWorld serverWorld = partialLevel.serverWorld;
        SimpleRegistry<DimensionOptions> simpleRegistry = partialLevel.simpleRegistry;
        // MinecraftServer.java:346
        this.worlds.put(World.OVERWORLD, serverWorld);
        PersistentStateManager persistentStateManager = serverWorld.getPersistentStateManager();
        this.initScoreboard(persistentStateManager);
        this.dataCommandStorage = new DataCommandStorage(persistentStateManager);
        // MinecraftServer.java:373
        this.getPlayerManager().setMainWorld(serverWorld);
        if (this.saveProperties.getCustomBossEvents() != null) {
            this.getBossBarManager().fromTag(this.saveProperties.getCustomBossEvents());
        }

        Iterator var18 = simpleRegistry.getEntries().iterator();

        while(var18.hasNext()) {
            Map.Entry<RegistryKey<DimensionOptions>, DimensionOptions> entry = (Map.Entry)var18.next();
            RegistryKey<DimensionOptions> registryKey2 = (RegistryKey)entry.getKey();
            if (registryKey2 != DimensionOptions.OVERWORLD) {
                RegistryKey<World> registryKey3 = RegistryKey.of(Registry.DIMENSION, registryKey2.getValue());
                DimensionType dimensionType3 = ((DimensionOptions)entry.getValue()).getDimensionType();
                RegistryKey<DimensionType> registryKey4 = (RegistryKey)this.dimensionTracker.getDimensionTypeRegistry().getKey(dimensionType3).orElseThrow(() -> {
                    return new IllegalStateException("Unregistered dimension type: " + dimensionType3);
                });
                ChunkGenerator chunkGenerator3 = ((DimensionOptions)entry.getValue()).getChunkGenerator();
                UnmodifiableLevelProperties unmodifiableLevelProperties = new UnmodifiableLevelProperties(this.saveProperties, this.saveProperties.getMainWorldProperties());
                ServerWorld serverWorld2 = new ServerWorld((MinecraftServer)(Object) this, this.workerExecutor, this.session, unmodifiableLevelProperties, registryKey3, registryKey4, dimensionType3, worldGenerationProgressListener, chunkGenerator3, this.saveProperties.getGeneratorOptions().isDebugWorld(), this.saveProperties.getGeneratorOptions().getSeed(), ImmutableList.of(), false);
                serverWorld.getWorldBorder().addListener(new WorldBorderListener.WorldBorderSyncer(serverWorld2.getWorldBorder()));
                this.worlds.put(registryKey3, serverWorld2);
            }
        }
        info.cancel();
    }

    @Inject(method = "prepareStartRegion", at = @At("HEAD"), cancellable = true)
    private void extendPrepareStartRegion(WorldGenerationProgressListener worldGenerationProgressListener, CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        info.cancel();
    }
}
