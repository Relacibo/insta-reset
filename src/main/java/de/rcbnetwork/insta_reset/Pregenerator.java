package de.rcbnetwork.insta_reset;

import com.google.common.collect.ImmutableList;
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
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.QueueingWorldGenerationProgressListener;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.UserCache;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.dynamic.RegistryOps;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.village.ZombieSiegeManager;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.WanderingTraderManager;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.*;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class Pregenerator {

    private static final Logger LOGGER = LogManager.getLogger();
    MinecraftClient client;
    private String fileName;
    private GeneratorOptions generatorOptions;
    private RegistryTracker.Modifiable registryTracker;

    public Pregenerator(MinecraftClient client, String fileName, GeneratorOptions generatorOptions, RegistryTracker.Modifiable registryTracker) {
        this.client = client;
        this.fileName = fileName;
        this.generatorOptions = generatorOptions;
        this.registryTracker = registryTracker;
    }

    public PregeneratingPartialLevel pregenerate(LevelInfo levelInfo) throws IOException, ExecutionException, InterruptedException {
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
        LevelStorage levelStorage = this.client.getLevelStorage();
        LevelStorage.Session session2;
        try {
            // fileName == worldName (CreateWorldScreen.java:221)
            session2 = levelStorage.createSession(this.fileName);
        } catch (IOException var21) {
            LOGGER.warn((String)"Failed to read level {} data", (Object)this.fileName, (Object)var21);
            SystemToast.addWorldAccessFailureToast(this.client, this.fileName);
            throw var21;
        }

        MinecraftClient.IntegratedResourceManager integratedResourceManager2;
        try {
            // Maybe doesn't work!!
            integratedResourceManager2 = this.client.method_29604(registryTracker, function, function4, safeMode, session2);
        } catch (Exception var20) {
            try {
                session2.close();
            } catch (IOException var16) {
                LOGGER.warn((String)"Failed to unlock access to level {}", (Object)this.fileName, (Object)var16);
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
        YggdrasilAuthenticationService yggdrasilAuthenticationService = new YggdrasilAuthenticationService(this.netProxy, UUID.randomUUID().toString());
        MinecraftSessionService minecraftSessionService = yggdrasilAuthenticationService.createMinecraftSessionService();
        GameProfileRepository gameProfileRepository = yggdrasilAuthenticationService.createProfileRepository();
        UserCache userCache = new UserCache(gameProfileRepository, new File(client.runDirectory, MinecraftServer.USER_CACHE_FILE.getName()));
        SkullBlockEntity.setUserCache(userCache);
        SkullBlockEntity.setSessionService(minecraftSessionService);
        UserCache.setUseRemote(false);

        // loadWorld (MinecraftServer.java:314)
        WorldGenerationProgressTracker worldGenerationProgressTracker = new WorldGenerationProgressTracker(11);
        worldGenerationProgressTracker.start();

        // createWorlds (MinecraftServer.java:323)
        ServerWorldProperties serverWorldProperties = saveProperties.getMainWorldProperties();
        boolean bl = generatorOptions.isDebugWorld();
        long l = generatorOptions.getSeed();
        long m = BiomeAccess.hashSeed(l);
        List<Spawner> list = ImmutableList.of(new PhantomSpawner(), new PillagerSpawner(), new CatSpawner(), new ZombieSiegeManager(), new WanderingTraderManager(serverWorldProperties));
        SimpleRegistry<DimensionOptions> simpleRegistry = generatorOptions.getDimensionMap();
        DimensionOptions dimensionOptions = (DimensionOptions)simpleRegistry.get(DimensionOptions.OVERWORLD);
        Object chunkGenerator2;
        DimensionType dimensionType2;
        if (dimensionOptions == null) {
            dimensionType2 = DimensionType.getOverworldDimensionType();
            chunkGenerator2 = GeneratorOptions.createOverworldGenerator((new Random()).nextLong());
        } else {
            dimensionType2 = dimensionOptions.getDimensionType();
            chunkGenerator2 = dimensionOptions.getChunkGenerator();
        }

        RegistryKey<DimensionType> registryKey = (RegistryKey)this.dimensionTracker.getDimensionTypeRegistry().getKey(dimensionType2).orElseThrow(() -> {
            return new IllegalStateException("Unregistered dimension type: " + dimensionType2);
        });
        ServerWorld serverWorld = new ServerWorld(this, this.workerExecutor, this.session, serverWorldProperties, World.OVERWORLD, registryKey, dimensionType2, worldGenerationProgressListener, (ChunkGenerator)chunkGenerator2, bl, m, list, true);
        Thread pregenerationThread = new Thread(() -> {

        });
    }

    public final class PregeneratingPartialLevel {

        public final String fileName;

        public final LevelInfo levelInfo;

        public final GeneratorOptions generatorOptions;

        public final Thread serverThread;

        public final ServerWorld serverWorld;

        public final SimpleRegistry<DimensionOptions> simpleRegistry;

        public final MinecraftClient.IntegratedResourceManager integratedResourceManager;

        public final LevelStorage.Session session;

        public final WorldGenerationProgressTracker worldGenerationProgressTracker;

        public PregeneratingPartialLevel(String fileName, LevelInfo levelInfo, GeneratorOptions generatorOptions, Thread serverThread, ServerWorld serverWorld, SimpleRegistry<DimensionOptions> simpleRegistry, MinecraftClient.IntegratedResourceManager integratedResourceManager, LevelStorage.Session session, WorldGenerationProgressTracker worldGenerationProgressTracker) {
            this.fileName = fileName;
            this.levelInfo = levelInfo;
            this.generatorOptions = generatorOptions;
            this.serverThread = serverThread;
            this.serverWorld = serverWorld;
            this.simpleRegistry = simpleRegistry;
            this.integratedResourceManager = integratedResourceManager;
            this.session = session;
            this.worldGenerationProgressTracker = worldGenerationProgressTracker;
        }
    }
}
