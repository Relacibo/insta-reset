package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.*;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.file.Path;
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
    private ScheduledFuture<?> cleanupFuture;
    private final AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
    private long lastScheduledWorldCreation = 0;
    private boolean standbyMode = false;

    public Pregenerator.PregeneratingLevel getCurrentLevel() {
        return this.currentLevel.get();
    }

    private final AtomicReference<List<String>> debugMessage = new AtomicReference<>(Collections.emptyList());

    public Iterator<String> getDebugMessage() {
        return debugMessage.get().stream().iterator();
    }

    private final AtomicReference<InstaResetState> state = new AtomicReference<>(InstaResetState.STOPPED);

    private void setState(InstaResetState state) {
        this.state.set(state);
    }

    public enum InstaResetState {
        RUNNING,
        STOPPING,
        STOPPED
    }

    public boolean isModRunning() {
        return state.get() == InstaResetState.RUNNING;
    }

    public InstaResetState getState() {
        return this.state.get();
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
        log("Using InstaReset!");
        this.config = Config.load();
        log(config.getSettingsJSON());
        this.client = MinecraftClient.getInstance();
        InstaReset._instance = this;
    }

    public void start() {
        log("Starting!");
        this.setState(InstaResetState.RUNNING);
        openNextLevel();
    }

    public void stop() {
        log("Stopping!");
        this.setState(InstaResetState.STOPPING);
        this.debugMessage.set(Collections.emptyList());
        cancelScheduledCleanup();
        // Unload current running levels
        Pregenerator.PregeneratingLevel level = pregeneratingLevelQueue.poll();
        while (level != null) {
            uninitializeLevelAsync(level);
            level = pregeneratingLevelQueue.poll();
        }
        // Unload scheduled levels
        PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
        while (future != null) {
            boolean cancelled = future.future.cancel(false);
            if (!cancelled) {
                try {
                    future.future.get();
                    level = pregeneratingLevelQueue.poll();
                    while (level != null) {
                        uninitializeLevelAsync(level);
                        level = pregeneratingLevelQueue.poll();
                    }
                } catch (Exception e) {
                    log(Level.ERROR, String.format("Error stopping level: %s - %s", future.uuid, e.getMessage()));
                }
            }
            future = pregeneratingLevelFutureQueue.poll();
        }
        // Add last level to pastLevelInfoQueue, if it is not already in it
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
        this.config.settings.resetCounter++;
        if (this.config.settings.cleanupIntervalSeconds != -1) {
            cancelScheduledCleanup();
        }
        // Add last level to pastLevelInfoQueue
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
        // Look if there is a level available, otherwise create one and foreground it
        Pregenerator.PregeneratingLevel next = pregeneratingLevelQueue.poll();
        if (next == null) {
            // Cannot be expired!
            PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
            // Either there is no level scheduled or cancel next scheduled level (As we don't want to wait for it).
            // In both cases create a new one synchronously.
            // When cancel returns true, the task was being executed at the time of calling and
            // there is now a new level in the queue.
            // (We have to cancel the future, otherwise we may have two server initializations in one interval)
            boolean futureIsNull = future == null;
            if (futureIsNull || future.future.cancel(false)) {
                String uuid = futureIsNull ? createUUID() : future.uuid;
                createLevel(uuid);
            }
            next = pregeneratingLevelQueue.poll();
            assert next != null;
        }
        this.currentLevel.set(next);
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
        if (config.settings.expireAfterSeconds != -1) {
            int removed = this.removeExpiredLevelsFromQueue();
            if (removed > 0) {
                this.standbyMode = true;
            }
        }
        if (refill) {
            this.refillQueueScheduled();
        }
    }

    private int removeExpiredLevelsFromQueue() {
        long timestamp = new Date().getTime();
        return this.pregeneratingLevelQueue.stream().filter(level -> level.expirationTimeStamp < timestamp).map((elem) -> {
            log(String.format("Removing expired level: %s, %s", elem.hash, elem.expirationTimeStamp));
            this.pregeneratingLevelQueue.remove(elem);
            uninitializeLevelAsync(elem);
            return 1;
        }).reduce(0, Integer::sum);
    }

    private void uninitializeLevelAsync(Pregenerator.PregeneratingLevel level) {
        Thread thread = new Thread(() -> {
            uninitializeLevel(level);
        });
        thread.start();
    }

    private void uninitializeLevel(Pregenerator.PregeneratingLevel level) {
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
        long now = new Date().getTime();
        // make the delay timeBetweenStartsMs from last creation or if this is negative 0
        long baseDelay = lastScheduledWorldCreation - now + config.settings.timeBetweenStartsMs ;
        baseDelay = Math.max(0, baseDelay);
        for (int i = size; i < maxLevels; i++) {
            // Schedule all creations at least timeBetweenStartsMs apart from each other
            long delayInMs = baseDelay + (long) i * config.settings.timeBetweenStartsMs;
            this.lastScheduledWorldCreation = now + delayInMs;
            final String uuid = createUUID();
            ScheduledFuture<?> scheduledFuture = service.schedule(() -> createLevel(uuid), delayInMs, TimeUnit.MILLISECONDS);
            PregeneratingLevelFuture future = new PregeneratingLevelFuture(uuid, this.lastScheduledWorldCreation, scheduledFuture);
            this.pregeneratingLevelFutureQueue.offer(future);
            log(String.format("Scheduled level with uuid %s for %s", uuid, future.expectedCreationTimeStamp));
        }
    }

    private void createLevel(String uuid) {
        this.removeFutureWithUUID(uuid);
        int expireAfterSeconds = config.settings.expireAfterSeconds;
        int number = this.config.settings.resetCounter + pregeneratingLevelQueue.size() + pregeneratingLevelFutureQueue.size();
        Path savesDirectory = this.client.getLevelStorage().getSavesDirectory();
        Pregenerator.PregeneratingLevel level;
        try {
            level = Pregenerator.pregenerate(client, savesDirectory, number, expireAfterSeconds, this.config.settings.difficulty);
        } catch (Exception e) {
            log(Level.ERROR, String.format("Pregeneration Initialization failed! %s", uuid));
            e.printStackTrace();
            return;
        }
        log(String.format("Started Server: %s", level.hash));
        this.pregeneratingLevelQueue.offer(level);
        if (config.settings.showStatusList) {
            updateDebugMessage();
        }
    }

    private String createUUID() {
        return UUID.randomUUID().toString();
    }

    private void removeFutureWithUUID(String uuid) {
        this.pregeneratingLevelFutureQueue.removeIf(future -> future.uuid.equals(uuid));
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
        updateDebugMessage(new Date().getTime());
    }

    private synchronized void updateDebugMessage(long now) {
        String nextLevelString = this.currentLevel.get() != null ? createDebugStringFromLevelInfo(this.currentLevel.get()) : "-";
        nextLevelString = String.format("%s <-", nextLevelString);
        String currentTimeStamp = String.format("Time: %s", Long.toHexString(now));
        String configString = createSettingsDebugString();

        Stream<String> futureStrings = pregeneratingLevelFutureQueue.stream().map(this::createDebugStringFromLevelFuture);
        Stream<String> levelStrings = pregeneratingLevelQueue.stream().map(this::createDebugStringFromLevelInfo);
        Stream<String> pastStrings = this.config.pastLevelInfoQueue.stream().map(this::createDebugStringFromPastLevel).map((s) -> String.format("%s", s));
        this.debugMessage.set(Stream.of(pastStrings,
                Stream.of(nextLevelString),
                levelStrings,
                futureStrings,
                Stream.of(currentTimeStamp),
                Stream.of(configString)
        ).flatMap(stream -> stream).collect(Collectors.toList()));
    }

    private String createDebugStringFromPastLevel(PastLevelInfo info) {
        return String.format("%s:%s", info.hash.substring(0, 10), Long.toHexString(info.creationTimeStamp));
    }

    private String createDebugStringFromLevelInfo(Pregenerator.PregeneratingLevel level) {
        return String.format("%s:%s", level.hash.substring(0, 10), Long.toHexString(level.creationTimeStamp));
    }

    private String createDebugStringFromLevelFuture(PregeneratingLevelFuture future) {
        return String.format("Scheduled:%s", Long.toHexString(future.expectedCreationTimeStamp));
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
        private final String uuid;
        public final long expectedCreationTimeStamp;
        public final ScheduledFuture<?> future;

        public PregeneratingLevelFuture(String uuid, long expectedCreationTimeStamp, ScheduledFuture<?> future) {
            this.uuid = uuid;
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

