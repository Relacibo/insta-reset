package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.QueueingWorldGenerationProgressListener;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.UserCache;
import net.minecraft.util.dynamic.RegistryOps;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.UUID;

public class Pregenerator {
    private static final Logger LOGGER = LogManager.getLogger();

    public static PregeneratingLevel pregenerate(MinecraftClient client, Path savesDirectory, int levelNumber, long expireAfterSeconds, Difficulty difficulty) throws IOException, ExecutionException, InterruptedException {
        long creationTimeStamp = new Date().getTime();
        long expirationTimeStamp = expireAfterSeconds != -1 ? creationTimeStamp + expireAfterSeconds * 1000L : 0;
        String levelName = Pregenerator.generateLevelName(levelNumber);
        // createLevel() (CreateWorldScreen.java:245)
        // this.client.method_29970(new SaveLevelScreen(new TranslatableText("createWorld.preparing")));
        // At this point in the original code datapacks are copied into the world folder. (CreateWorldScreen.java:247)

        // Shorter version of original code (MoreOptionsDialog.java:80 & MoreOptionsDialog.java:302)
        GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions().withHardcore(false, OptionalLong.empty());
        LevelInfo levelInfo = new LevelInfo(levelName, GameMode.SURVIVAL, false, difficulty, false, new GameRules(), DataPackSettings.SAFE_MODE);
        String seedHash = Hashing.sha256().hashString(String.valueOf(generatorOptions.getSeed()), StandardCharsets.UTF_8).toString();
        String fileName = Pregenerator.generateFileName(savesDirectory, levelName, seedHash);
        // MoreOptionsDialog:79+326
        RegistryTracker.Modifiable registryTracker = RegistryTracker.create();

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
        MinecraftClient.WorldLoadAction worldLoadAction = MinecraftClient.WorldLoadAction.CREATE;

        // startIntegratedServer (MinecraftClient.java:1658)
        LevelStorage levelStorage = client.getLevelStorage();
        LevelStorage.Session session2;
        try {
            // fileName == worldName (CreateWorldScreen.java:221)
            session2 = levelStorage.createSession(fileName);
        } catch (IOException var21) {
            LOGGER.warn((String) "Failed to read level {} data", (Object) fileName, (Object) var21);
            SystemToast.addWorldAccessFailureToast(client, fileName);
            throw var21;
        }

        MinecraftClient.IntegratedResourceManager integratedResourceManager2;
        try {
            integratedResourceManager2 = client.method_29604(registryTracker, function, function4, safeMode, session2);
        } catch (Exception var20) {
            try {
                session2.close();
            } catch (IOException var16) {
                LOGGER.warn((String) "Failed to unlock access to level {}", (Object) fileName, (Object) var16);
            } finally {
                throw var20;
            }
        }

        SaveProperties saveProperties = integratedResourceManager2.getSaveProperties();

        //boolean bl = saveProperties.getGeneratorOptions().isLegacyCustomizedType();
        //boolean bl2 = saveProperties.method_29588() != Lifecycle.stable();
        //if (worldLoadAction == MinecraftClient.WorldLoadAction.NONE || !bl && !bl2) {
        //bl is always false and method_29588 should always return stable
        // MinecraftClient.java:1691
        session2.method_27425(registryTracker, saveProperties);
        integratedResourceManager2.getServerResourceManager().loadRegistryTags();
        YggdrasilAuthenticationService yggdrasilAuthenticationService = new YggdrasilAuthenticationService(client.getNetworkProxy(), UUID.randomUUID().toString());
        MinecraftSessionService minecraftSessionService = yggdrasilAuthenticationService.createMinecraftSessionService();
        GameProfileRepository gameProfileRepository = yggdrasilAuthenticationService.createProfileRepository();
        UserCache userCache = new UserCache(gameProfileRepository, new File(client.runDirectory, MinecraftServer.USER_CACHE_FILE.getName()));
        //SkullBlockEntity.setUserCache(userCache);
        //SkullBlockEntity.setSessionService(minecraftSessionService);
        //UserCache.setUseRemote(false);

        // MinecraftClient.java:351
        final Queue<Runnable> renderTaskQueue = Queues.newConcurrentLinkedQueue();
        // loadWorld (MinecraftServer.java:314)
        final AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker = new AtomicReference<>();
        //  startIntegratedServer (MinecraftClient.java:1704)
        IntegratedServer server = (IntegratedServer) MinecraftServer.startServer((serverThread) -> {
            return new IntegratedServer(serverThread, client, registryTracker, session2, integratedResourceManager2.getResourcePackManager(), integratedResourceManager2.getServerResourceManager(), saveProperties, minecraftSessionService, gameProfileRepository, userCache, (i) -> {
                WorldGenerationProgressTracker wgpt = new WorldGenerationProgressTracker(i + 0);
                worldGenerationProgressTracker.set(wgpt);
                wgpt.start();
                Queue rtg = renderTaskQueue;
                rtg.getClass();
                return new QueueingWorldGenerationProgressListener(wgpt, rtg::add);
            });
        });
        // Fast-Reset: don't save when closing the server.
        ((FlushableServer) server).setShouldFlush(true);
        return new PregeneratingLevel(seedHash, creationTimeStamp, expirationTimeStamp, fileName, levelInfo, registryTracker, generatorOptions, integratedResourceManager2, session2, worldGenerationProgressTracker, server, renderTaskQueue, minecraftSessionService, userCache);
    }

