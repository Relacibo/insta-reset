package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.gen.*;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
    public final InstaResetDebugScreen debugScreen = new InstaResetDebugScreen(this);
    private Config config;
    private MinecraftClient client;
    private final ScheduledExecutorService service = Executors.newScheduledThreadPool(0);
    private final Queue<PregeneratingLevelExpireFuture> pregeneratingLevelExpireFutureQueue = Queues.newConcurrentLinkedQueue();
    private final Queue<PregeneratingLevelFuture> pregeneratingLevelFutureQueue = Queues.newConcurrentLinkedQueue();
    private final Queue<Pregenerator.PregeneratingLevel> pregeneratingLevelQueue = Queues.newConcurrentLinkedQueue();
    private final AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
    private long lastScheduledWorldCreation = 0;
    private long lastWorldCreation = 0;
    private long lastReset = 0;

    public Pregenerator.PregeneratingLevel getCurrentLevel() {
        return this.currentLevel.get();
    }

    public Config.Settings getSettings() {
        return config.settings;
    }

    public Stream<Pregenerator.PregeneratingLevel> getPregeneratingLevelQueueStream() {
        return pregeneratingLevelQueue.stream();
    }

    public Stream<PregeneratingLevelFuture> getPregeneratingLevelFutureQueueStream() {
        return pregeneratingLevelFutureQueue.stream();
    }

    public Stream<PastLevelInfo> getPastLevelInfoQueueStream() {
        return config.pastLevelInfoQueue.stream();
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
        // Cancel schedule for expire tasks
        PregeneratingLevelExpireFuture expireFuture = pregeneratingLevelExpireFutureQueue.poll();
        while (expireFuture != null) {
            expireFuture.future.cancel(false);
            expireFuture = pregeneratingLevelExpireFutureQueue.poll();
        }
        // Try cancel schedule for future levels
        PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
        while (future != null) {
            future.future.cancel(false);
            future = pregeneratingLevelFutureQueue.poll();
        }
        // Unload current loaded levels
        Pregenerator.PregeneratingLevel level = pregeneratingLevelQueue.poll();
        while (level != null) {
            uninitializeLevelAsync(level);
            level = pregeneratingLevelQueue.poll();
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
        this.debugScreen.setDebugMessage(Collections.emptyList());
        this.setState(InstaResetState.STOPPED);
    }

    public void openNextLevel() {
        long now = new Date().getTime();
        boolean wasInStandbyMode = isInStandbyMode(now);
        // Add last level to pastLevelInfoQueue
        Pregenerator.PregeneratingLevel pastLevel = this.currentLevel.get();
        if (pastLevel != null) {
            this.config.pastLevelInfoQueue.offer(new PastLevelInfo(pastLevel.hash, pastLevel.creationTimeStamp));
            if (this.config.pastLevelInfoQueue.size() > 5) {
                this.config.pastLevelInfoQueue.remove();
            }
        }
        this.config.settings.resetCounter++;
        this.lastReset = now;
        // If the mod was in standby mode then the scheduled world creations will be too far apart from each other.
        // So reschedule with small increments.
        if (wasInStandbyMode) {
            rescheduleWorldCreation();
        }
        Pregenerator.PregeneratingLevel next = pregeneratingLevelQueue.poll();
        if (next == null) {
            PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
            // Either there is no level scheduled or cancel next scheduled level (As we don't want to wait for it).
            // In both cases create a new one synchronously.
            // When cancel returns true, the task was being executed at the time of calling.
            if (future == null || future.future.cancel(false)) {
                next = createLevel(this.config.settings.resetCounter);
            } else {
                next = pregeneratingLevelQueue.poll();
            }
            assert next != null;
        }
        String hash = next.hash;
        // Prevent scheduled expire task (if present) from executing
        // Even if it runs it shouldn't do anything bad.
        pregeneratingLevelExpireFutureQueue.stream()
                .filter(f -> f.hash.equals(hash))
                .forEach(f -> f.future.cancel(true));
        this.currentLevel.set(next);
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.refillQueueScheduled();
        this.debugScreen.updateDebugMessage();
        this.openCurrentLevel();
    }

    private void uninitializeLevelAsync(Pregenerator.PregeneratingLevel level) {
        Thread thread = new Thread(() -> {
            try {
                log(String.format("Removing Level: %s", level.hash));
                Pregenerator.uninitialize(this.client, level);
            } catch (IOException e) {
                log(Level.ERROR, "Cannot close Session");
            }
        });
        thread.start();
    }

    void refillQueueScheduled() {
        long now = new Date().getTime();
        int size = pregeneratingLevelQueue.size() + pregeneratingLevelFutureQueue.size();
        boolean standByMode = isInStandbyMode(now);
        int maxLevels = standByMode ?
                this.config.settings.numberOfPregenLevelsStandby :
                this.config.settings.numberOfPregenLevels;
        int timeBetweenStarts = standByMode ?
                config.settings.timeBetweenStartsMsStandby :
                config.settings.timeBetweenStartsMs;
        // make the delay timeBetweenStartsMs from last creation or if this is negative 0
        long baseDelay = lastScheduledWorldCreation - now + timeBetweenStarts;
        baseDelay = Math.max(0, baseDelay);
        for (int i = 0; i < maxLevels - size; i++) {
            // Schedule all creations at least timeBetweenStartsMs apart from each other
            long delayInMs = baseDelay + (long) i * timeBetweenStarts;
            this.lastScheduledWorldCreation = now + delayInMs;
            final String uuid = createUUID();
            ScheduledFuture<?> scheduledFuture = scheduleLevelCreation(uuid, delayInMs);
            PregeneratingLevelFuture future = new PregeneratingLevelFuture(uuid, this.lastScheduledWorldCreation, scheduledFuture);
            this.pregeneratingLevelFutureQueue.offer(future);
            log(String.format("Scheduled level with uuid %s for %s", uuid, this.lastScheduledWorldCreation));
        }
    }

    private ScheduledFuture<?> scheduleLevelCreation(String uuid, long delayInMs) {
        return service.schedule(() -> {
            int levelNumber = this.config.settings.resetCounter + pregeneratingLevelQueue.size() + 1;
            Pregenerator.PregeneratingLevel level = createLevel(levelNumber);
            // Will be executed after it was inserted in the main thread
            this.removeFutureWithUUID(uuid);
            log(String.format("Started Server: %s", level.hash));
            this.pregeneratingLevelQueue.offer(level);
            // Schedule the expiration of this level
            if (config.settings.expireAfterSeconds != -1) {
                ScheduledFuture<?> expf = scheduleLevelExpiration(level, level.expirationTimeStamp - new Date().getTime());
                this.pregeneratingLevelExpireFutureQueue.offer(new PregeneratingLevelExpireFuture(level.hash, expf));
            }
            debugScreen.updateDebugMessage();
        }, delayInMs, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduleLevelExpiration(Pregenerator.PregeneratingLevel level, long delayInMs) {
        return service.schedule(() -> {
            // Remove self from queue
            this.pregeneratingLevelExpireFutureQueue.removeIf(f -> f.hash.equals(level.hash));
            boolean removed = this.pregeneratingLevelQueue.remove(level);
            if (!removed) {
                return;
            }
            log(String.format("Removing expired level: %s, %s", level.hash, level.expirationTimeStamp));
            try {
                Pregenerator.uninitialize(this.client, level);
            } catch (IOException e) {
                log(Level.ERROR, "Cannot close Session");
            }
            refillQueueScheduled();
            debugScreen.updateDebugMessage();
        }, delayInMs, TimeUnit.MILLISECONDS);
    }

    private void rescheduleWorldCreation() {
        PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
        while (future != null) {
            // Only cancel creation task, expiration task isn't scheduled yet.
            future.future.cancel(false);
            future = pregeneratingLevelFutureQueue.poll();
        }
        this.lastScheduledWorldCreation = this.lastWorldCreation;
        refillQueueScheduled();
        debugScreen.updateDebugMessage();
    }

    private Pregenerator.PregeneratingLevel createLevel(int levelNumber) {
        try {
            Pregenerator.PregeneratingLevel level = Pregenerator.pregenerate(client, levelNumber, config.settings.expireAfterSeconds, config.settings.difficulty);
            this.lastWorldCreation = new Date().getTime();
            return level;
        } catch (Exception e) {
            log(Level.ERROR, String.format("Pregeneration Initialization failed! %s", levelNumber));
            e.printStackTrace();
            return null;
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
        log(String.format("Opening level: %s - %s", this.config.settings.resetCounter, level.hash));
        String fileName = level.fileName;
        LevelInfo levelInfo = level.levelInfo;
        GeneratorOptions generatorOptions = level.generatorOptions;
        RegistryTracker.Modifiable registryTracker = level.registryTracker;
        // CreateWorldScreen.java:258
        this.client.method_29607(fileName, levelInfo, registryTracker, generatorOptions);
    }

    private boolean isInStandbyMode(long now) {
        return now > this.lastReset + config.settings.expireAfterSeconds * 1000L;
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

    public static final class PregeneratingLevelExpireFuture {
        public final String hash;
        public final ScheduledFuture<?> future;

        public PregeneratingLevelExpireFuture(String hash, ScheduledFuture<?> future) {
            this.hash = hash;
            this.future = future;
        }
    }
}

