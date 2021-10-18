package de.rcbnetwork.insta_reset;

import com.google.common.hash.Hashing;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.*;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class InstaReset implements ClientModInitializer {
    private static InstaReset _instance;

    public static InstaReset instance() {
        return _instance;
    }

    public static final String MOD_NAME = "InstaReset";
    public static final String MOD_ID = "insta-reset";
    private static final Logger logger = LogManager.getLogger();
    private Config config;
    private MinecraftClient client;
    private Queue<AtomicReference<Pregenerator.PregeneratingLevel>> pregeneratingLevelQueue = new LinkedList<>();
    private AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
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

    public Pregenerator.PregeneratingLevel getCurrentLevel() {
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

    private AtomicReference<Pregenerator.PregeneratingLevel> pollFromPregeneratingLevelQueue() {
        Pregenerator.PregeneratingLevel level = tryCreatePregeneratingLevel();
        if (level == null) {
            this.stop();
            log(Level.ERROR, "Cannot generate new level");
            return null;
        }
        this.pregeneratingLevelQueue.offer(new AtomicReference<>(level));
        log(Level.INFO, String.format("Queued level: %s", level.hash));
        return pregeneratingLevelQueue.poll();
    }

    public void startAsync() {
        Thread thread = new Thread(() -> {
            start();
        });
        thread.start();
    }

    public void start() {
        log(Level.ERROR, "Initializing Server Queue!");
        this.setState(InstaResetState.STARTING);
        Pregenerator.PregeneratingLevel level = this.currentLevel.get();
        if (level == null) {
            level = tryCreatePregeneratingLevel();
            if (level == null) {
                this.stop();
                log(Level.ERROR, "Cannot generate new level");
                return;
            }
            openLevel(new AtomicReference<>(level));
        } else {
            ((FlushableServer) (level.server)).setShouldFlush(true);
        }
        for (int i = 0; i < this.config.settings.numberOfPregeneratingLevels; i++) {
            level = tryCreatePregeneratingLevel();
            if (level == null) {
                this.stop();
                log(Level.ERROR, "Cannot generate new level");
                return;
            }
            this.pregeneratingLevelQueue.offer(new AtomicReference<>(level));
        }
        this.setState(InstaResetState.RUNNING);
    }

    public void stopAsync() {
        Thread thread = new Thread(() -> {
            stop();
        });
        thread.start();
    }

    public void stop() {
        this.setState(InstaResetState.STOPPING);
        AtomicReference<Pregenerator.PregeneratingLevel> reference = pregeneratingLevelQueue.poll();
        while (reference != null) {
            stopLevelAsync(reference);
            reference = pregeneratingLevelQueue.poll();
        }
        Pregenerator.PregeneratingLevel level = currentLevel.get();
        if (level != null) {
            ((FlushableServer) (level.server)).setShouldFlush(false);
        }
        this.setState(InstaResetState.STOPPED);
    }

    private void stopLevelAsync(AtomicReference<Pregenerator.PregeneratingLevel> reference) {
        Thread thread = new Thread(() -> {
            try {
                Pregenerator.uninitialize(this.client, reference.get());
            } catch (IOException e) {
                log(Level.ERROR, "Cannot close Session");
            } finally {
                reference.set(null);
            }
        });
        thread.start();
    }

    public void openLevel(AtomicReference<Pregenerator.PregeneratingLevel> reference) {
        this.client.method_29970(new SaveLevelScreen(new LiteralText("InstaReset - Opening next level")));
        this.currentLevel = reference;
        Pregenerator.PregeneratingLevel level = reference.get();
        log(Level.INFO, String.format("Opening level: %s", level.hash));
        String fileName = level.fileName;
        LevelInfo levelInfo = level.levelInfo;
        GeneratorOptions generatorOptions = level.generatorOptions;
        this.client.method_29607(fileName, levelInfo, RegistryTracker.create(), generatorOptions);
        this.config.settings.resetCounter++;
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void openNextLevel() {
        this.client.method_29970(new SaveLevelScreen(new TranslatableText("InstaReset - Opening next level")));
        AtomicReference<Pregenerator.PregeneratingLevel> reference = this.pollFromPregeneratingLevelQueue();
        if (reference == null) {
            this.stop();
            this.client.method_29970(null);
            return;
        }
        openLevel(reference);
    }

    public Pregenerator.PregeneratingLevel tryCreatePregeneratingLevel() {
        for (int failCounter = 0; true; failCounter++) {
            try {
                return createPregeneratingLevel();
            } catch (Exception e) {
                if (failCounter == 5) {
                    return null;
                }
                failCounter++;
            }
        }
    }

    public Pregenerator.PregeneratingLevel createPregeneratingLevel() throws IOException, ExecutionException, InterruptedException {
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
        String levelName = String.format("Speedrun #%d", this.config.settings.resetCounter + pregeneratingLevelQueue.size() + 1);
        return levelName;
    }

    private String generateFileName(String levelName, String seedHash) {
        String fileName = String.format("%s - %s", levelName, seedHash.substring(0, 7));
        // Ensure its a unique name (CreateWorldScreen.java:222)
        try {
            fileName = FileNameUtil.getNextUniqueName(this.client.getLevelStorage().getSavesDirectory(), fileName, "");
        } catch (Exception var4) {
            try {
                fileName = FileNameUtil.getNextUniqueName(this.client.getLevelStorage().getSavesDirectory(), fileName, "");
            } catch (Exception var3) {
                throw new RuntimeException("Could not create save folder", var3);
            }
        }
        return fileName;
    }

    @Environment(EnvType.CLIENT)
    static class WorldCreationException extends RuntimeException {
        public WorldCreationException(Throwable throwable) {
            super(throwable);
        }
    }

    public static void log(String message){
        logger.log(Level.INFO, "["+MOD_NAME+"] " + message);
    }
    public static void log(Level level, String message){
        logger.log(level, "["+MOD_NAME+"] " + message);
    }
}

