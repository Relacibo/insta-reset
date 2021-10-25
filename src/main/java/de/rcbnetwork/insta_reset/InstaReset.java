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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
    private long lastScheduledWorldCreation = 0;
    private long lastWorldCreation = 0;
    private long lastReset = 0;
    private AtomicInteger resetCounterTemp;

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
        resetCounterTemp = new AtomicInteger(config.settings.resetCounter);
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
            // TODO: get if already executed or executing
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
        PregeneratingLevelFuture future = pregeneratingLevelFutureQueue.poll();
        Pregenerator.PregeneratingLevel next;
        if (future == null) {
            next = createLevel(createUUID(), this.config.settings.resetCounter);
        } else {
            String uuid = future.uuid;
            // Prevent scheduled expire task from executing
            pregeneratingLevelExpireFutureQueue.stream()
                    .filter(f -> f.uuid.equals(uuid))
                    .forEach(f -> {
                        f.future.cancel(true);
                        pregeneratingLevelExpireFutureQueue.remove(f);
                    });
            try {
                next = future.future.get();
            } catch (Exception e) {
                log(Level.ERROR, e.getMessage());
                e.printStackTrace();
                this.stop();
                return;
            }
        }
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
        int numToCreate = maxLevels - pregeneratingLevelFutureQueue.size();
        for (int i = 0; i < numToCreate; i++) {
            // Schedule all creations at least timeBetweenStartsMs apart from each other
            long delayInMs = baseDelay + (long) i * timeBetweenStarts;
            this.lastScheduledWorldCreation = now + delayInMs;
            final String uuid = createUUID();
            ScheduledFuture<Pregenerator.PregeneratingLevel> scheduledFuture = scheduleLevelCreation(uuid, delayInMs);
            PregeneratingLevelFuture future = new PregeneratingLevelFuture(uuid, this.lastScheduledWorldCreation, scheduledFuture);
            this.pregeneratingLevelFutureQueue.offer(future);
            log(String.format("Scheduled level with uuid %s for %s", uuid, this.lastScheduledWorldCreation));
        }
    }

    private ScheduledFuture<Pregenerator.PregeneratingLevel> scheduleLevelCreation(String uuid, long delayInMs) {
        return service.schedule(() -> {
            int levelNumber = resetCounterTemp.incrementAndGet();
            // Will be executed after it was inserted in the main thread
            Pregenerator.PregeneratingLevel level = createLevel(uuid, levelNumber);
            log(String.format("Started Server: %s", level.hash));
            // Schedule the expiration of this level
            if (config.settings.expireAfterSeconds != -1) {
                ScheduledFuture<?> expf = scheduleLevelExpiration(level, level.expirationTimeStamp - new Date().getTime());
                this.pregeneratingLevelExpireFutureQueue.offer(new PregeneratingLevelExpireFuture(uuid, expf));
            }
            debugScreen.updateDebugMessage();
            return level;
        }, delayInMs, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduleLevelExpiration(Pregenerator.PregeneratingLevel level, long delayInMs) {
        return service.schedule(() -> {
            String uuid = level.uuid;
            // Remove self from queue
            this.pregeneratingLevelExpireFutureQueue.removeIf(f -> f.uuid.equals(uuid));
            boolean removed = this.pregeneratingLevelFutureQueue.removeIf( f -> f.uuid.equals(uuid));
            if (!removed) {
                return;
            }
            resetCounterTemp.decrementAndGet();
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
            if (!future.future.isDone()) {
                // Only cancel creation task, expiration task isn't scheduled yet.
                future.future.cancel(false);
            }
            future = pregeneratingLevelFutureQueue.poll();
        }
        this.lastScheduledWorldCreation = this.lastWorldCreation;
        refillQueueScheduled();
        debugScreen.updateDebugMessage();
    }

    private Pregenerator.PregeneratingLevel createLevel(String uuid, int levelNumber) {
        try {
            Pregenerator.PregeneratingLevel level = Pregenerator.pregenerate(uuid, levelNumber, client, config.settings.expireAfterSeconds, config.settings.difficulty);
            this.lastWorldCreation = level.creationTimeStamp;
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
        public final ScheduledFuture<Pregenerator.PregeneratingLevel> future;

        public PregeneratingLevelFuture(String uuid, long expectedCreationTimeStamp, ScheduledFuture<Pregenerator.PregeneratingLevel> future) {
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
        public final String uuid;
        public final ScheduledFuture<?> future;

        public PregeneratingLevelExpireFuture(String uuid, ScheduledFuture<?> future) {
            this.uuid = uuid;
            this.future = future;
        }
    }
}

