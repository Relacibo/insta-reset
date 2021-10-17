package de.rcbnetwork.insta_reset;

import com.google.common.hash.Hashing;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.*;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
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
	private Queue<AtomicReference<Pregenerator.PregeneratingPartialLevel>> pregeneratingLevelQueue = new LinkedList<>();
	private AtomicReference<Pregenerator.PregeneratingPartialLevel> currentLevel = new AtomicReference<>();
	private AtomicReference<InstaResetState> state = new AtomicReference<>(InstaResetState.STOPPED);

	private List<StateListener> stateListeners = new ArrayList<>();

	public void addStateListener(StateListener listener) {
		stateListeners.add(listener);
	}

	public void removeStateListener(StateListener listener) {
		stateListeners.remove(listener);
	}

	private void setState(InstaResetState state) {
		InstaResetState oldState = this.state.get();
		if (this.state.get() == state) {
			return;
		}
		InstaResetStateChangedEvent event = new InstaResetStateChangedEvent(oldState, state);
		stateListeners.forEach((listener) -> {
			listener.update(event);
		});
		this.state.set(state);
	}

	public enum InstaResetState {
		STARTING,
		RUNNING,
		STOPPING,
		STOPPED
	}

	public interface StateListener {
		void update(InstaResetStateChangedEvent event);
	}

	public static final class InstaResetStateChangedEvent {
		public final InstaResetState oldState;
		public final InstaResetState newState;

		public InstaResetStateChangedEvent(InstaResetState oldState, InstaResetState newState) {
			this.oldState = oldState;
			this.newState = newState;
		}
	}

	public Pregenerator.PregeneratingPartialLevel getCurrentLevel() {
		return this.currentLevel.get();
	}

	public boolean isModRunning() {
		return state.get() == InstaResetState.RUNNING;
	}
	public InstaResetState getState() {
		return this.state.get();
	}

	@Override
	public void onInitializeClient() {
		this.config = Config.load();
		this.client = MinecraftClient.getInstance();
		InstaReset._instance = this;
	}

	private AtomicReference<Pregenerator.PregeneratingPartialLevel> pollFromPregeneratingLevelQueue() {
		/*Pregenerator.PregeneratingPartialLevel level = tryCreatePregeneratingLevel();
		if (level == null) {
			this.stop();
			logger.error("InstaReset - Cannot generate new level");
			return null;
		}
		this.pregeneratingLevelQueue.offer(level);*/
		return pregeneratingLevelQueue.poll();
	}

	public void async_start() {
		Thread thread = new Thread(() -> {
			start();
		});
		thread.start();
	}

	public void start() {
		this.setState(InstaResetState.STARTING);
		for (int i = 0; i < this.config.settings.numberOfPregeneratingLevels; i++) {
			Pregenerator.PregeneratingPartialLevel level = tryCreatePregeneratingLevel();
			if (level == null) {
				this.stop();
				logger.error("InstaReset - Cannot generate new level");
				return;
			}
			this.pregeneratingLevelQueue.offer(new AtomicReference<>(level));
		}
		this.setState(InstaResetState.RUNNING);
	}

	public void async_stop() {
		Thread thread = new Thread(() -> {
			stop();
		});
		thread.start();
	}
	public void stop() {
		this.setState(InstaResetState.STOPPING);
		AtomicReference<Pregenerator.PregeneratingPartialLevel> reference = pregeneratingLevelQueue.poll();
		while (reference != null) {
			stopLevel_async(reference);
			reference = pregeneratingLevelQueue.poll();
		}
		Pregenerator.PregeneratingPartialLevel level = currentLevel.get();
		if (level != null) {
			level.renderTaskQueue.set(null);
			level.worldGenerationProgressTracker.set(null);
			currentLevel.set(null);
		}
		this.setState(InstaResetState.STOPPED);
	}

	private void stopLevel_async(AtomicReference<Pregenerator.PregeneratingPartialLevel> reference) {
		Thread thread = new Thread(() -> {
			try {
				Pregenerator.uninitialize(reference.get());
			} catch (IOException e) {
				logger.error("InstaReset - Cannot close Session");
			} finally {
				reference.set(null);
			}
		});
		thread.start();
	}

	public void openNextLevel() {
		this.client.method_29970(new SaveLevelScreen(new TranslatableText("InstaReset - Opening next level")));
		AtomicReference<Pregenerator.PregeneratingPartialLevel> reference = this.pollFromPregeneratingLevelQueue();
		if (reference == null) {
			this.stop();
			this.client.method_29970(null);
			return;
		}
		this.config.settings.resetCounter++;
		try {
			config.writeChanges();
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.currentLevel = reference;
		Pregenerator.PregeneratingPartialLevel level = reference.get();
		String fileName = level.fileName;
		LevelInfo levelInfo = level.levelInfo;
		GeneratorOptions generatorOptions = level.generatorOptions;
		// Make directory visible again
		try {
			Files.setAttribute(Paths.get(fileName), "dos:hidden", false, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.client.method_29607(fileName, levelInfo, RegistryTracker.create(), generatorOptions);
	}

	public Pregenerator.PregeneratingPartialLevel tryCreatePregeneratingLevel() {
		for (int failCounter = 0; true; failCounter++) {
			try {
				return createPregeneratingPartialLevel();
			} catch (Exception e) {
				if (failCounter == 5) {
					return null;
				}
				failCounter++;
			}
		}
	}

	public Pregenerator.PregeneratingPartialLevel createPregeneratingPartialLevel() throws IOException, ExecutionException, InterruptedException {
		// createLevel() (CreateWorldScreen.java:245)
		String levelName = this.generateLevelName();
		// this.client.method_29970(new SaveLevelScreen(new TranslatableText("createWorld.preparing")));
		// At this point in the original code datapacks are copied into the world folder. (CreateWorldScreen.java:247)

		// Shorter version of original code (MoreOptionsDialog.java:80 & MoreOptionsDialog.java:302)
		GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions().withHardcore(false, OptionalLong.empty());
		LevelInfo levelInfo = new LevelInfo(levelName, GameMode.SURVIVAL, false, this.config.settings.difficulty, false, new GameRules(), DataPackSettings.SAFE_MODE);
		String seedHash = Hashing.sha256().hashString(String.valueOf(generatorOptions.getSeed()), StandardCharsets.UTF_8).toString();
		String fileName = this.generateFileName(levelName, seedHash);
		// MoreOptionsDialog:79+326
		RegistryTracker.Modifiable registryTracker = RegistryTracker.create();
		return Pregenerator.pregenerate(client, seedHash, fileName, generatorOptions, registryTracker, levelInfo);
	}

	private String generateLevelName() {
		String levelName = String.format("Speedrun #%d", this.config.settings.resetCounter + pregeneratingLevelQueue.size());
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

