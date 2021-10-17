package de.rcbnetwork.insta_reset.mixin;

import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.Pregenerator;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.gui.screen.DatapackFailureScreen;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.Session;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.QueueingWorldGenerationProgressListener;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.UserCache;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.dynamic.RegistryOps;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.transformer.meta.MixinInner;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.UUID;
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
    void disconnect() {}

    @Shadow
    void openScreen(@Nullable Screen screen) {}

    @Shadow
    static Logger LOGGER;

    @Shadow
    void render(boolean tick) {}

    @Shadow
    static void printCrashReport(CrashReport crashReport) {}

    @Shadow
    public Session getSession() { return null; }

    @Shadow
    private void method_29601(MinecraftClient.WorldLoadAction worldLoadAction, String string, boolean bl, Runnable runnable) { }

    @Shadow
    private void startIntegratedServer(String worldName, RegistryTracker.Modifiable registryTracker, Function<LevelStorage.Session, DataPackSettings> function, Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4, boolean safeMode, MinecraftClient.WorldLoadAction worldLoadAction) {}

    @Inject(method = "startIntegratedServer(Ljava/lang/String;Lnet/minecraft/util/registry/RegistryTracker$Modifiable;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function4;ZLnet/minecraft/client/MinecraftClient$WorldLoadAction;)V", at = @At("HEAD"), cancellable = true)
    private void replaceStartIntegratedServer(String worldName, RegistryTracker.Modifiable registryTracker, Function<LevelStorage.Session, DataPackSettings> function, Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4, boolean safeMode, MinecraftClient.WorldLoadAction worldLoadAction, CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        // MinecraftClient.java:1659
        Pregenerator.PregeneratingPartialLevel currentLevel = InstaReset.instance().getCurrentLevel();
        LevelStorage.Session session2 = currentLevel.session;
        MinecraftClient.IntegratedResourceManager integratedResourceManager2 = currentLevel.integratedResourceManager;

        SaveProperties saveProperties = integratedResourceManager2.getSaveProperties();
        boolean bl = saveProperties.getGeneratorOptions().isLegacyCustomizedType();
        boolean bl2 = saveProperties.method_29588() != Lifecycle.stable();
        if (worldLoadAction == MinecraftClient.WorldLoadAction.NONE || !bl && !bl2) {
            this.disconnect();
            this.worldGenProgressTracker = currentLevel.worldGenerationProgressTracker;

            try {
                session2.method_27425(registryTracker, saveProperties);
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
                crashReportSection.add("Level ID", (Object)worldName);
                crashReportSection.add("Level Name", (Object)saveProperties.getLevelName());
                throw new CrashException(crashReport);
            }

            while(this.worldGenProgressTracker.get() == null) {
                Thread.yield();
            }

            if (worldGenProgressTracker.get().getProgressPercentage() < 100) {
                LevelLoadingScreen levelLoadingScreen = new LevelLoadingScreen((WorldGenerationProgressTracker)this.worldGenProgressTracker.get());
                this.openScreen(levelLoadingScreen);
                this.profiler.push("waitForServer");

                while(!this.server.isLoading()) {
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
            clientConnection.setPacketListener(new ClientLoginNetworkHandler(clientConnection, (MinecraftClient)(Object)this, (Screen)null, (text) -> {
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
                LOGGER.warn((String)"Failed to unlock access to level {}", (Object)worldName, (Object)var17);
            }

        }
        info.cancel();
    }
}