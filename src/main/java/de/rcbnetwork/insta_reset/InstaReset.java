package de.rcbnetwork.insta_reset;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.village.ZombieSiegeManager;
import net.minecraft.world.*;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.*;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.world.level.ServerWorldProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class InstaReset implements ClientModInitializer {
	private static InstaReset _instance;
	public static InstaReset instance() {
		return _instance;
	}
	public static final String MOD_ID = "insta-reset";
	private Logger logger = LogManager.getLogger();
	private Config config;
	private MinecraftClient client;
	private boolean modRunning = false;
	private Queue<PregeneratingPartialLevel> pregeneratingLevelQueue = new LinkedList<>();
	private AtomicReference<PregeneratingPartialLevel> currentLevel;

	public PregeneratingPartialLevel getCurrentLevel() {
		return this.currentLevel.get();
	}

	public boolean isModRunning() {
		return this.modRunning;
	}

	private PregeneratingPartialLevel pollFromPregeneratingLevelQueue() {
		this.pregeneratingLevelQueue.offer(createPregeneratingPartialLevel());
		return pregeneratingLevelQueue.poll();
	}

	public void start() {
		this.modRunning = true;
		for (int i = 0; i < this.config.settings.numberOfConcurrentLevels; i++) {
			this.pregeneratingLevelQueue.offer(createPregeneratingPartialLevel());
		}
	}

	public void stop() {
		this.modRunning = false;
		this.client.world.disconnect();
		this.client.disconnect(new SaveLevelScreen(new TranslatableText("menu.savingLevel")));
		this.client.openScreen(new TitleScreen());
	}

	@Override
	public void onInitializeClient() {
		this.config = Config.load();
		this.client = MinecraftClient.getInstance();
		InstaReset._instance = this;
	}

	public void openNextLevel() {
		PregeneratingPartialLevel level = this.pollFromPregeneratingLevelQueue();
		this.currentLevel.set(level);
		String fileName = level.fileName;
		LevelInfo levelInfo = level.levelInfo;
		GeneratorOptions generatorOptions = level.generatorOptions;
		this.client.method_29607(fileName, levelInfo, RegistryTracker.create(), generatorOptions);
	}

	public PregeneratingPartialLevel createPregeneratingPartialLevel() {
		// createLevel() (CreateWorldScreen.java:245)
		String levelName = this.generateLevelName();
		// this.client.method_29970(new SaveLevelScreen(new TranslatableText("createWorld.preparing")));
		// At this point in the original code datapacks are copied into the world folder. (CreateWorldScreen.java:247)

		// Shorter version of original code (MoreOptionsDialog.java:80 & MoreOptionsDialog.java:302)
		GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions().withHardcore(false, OptionalLong.empty());
		LevelInfo levelInfo = new LevelInfo(levelName, GameMode.SURVIVAL, false, this.config.settings.difficulty, false, new GameRules(), DataPackSettings.SAFE_MODE);
		this.config.settings.resetCounter++;
		try {
			config.writeChanges();
		} catch (IOException e) {
			e.printStackTrace();
		}
		String seedHash = Hashing.sha256().hashString(String.valueOf(generatorOptions.getSeed()), StandardCharsets.UTF_8).toString();
		String fileName = this.generateFileName(levelName, seedHash);
		return startLevelPregeneration(fileName, levelInfo, generatorOptions);
	}

	private PregeneratingPartialLevel startLevelPregeneration(String fileName, LevelInfo levelInfo, GeneratorOptions generatorOptions) {
		ServerWorldProperties serverWorldProperties = this.saveProperties.getMainWorldProperties();
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
		return new PregeneratingPartialLevel(fileName, levelInfo, generatorOptions, pregenerationThread, );
	}

	private String generateLevelName() {
		String levelName = String.format("Speedrun #%d", this.config.settings.resetCounter);
		return levelName;
	}

	private String generateFileName(String levelName, String seedHash) {
		String fileName;
		// Ensure its a unique name (CreateWorldScreen.java:222)
		try {
			fileName = FileNameUtil.getNextUniqueName(this.client.getLevelStorage().getSavesDirectory(), levelName, "");
		} catch (Exception var4) {
			try {
				fileName = FileNameUtil.getNextUniqueName(this.client.getLevelStorage().getSavesDirectory(), levelName, "");
			} catch (Exception var3) {
				throw new RuntimeException("Could not create save folder", var3);
			}
		}
		return String.format("%s - %s", fileName, seedHash);
	}

	@Environment(EnvType.CLIENT)
	static class WorldCreationException extends RuntimeException {
		public WorldCreationException(Throwable throwable) {
			super(throwable);
		}
	}
}

