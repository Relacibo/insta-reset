package de.rcbnetwork.insta_reset;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DatapackFailureScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.dynamic.RegistryOps;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
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

        public PregeneratingPartialLevel(String fileName, LevelInfo levelInfo, GeneratorOptions generatorOptions, Thread serverThread, ServerWorld serverWorld, SimpleRegistry<DimensionOptions> simpleRegistry, MinecraftClient.IntegratedResourceManager integratedResourceManager, LevelStorage.Session session) {
            this.fileName = fileName;
            this.levelInfo = levelInfo;
            this.generatorOptions = generatorOptions;
            this.serverThread = serverThread;
            this.serverWorld = serverWorld;
            this.simpleRegistry = simpleRegistry;
            this.integratedResourceManager = integratedResourceManager;
            this.session = session;
        }
    }
}
