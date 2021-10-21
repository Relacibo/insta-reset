package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import com.google.common.hash.Hashing;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.*;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class InstaReset implements ClientModInitializer {
    private static InstaReset _instance;
    private ScheduledFuture<?> cleanupFuture;

    public static InstaReset instance() {
        return _instance;
    }

    public static final String MOD_NAME = "InstaReset";
    public static final String MOD_ID = "insta-reset";
    private static final Logger logger = LogManager.getLogger();
    private Config config;
    private MinecraftClient client;
    private final ScheduledExecutorService service = Executors.newScheduledThreadPool(0);
    private final Queue<PregeneratingLevelFuture> pregeneratingLevelFutureQueue = Queues.newConcurrentLinkedQueue();
    private final Queue<Pregenerator.PregeneratingLevel> pregeneratingLevelQueue = Queues.newConcurrentLinkedQueue();

    private AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
    private PregeneratingLevelFuture currentLevelFuture = null;

    private boolean standbyMode = false;

    public Pregenerator.PregeneratingLevel getCurrentLevel() {
        return this.currentLevel.get();
    }

    private List<String> debugMessage = Collections.emptyList();

    public Iterator<String> getDebugMessage() {
        return debugMessage.stream().iterator();
    }

    private final AtomicReference<InstaResetState> state = new AtomicReference<>(InstaResetState.STOPPED);

    private void setState(InstaResetState state) {
        this.state.set(state);
    }

    public enum InstaResetState {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    public boolean isModRunning() {
        return state.get() == InstaResetState.STARTING || state.get() == InstaResetState.RUNNING;
    }

    public InstaResetState getState() {
        return this.state.get();
    }

    public boolean isCurrentServerShouldFlush() {
        Pregenerator.PregeneratingLevel level = this.currentLevel.get();
        if (level == null) {
            return false;
        }
        return ((FlushableServer) level.server).shouldFlush();
    }

    public void setCurrentServerShouldFlush(boolean shouldFlush) {
        Pregenerator.PregeneratingLevel level = this.currentLevel.get();
        if (level == null) {
            return;
        }
        ((FlushableServer) level.server).setShouldFlush(shouldFlush);
    }

    @Override
    public void onInitializeClient() {
        this.config = Config.load();
        this.client = MinecraftClient.getInstance();
        InstaReset._instance = this;
    }

    public void startAsync() {
        Thread thread = new Thread(this::start);
        thread.start();
    }

    public void start() {
        log("Starting!");
        log(config.getSettingsJSON());
        this.setState(InstaResetState.STARTING);
        Pregenerator.PregeneratingLevel level = this.currentLevel.get();
        if (client.getNetworkHandler() == null) {
            this.setState(InstaResetState.RUNNING);
            openNextLevel();
        } else {
            this.setState(InstaResetState.RUNNING);
            refillQueueScheduled();
        }
    }

    public void stopAsync() {
        Thread thread = new Thread(this::stop);
        thread.start();
    }

    public void stop() {
        log("Stopping!");
        this.setState(InstaResetState.STOPPING);
        this.debugMessage = Collections.emptyList();
        PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
        while (future != null) {
            try {
                removeLevelAsync(future.future.get());
            } catch (Exception e) {
                log(Level.ERROR, String.format("Error stopping level: %s - %s", future.hash, e.getMessage()));
            }
            future = pregeneratingLevelFutureQueue.poll();
        }
        Pregenerator.PregeneratingLevel level = pregeneratingLevelQueue.poll();
        while (level != null) {
            removeLevelAsync(level);
            level = pregeneratingLevelQueue.poll();
        }
        level = currentLevel.get();
        if (level != null && (this.config.pastLevelInfoQueue.isEmpty() || !level.hash.equals(this.config.pastLevelInfoQueue.peek().hash))) {
            config.pastLevelInfoQueue.offer(new PastLevelInfo(level.hash, level.creationTimeStamp));
            if (this.config.pastLevelInfoQueue.size() > 5) {
                this.config.pastLevelInfoQueue.remove();
            }
        }
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.setState(InstaResetState.STOPPED);
    }

    public void openNextLevel() {
        if (this.config.settings.cleanupIntervalSeconds != -1) {
            cancelScheduledCleanup();
        }
        Pregenerator.PregeneratingLevel pastLevel = this.currentLevel.get();
        if (pastLevel != null) {
            this.config.pastLevelInfoQueue.offer(new PastLevelInfo(pastLevel.hash, pastLevel.creationTimeStamp));
            if (this.config.pastLevelInfoQueue.size() > 5) {
                this.config.pastLevelInfoQueue.remove();
            }
        }
        try {
            this.cleanup(false);
        } catch (Exception e) {
            e.printStackTrace();
            log(Level.ERROR, e.getMessage());
            this.stop();
            return;
        }
        this.standbyMode = false;
        Pregenerator.PregeneratingLevel next = pregeneratingLevelQueue.poll();
        if (next == null) {
            // Cannot be expired!
            PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
            if (future == null) {
                future = schedulePregenerationOfLevel(0);
                if (future == null) {
                    this.stop();
                    this.client.method_29970(null);
                }
            }
            this.currentLevelFuture = future;
            try {
                next = future.future.get();
            } catch (Exception e) {
                log(Level.ERROR, e.getMessage());
                this.stop();
                this.client.method_29970(null);
                return;
            }
        }
        this.currentLevelFuture = null;
        this.currentLevel = new AtomicReference<>(next);
        this.config.settings.resetCounter++;
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.refillQueueScheduled();
        if (config.settings.showStatusList) {
            this.updateDebugMessage();
        }
        if (this.config.settings.cleanupIntervalSeconds != -1) {
            this.scheduleCleanup();
        }
        this.openCurrentLevel();
    }

    private void cancelScheduledCleanup() {
        if (cleanupFuture == null) {
            return;
        }
        cleanupFuture.cancel(false);
    }

    private void scheduleCleanup() {
        this.cleanupFuture = service.scheduleAtFixedRate(() -> {
            try {
                this.cleanup(true);
            } catch (Exception e) {
                e.printStackTrace();
                log(Level.ERROR, e.getMessage());
                this.stop();
            }
        }, this.config.settings.cleanupIntervalSeconds, this.config.settings.cleanupIntervalSeconds, TimeUnit.SECONDS);
    }

    private synchronized void cleanup(boolean refill) throws Exception {
        this.transferFinishedFutures();
        if (config.settings.expireAfterSeconds != -1) {
            int removed = this.removeExpiredLevelsFromQueue();
            if (removed > 0) {
                this.standbyMode = true;
            }
        }
        if (refill) {
            this.refillQueueScheduled();
            if (config.settings.showStatusList) {
                this.updateDebugMessage();
            }
        }
    }

    private void transferFinishedFutures() throws Exception {
        PregeneratingLevelFuture peek = pregeneratingLevelFutureQueue.peek();
        while (peek != null && peek.future.isDone()) {
            Pregenerator.PregeneratingLevel level;
            level = pregeneratingLevelFutureQueue.poll().future.get();
            if (level == null) {
                throw new NullPointerException(String.format("Pregeneration failed, result was null: %s", peek.hash));
            }
            pregeneratingLevelQueue.offer(level);
            peek = pregeneratingLevelFutureQueue.peek();
        }
    }

    private int removeExpiredLevelsFromQueue() {
        long timestamp = new Date().getTime();
        return this.pregeneratingLevelQueue.stream().filter(level -> level.expirationTimeStamp < timestamp).map((elem) -> {
            log(String.format("Removing expired level: %s, %s", elem.hash, elem.expirationTimeStamp));
            this.pregeneratingLevelQueue.remove(elem);
            removeLevelAsync(elem);
            return 1;
        }).reduce(0, Integer::sum);
    }

    private void removeLevelAsync(Pregenerator.PregeneratingLevel level) {
        Thread thread = new Thread(() -> {
            removeLevel(level);
        });
        thread.start();
    }

    private void removeLevel(Pregenerator.PregeneratingLevel level) {
        try {
            log(String.format("Removing Level: %s", level.hash));
            Pregenerator.uninitialize(this.client, level);
        } catch (IOException e) {
            log(Level.ERROR, "Cannot close Session");
        }
    }

    void refillQueueScheduled() {
        int size = pregeneratingLevelQueue.size() + pregeneratingLevelFutureQueue.size();
        int maxLevels = standbyMode ? this.config.settings.numberOfPregenLevelsInStandby : this.config.settings.numberOfPregenLevels;
        for (int i = size; i < maxLevels; i++) {
            // Put each initialization a bit apart
            PregeneratingLevelFuture future = schedulePregenerationOfLevel((long) (i + 1) * config.settings.timeBetweenStartsMs);
            if (future == null) {
                this.stop();
                this.client.method_29970(null);
                return;
            }
            this.pregeneratingLevelFutureQueue.offer(future);
            log(String.format("Scheduled level %s for %s", future.hash, future.expectedCreationTimeStamp));
        }

    }

    public PregeneratingLevelFuture schedulePregenerationOfLevel(long delayInMs) {
        int expireAfterSeconds = config.settings.expireAfterSeconds;
        long expectedCreationTimeStamp = new Date().getTime() + delayInMs;
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

        // Schedule the task. If the level is needed right now, the delay is set to 0, otherwise there is a delay specified in the config file.
        Future<Pregenerator.PregeneratingLevel> future = service.schedule(() -> {
            try {
                return Pregenerator.pregenerate(client, seedHash, fileName, generatorOptions, registryTracker, levelInfo, expireAfterSeconds);
            } catch (Exception e) {
                log(Level.ERROR, String.format("Pregeneration Initialization failed: %s", seedHash));
                e.printStackTrace();
                return null;
            }
        }, delayInMs, TimeUnit.MILLISECONDS);
        return new PregeneratingLevelFuture(seedHash, expectedCreationTimeStamp, future);
    }

    private String generateLevelName() {
        return String.format("Speedrun #%d", this.config.settings.resetCounter + pregeneratingLevelQueue.size() + pregeneratingLevelFutureQueue.size() + 1);
    }

    private String generateFileName(String levelName, String seedHash) {
        String fileName = String.format("%s - %s", levelName, seedHash.substring(0, 10));
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

    public void openCurrentLevel() {
        Pregenerator.PregeneratingLevel level = currentLevel.get();
        log(String.format("Opening level: %s", level.hash));
        String fileName = level.fileName;
        LevelInfo levelInfo = level.levelInfo;
        GeneratorOptions generatorOptions = level.generatorOptions;
        RegistryTracker.Modifiable registryTracker = level.registryTracker;
        // CreateWorldScreen.java:258
        this.client.method_29607(fileName, levelInfo, registryTracker, generatorOptions);
    }

    private void updateDebugMessage() {
        long now = new Date().getTime();
        String nextLevelString = this.currentLevelFuture != null ?
                createDebugStringFromLevelFuture(this.currentLevelFuture) :
                createDebugStringFromLevelInfo(this.currentLevel.get());
        nextLevelString = String.format("%s <-", nextLevelString);
        String currentTimeStamp = String.format("Time: %s", Long.toHexString(now));
        String configString = createSettingsDebugString();

        Stream<String> futureStrings = pregeneratingLevelFutureQueue.stream().map(this::createDebugStringFromLevelFuture);
        Stream<String> levelStrings = pregeneratingLevelQueue.stream().map(this::createDebugStringFromLevelInfo);
        Stream<String> pastStrings = this.config.pastLevelInfoQueue.stream().map(this::createDebugStringFromPastLevel).map((s) -> String.format("%s", s));
        this.debugMessage = Stream.of(pastStrings,
                Stream.of(nextLevelString),
                levelStrings,
                futureStrings,
                Stream.of(currentTimeStamp),
                Stream.of(configString)
        ).flatMap(stream -> stream).collect(Collectors.toList());
    }

    private String createDebugStringFromPastLevel(PastLevelInfo info) {
        return String.format("%s:%sc", info.hash.substring(0, 10), Long.toHexString(info.creationTimeStamp));
    }

    private String createDebugStringFromLevelInfo(Pregenerator.PregeneratingLevel level) {
        return String.format("%s:%sc", level.hash.substring(0, 10), Long.toHexString(level.creationTimeStamp));
    }

    private String createDebugStringFromLevelFuture(PregeneratingLevelFuture future) {
        return String.format("%s:%ss", future.hash.substring(0, 10), Long.toHexString(future.expectedCreationTimeStamp));
    }

    private String createSettingsDebugString() {
        Difficulty difficulty = config.settings.difficulty;
        int resetCounter = config.settings.resetCounter;
        int expireAfterSeconds = config.settings.expireAfterSeconds;
        return String.format("%s:%d:%d", difficulty.getName().charAt(0), resetCounter, expireAfterSeconds);
    }

    public static void log(String message) {
        logger.log(Level.INFO, "[" + MOD_NAME + "] " + message);
    }

    public static void log(Level level, String message) {
        logger.log(level, "[" + MOD_NAME + "] " + message);
    }

    public static final class PregeneratingLevelFuture {
        public final String hash;
        public final long expectedCreationTimeStamp;
        public final Future<Pregenerator.PregeneratingLevel> future;

        public PregeneratingLevelFuture(String hash, long expectedCreationTimeStamp, Future<Pregenerator.PregeneratingLevel> future) {
            this.hash = hash;
            this.expectedCreationTimeStamp = expectedCreationTimeStamp;
            this.future = future;
        }
    }

    public static final class PastLevelInfo {
        public final String hash;
        public final long creationTimeStamp;

        public PastLevelInfo(String hash, long creationTimeStamp) {
            this.hash = hash;
            this.creationTimeStamp = creationTimeStamp;
        }
    }
}

