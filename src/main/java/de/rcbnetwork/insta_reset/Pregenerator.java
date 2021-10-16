package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.QueueingWorldGenerationProgressListener;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.UserCache;
import net.minecraft.util.dynamic.RegistryOps;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.UUID;

public class Pregenerator {

    private static final Logger LOGGER = LogManager.getLogger();

    public static PregeneratingPartialLevel pregenerate(MinecraftClient client, String hash, String fileName, GeneratorOptions generatorOptions, RegistryTracker.Modifiable registryTracker, LevelInfo levelInfo) throws IOException, ExecutionException, InterruptedException {
        // method_29605 (MinecraftClient.java:1645) + startIntegratedServer (MinecraftClient.java:1658)
        Function<LevelStorage.Session, DataPackSettings> function = (session) -> {
            return levelInfo.method_29558();
        };
        Function4<LevelStorage.Session, RegistryTracker.Modifiable, ResourceManager, DataPackSettings, SaveProperties> function4 = (session, modifiable2, resourceManager, dataPackSettings) -> {
            RegistryOps<JsonElement> registryOps = RegistryOps.of(JsonOps.INSTANCE, resourceManager, registryTracker);
            DataResult<SimpleRegistry<DimensionOptions>> dataResult = registryOps.loadToRegistry(generatorOptions.getDimensionMap(), Registry.DIMENSION_OPTIONS, DimensionOptions.CODEC);
            Logger var10001 = LOGGER;
            var10001.getClass();
            SimpleRegistry<DimensionOptions> simpleRegistry = (SimpleRegistry) dataResult.resultOrPartial(var10001::error).orElse(generatorOptions.getDimensionMap());
            return new LevelProperties(levelInfo, generatorOptions.method_29573(simpleRegistry), dataResult.lifecycle());
        };
        boolean safeMode = false;
        //MinecraftClient.WorldLoadAction worldLoadAction = MinecraftClient.WorldLoadAction.CREATE;

        // startIntegratedServer (MinecraftClient.java:1658)
        LevelStorage levelStorage = client.getLevelStorage();
        LevelStorage.Session session2;
        try {
            // fileName == worldName (CreateWorldScreen.java:221)
            session2 = levelStorage.createSession(fileName);
        } catch (IOException var21) {
            LOGGER.warn((String)"Failed to read level {} data", (Object)fileName, (Object)var21);
            SystemToast.addWorldAccessFailureToast(client, fileName);
            throw var21;
        }

        MinecraftClient.IntegratedResourceManager integratedResourceManager2;
        try {
            // Maybe doesn't work!!
            integratedResourceManager2 = client.method_29604(registryTracker, function, function4, safeMode, session2);
        } catch (Exception var20) {
            try {
                session2.close();
            } catch (IOException var16) {
                LOGGER.warn((String)"Failed to unlock access to level {}", (Object)fileName, (Object)var16);
            } finally {
                throw var20;
            }
        }

        SaveProperties saveProperties = integratedResourceManager2.getSaveProperties();

        // boolean bl = saveProperties.getGeneratorOptions().isLegacyCustomizedType();
        // boolean bl2 = saveProperties.method_29588() != Lifecycle.stable();
        // if (worldLoadAction == MinecraftClient.WorldLoadAction.NONE || !bl && !bl2) {
        // bl is always false and method_29588 should always return stable
        // MinecraftClient.java:1691
        session2.method_27425(registryTracker, saveProperties);
        integratedResourceManager2.getServerResourceManager().loadRegistryTags();
        YggdrasilAuthenticationService yggdrasilAuthenticationService = new YggdrasilAuthenticationService(client.getNetworkProxy(), UUID.randomUUID().toString());
        MinecraftSessionService minecraftSessionService = yggdrasilAuthenticationService.createMinecraftSessionService();
        GameProfileRepository gameProfileRepository = yggdrasilAuthenticationService.createProfileRepository();
        UserCache userCache = new UserCache(gameProfileRepository, new File(client.runDirectory, MinecraftServer.USER_CACHE_FILE.getName()));
        SkullBlockEntity.setUserCache(userCache);
        SkullBlockEntity.setSessionService(minecraftSessionService);
        //UserCache.setUseRemote(false);

        // loadWorld (MinecraftServer.java:314)
        final AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker = new AtomicReference<>();
        final AtomicReference<Queue> renderTaskQueue = new AtomicReference<>();
        IntegratedServer server = (IntegratedServer)MinecraftServer.startServer((serverThread) -> {
            return new IntegratedServer(serverThread, client, registryTracker, session2, integratedResourceManager2.getResourcePackManager(), integratedResourceManager2.getServerResourceManager(), saveProperties, minecraftSessionService, gameProfileRepository, userCache, (i) -> {
                WorldGenerationProgressTracker wgpt = new WorldGenerationProgressTracker(i + 0);
                worldGenerationProgressTracker.set(wgpt);
                wgpt.start();
                // MinecraftClient.java:351
                Queue rtg = Queues.newConcurrentLinkedQueue();
                renderTaskQueue.set(rtg);
                rtg.getClass();
                return new QueueingWorldGenerationProgressListener(wgpt, rtg::add);
            });
        });
        return new PregeneratingPartialLevel(hash, fileName, levelInfo, generatorOptions, integratedResourceManager2, session2, worldGenerationProgressTracker, server, renderTaskQueue, minecraftSessionService, userCache);
    }

    public static final class PregeneratingPartialLevel {

        private String hash;
        public final String fileName;

        public final LevelInfo levelInfo;

        public final GeneratorOptions generatorOptions;

        public final MinecraftClient.IntegratedResourceManager integratedResourceManager;

        public final LevelStorage.Session session;

        public final AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker;

        public final IntegratedServer server;

        public final AtomicReference<Queue> renderTaskQueue;

        public final MinecraftSessionService minecraftSessionService;

        public final UserCache userCache;

        public PregeneratingPartialLevel(String hash, String fileName, LevelInfo levelInfo, GeneratorOptions generatorOptions, MinecraftClient.IntegratedResourceManager integratedResourceManager, LevelStorage.Session session, AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker, IntegratedServer server, AtomicReference<Queue> renderTaskQueue, MinecraftSessionService minecraftSessionService, UserCache userCache) {
            this.hash = hash;
            this.fileName = fileName;
            this.levelInfo = levelInfo;
            this.generatorOptions = generatorOptions;
            this.integratedResourceManager = integratedResourceManager;
            this.session = session;
            this.worldGenerationProgressTracker = worldGenerationProgressTracker;
            this.server = server;
            this.renderTaskQueue = renderTaskQueue;
            this.minecraftSessionService = minecraftSessionService;
            this.userCache = userCache;
        }
    }
}