    private static String generateLevelName(int number) {
        return String.format("Speedrun %d", number);
    }

    private static String generateFileName(Path savesDirectory, String levelName, String seedHash) {
        String fileName = String.format("%s - %s", levelName, seedHash.substring(0, 10));
        // Ensure its a unique name (CreateWorldScreen.java:222)
        try {
            fileName = FileNameUtil.getNextUniqueName(savesDirectory, fileName, "");
        } catch (Exception var4) {
            try {
                fileName = FileNameUtil.getNextUniqueName(savesDirectory, fileName, "");
            } catch (Exception var3) {
                throw new RuntimeException("Could not create save folder", var3);
            }
        }
        return fileName;
    }

    public static void uninitialize(MinecraftClient client, PregeneratingLevel level) throws IOException {
        level.server.stop(true);
        // WorldListWidget.java:323
        LevelStorage levelStorage = client.getLevelStorage();
        try {
            LevelStorage.Session session = levelStorage.createSession(level.fileName);
            Throwable var5 = null;

            try {
                session.deleteSessionLock();
            } catch (Throwable var15) {
                var5 = var15;
                throw var15;
            } finally {
                if (session != null) {
                    if (var5 != null) {
                        try {
                            session.close();
                        } catch (Throwable var14) {
                            var5.addSuppressed(var14);
                        }
                    } else {
                        session.close();
                    }
                }

            }
        } catch (IOException var17) {
            SystemToast.addWorldDeleteFailureToast(client, level.fileName);
            LOGGER.error((String) "Failed to delete world {}", (Object) level.fileName, (Object) var17);
        }
    }

    public static final class PregeneratingLevel {

        public final String hash;

        public final long creationTimeStamp;

        public final long expirationTimeStamp;

        public final String fileName;

        public final LevelInfo levelInfo;

        public final RegistryTracker.Modifiable registryTracker;

        public final GeneratorOptions generatorOptions;

        public final MinecraftClient.IntegratedResourceManager integratedResourceManager;

        public final LevelStorage.Session session;

        public final AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker;

        public final IntegratedServer server;

        public final Queue<Runnable> renderTaskQueue;

        public final MinecraftSessionService minecraftSessionService;

        public final UserCache userCache;

        public PregeneratingLevel(String hash, long creationTimeStamp, long expirationTimeStamp, String fileName, LevelInfo levelInfo, RegistryTracker.Modifiable registryTracker, GeneratorOptions generatorOptions, MinecraftClient.IntegratedResourceManager integratedResourceManager, LevelStorage.Session session, AtomicReference<WorldGenerationProgressTracker> worldGenerationProgressTracker, IntegratedServer server, Queue<Runnable> renderTaskQueue, MinecraftSessionService minecraftSessionService, UserCache userCache) {
            this.hash = hash;
            this.creationTimeStamp = creationTimeStamp;
            this.expirationTimeStamp = expirationTimeStamp;
            this.fileName = fileName;
            this.levelInfo = levelInfo;
            this.registryTracker = registryTracker;
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
