package de.rcbnetwork.insta_reset.mixin.client;

import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.Lifecycle;
import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.Pregenerator;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import de.rcbnetwork.insta_reset.interfaces.InitiallyHibernatingServer;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.util.Session;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.resource.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.UserCache;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow
    AtomicReference<WorldGenerationProgressTracker> worldGenProgressTracker;

    @Shadow
    Queue renderTaskQueue;

    @Shadow
    IntegratedServer server;

    @Shadow
    boolean isIntegratedServerRunning;

    @Shadow
    Profiler profiler;

    @Shadow
    CrashReport crashReport;

    @Shadow
    ClientConnection connection;

    @Shadow
    void disconnect() {
    }

    @Shadow
    void openScreen(@Nullable Screen screen) {
    }

    @Shadow
    static Logger LOGGER;

    @Shadow
    void render(boolean tick) {
    }

    @Shadow
    static void printCrashReport(CrashReport crashReport) {
    }

    @Shadow
    public Session getSession() {
        return null;
    }

    @Unique
    public final Object integratedResourceManagerLock = new Object();

    @Shadow
    private void method_29601(MinecraftClient.WorldLoadAction worldLoadAction, String string, boolean bl, Runnable runnable) {
    }

    @Shadow
    private void startIntegratedServer(String worldName, RegistryTracker.Modifiable registryTracker, Function<LevelStorage.Session, DataPackSettings> function, Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4, boolean safeMode, MinecraftClient.WorldLoadAction worldLoadAction) {
    }


    @Inject(method = "startIntegratedServer(Ljava/lang/String;Lnet/minecraft/util/registry/RegistryTracker$Modifiable;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function4;ZLnet/minecraft/client/MinecraftClient$WorldLoadAction;)V", at = @At("HEAD"), cancellable = true)
    private void replaceStartIntegratedServer(String worldName, RegistryTracker.Modifiable registryTracker, Function<LevelStorage.Session, DataPackSettings> function, Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4, boolean safeMode, MinecraftClient.WorldLoadAction worldLoadAction, CallbackInfo info) {
        InstaReset.InstaResetState state = InstaReset.instance().getState();
        if (state != InstaReset.InstaResetState.STARTING && state != InstaReset.InstaResetState.RUNNING) {
            return;
        }
        // MinecraftClient.java:1659
        Pregenerator.PregeneratingLevel currentLevel = InstaReset.instance().getCurrentLevel();
        LevelStorage.Session session2 = currentLevel.session;
        MinecraftClient.IntegratedResourceManager integratedResourceManager2 = currentLevel.integratedResourceManager;

        SaveProperties saveProperties = integratedResourceManager2.getSaveProperties();
        boolean bl = saveProperties.getGeneratorOptions().isLegacyCustomizedType();
        boolean bl2 = saveProperties.method_29588() != Lifecycle.stable();
        if (worldLoadAction == MinecraftClient.WorldLoadAction.NONE || !bl && !bl2) {
            this.disconnect();
            this.worldGenProgressTracker = currentLevel.worldGenerationProgressTracker;

            try {
                //session2.method_27425(registryTracker, saveProperties);
                //integratedResourceManager2.getServerResourceManager().loadRegistryTags();
                //YggdrasilAuthenticationService yggdrasilAuthenticationService = new YggdrasilAuthenticationService(this.netProxy, UUID.randomUUID().toString());
                //MinecraftSessionService minecraftSessionService = yggdrasilAuthenticationService.createMinecraftSessionService();
                //GameProfileRepository gameProfileRepository = yggdrasilAuthenticationService.createProfileRepository();
                //UserCache userCache = new UserCache(gameProfileRepository, new File(currentLevel.fileName, MinecraftServer.USER_CACHE_FILE.getName()));
                SkullBlockEntity.setUserCache(currentLevel.userCache);
                SkullBlockEntity.setSessionService(currentLevel.minecraftSessionService);
                UserCache.setUseRemote(false);
                this.renderTaskQueue = currentLevel.renderTaskQueue.get();
                this.server = currentLevel.server;
                this.isIntegratedServerRunning = true;
            } catch (Throwable var19) {
                CrashReport crashReport = CrashReport.create(var19, "Starting integrated server");
                CrashReportSection crashReportSection = crashReport.addElement("Starting integrated server");
                crashReportSection.add("Level ID", (Object) worldName);
                crashReportSection.add("Level Name", (Object) saveProperties.getLevelName());
                throw new CrashException(crashReport);
            }

            while (this.worldGenProgressTracker.get() == null) {
                Thread.yield();
            }

            ((InitiallyHibernatingServer) this.server).wakeUp();
            if (!this.server.isLoading()) {
                this.profiler.push("waitForServer");
                LevelLoadingScreen levelLoadingScreen = new LevelLoadingScreen((WorldGenerationProgressTracker) this.worldGenProgressTracker.get());
                this.openScreen(levelLoadingScreen);

                while (!this.server.isLoading()) {
                    levelLoadingScreen.tick();
                    this.render(false);

                    try {
                        Thread.sleep(16L);
                    } catch (InterruptedException var18) {
                    }

                    if (this.crashReport != null) {
                        printCrashReport(this.crashReport);
                        return;
                    }
                }
                this.profiler.pop();
            }

            SocketAddress socketAddress = this.server.getNetworkIo().bindLocal();
            ClientConnection clientConnection = ClientConnection.connectLocal(socketAddress);
            clientConnection.setPacketListener(new ClientLoginNetworkHandler(clientConnection, (MinecraftClient) (Object) this, (Screen) null, (text) -> {
            }));
            clientConnection.send(new HandshakeC2SPacket(socketAddress.toString(), 0, NetworkState.LOGIN));
            clientConnection.send(new LoginHelloC2SPacket(this.getSession().getProfile()));
            this.connection = clientConnection;
        } else {
            this.method_29601(worldLoadAction, worldName, bl, () -> {
                this.startIntegratedServer(worldName, registryTracker, function, function4, safeMode, MinecraftClient.WorldLoadAction.NONE);
            });
            integratedResourceManager2.close();

            try {
                session2.close();
            } catch (IOException var17) {
                LOGGER.warn((String) "Failed to unlock access to level {}", (Object) worldName, (Object) var17);
            }
        }
        info.cancel();
    }

    @Inject(method = "method_29604", at = @At("HEAD"), cancellable = true)
    public void replaceCreateResourceManager(RegistryTracker.Modifiable modifiable, Function<LevelStorage.Session, DataPackSettings> function, Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4, boolean bl, LevelStorage.Session session, CallbackInfoReturnable info) throws InterruptedException, ExecutionException {
        InstaReset.InstaResetState state = InstaReset.instance().getState();
        if (state != InstaReset.InstaResetState.STARTING && state != InstaReset.InstaResetState.RUNNING) {
            return;
        }
        // MinecraftClient.java:1830
        DataPackSettings dataPackSettings = (DataPackSettings) function.apply(session);
        ResourcePackManager resourcePackManager = new ResourcePackManager(ResourcePackProfile::new, new ResourcePackProvider[]{new VanillaDataPackProvider(), new FileResourcePackProvider(session.getDirectory(WorldSavePath.DATAPACKS).toFile(), ResourcePackSource.PACK_SOURCE_WORLD)});

        try {
            DataPackSettings dataPackSettings2;
            ServerResourceManager serverResourceManager;
            synchronized (this.integratedResourceManagerLock) {
                dataPackSettings2 = MinecraftServer.loadDataPacks(resourcePackManager, dataPackSettings, bl);
                CompletableFuture<ServerResourceManager> completableFuture = ServerResourceManager.reload(resourcePackManager.createResourcePacks(), CommandManager.RegistrationEnvironment.INTEGRATED, 2, Util.getServerWorkerExecutor(), (MinecraftClient) (Object) this);
                ((MinecraftClient) (Object) this).runTasks(completableFuture::isDone);
                serverResourceManager = completableFuture.get();
            }
            SaveProperties saveProperties = (SaveProperties) function4.apply(session, modifiable, serverResourceManager.getResourceManager(), dataPackSettings2);
            info.setReturnValue(new MinecraftClient.IntegratedResourceManager(resourcePackManager, serverResourceManager, saveProperties));

        } catch (ExecutionException | InterruptedException var12) {
            resourcePackManager.close();
            throw var12;
        }
        info.cancel();
    }

    // From Fast-Reset-Mod
    @Inject(method = "method_29607", at = @At("HEAD"))
    public void worldWait(String worldName, LevelInfo levelInfo, RegistryTracker.Modifiable registryTracker, GeneratorOptions generatorOptions, CallbackInfo ci) {
        if (this.server != null && ((FlushableServer) this.server).shouldFlush()) {
            synchronized (((FlushableServer) this.server).getFlushLock()) {
                System.out.println("done waiting for save lock");
            }
        }

    }
}
