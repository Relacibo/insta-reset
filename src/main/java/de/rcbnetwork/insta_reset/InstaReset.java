package de.rcbnetwork.insta_reset;

import com.google.common.collect.Queues;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collector;
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
    public final InstaResetDebugScreen debugScreen = new InstaResetDebugScreen(this);
    private Config config;
    private MinecraftClient client;
    private final ScheduledExecutorService service = Executors.newScheduledThreadPool(0);
    private final Queue<RunningLevelExpireFuture> runningLevelExpirationQueue = Queues.newConcurrentLinkedQueue();
    private final Queue<RunningLevelFuture> runningLevelQueue = Queues.newConcurrentLinkedQueue();
    private final Set<String> queuedRunningLevelUUIDs = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Pregenerator.RunningLevel> currentLevel = new AtomicReference<>();
    private long lastScheduledWorldCreation = 0;
    private long lastWorldCreation = 0;
    private long lastReset = 0;
    private AtomicInteger resetCounterTemp;
    public final Collector<RunningLevelFuture, ?, Map<Boolean, List<RunningLevelFuture>>> PARTITION_BY_RUNNING_STATE = Collectors.partitioningBy(f -> this.isLevelRunning(f.uuid));

    public Pregenerator.RunningLevel getCurrentLevel() {
        return this.currentLevel.get();
    }

    public Config.Settings getSettings() {
        return config.settings;
    }

    public boolean isLevelRunning(String uuid) {
        return queuedRunningLevelUUIDs.contains(uuid);
    }

    public Stream<RunningLevelFuture> getRunningLevelFutureQueueStream() {
        return runningLevelQueue.stream();
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
        Pregenerator.RunningLevel level = this.currentLevel.get();
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
        this.lastReset = 0;
        this.lastScheduledWorldCreation = 0;
        this.lastWorldCreation = 0;
        openNextLevel();
    }

    public void stop() {
        log("Stopping!");
        this.setState(InstaResetState.STOPPING);
        // Try cancel schedule for future levels, stop running levels and remove scheduled expireTasks.
        RunningLevelFuture future = runningLevelQueue.poll();
        while (future != null) {
            String uuid = future.uuid;
            if (queuedRunningLevelUUIDs.contains(uuid)) {
                removeScheduledExpiration(uuid);
                try {
                    uninitializeLevelAsync(future.future.get());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                future.future.cancel(true);
            }
            future = runningLevelQueue.poll();
        }
        queuedRunningLevelUUIDs.clear();
        // Add last level to pastLevelInfoQueue, if it is not already in it
        Pregenerator.RunningLevel level = currentLevel.get();
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
        Pregenerator.RunningLevel pastLevel = this.currentLevel.get();
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
            rescheduleLevelCreations();
        }
        RunningLevelFuture future = runningLevelQueue.poll();
        Pregenerator.RunningLevel next;
        if (future == null) {
            next = createLevel(createUUID(), this.config.settings.resetCounter);
            lastScheduledWorldCreation = Math.max(this.lastScheduledWorldCreation, this.lastReset);
        } else {
            String uuid = future.uuid;
            queuedRunningLevelUUIDs.remove(uuid);
            removeScheduledExpiration(uuid);
            try {
                next = future.future.get();
            } catch (Exception e) {
                log(Level.ERROR, e.getMessage());
                e.printStackTrace();
                this.stop();
                return;
            }
        }
        assert next != null;
        this.currentLevel.set(next);
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.refillQueueScheduled();
        this.debugScreen.updateDebugMessage();
        log(String.format("Opening level: %s - %s", this.config.settings.resetCounter, next.hash));
        Pregenerator.foregroundLevel(client, next);
    }

    private void uninitializeLevelAsync(Pregenerator.RunningLevel level) {
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
        // make the delay timeBetweenStartsMs from last creation or if that's negative 0
        long baseDelay = lastScheduledWorldCreation - now + timeBetweenStarts;
        baseDelay = Math.max(0, baseDelay);
        int numToCreate = maxLevels - runningLevelQueue.size();
        for (int i = 0; i < numToCreate; i++) {
            // Schedule all creations at least timeBetweenStartsMs apart from each other
            long delayInMs = baseDelay + (long) i * timeBetweenStarts;
            this.lastScheduledWorldCreation = now + delayInMs;
            final String uuid = createUUID();
            ScheduledFuture<Pregenerator.RunningLevel> scheduledFuture = scheduleLevelCreation(uuid, delayInMs);
            this.runningLevelQueue.offer(new RunningLevelFuture(uuid, this.lastScheduledWorldCreation, scheduledFuture));
            log(String.format("Scheduled level with uuid %s for %s", uuid, this.lastScheduledWorldCreation));
        }
    }

    private ScheduledFuture<Pregenerator.RunningLevel> scheduleLevelCreation(String uuid, long delayInMs) {
        return service.schedule(() -> {
            queuedRunningLevelUUIDs.add(uuid);
            int levelNumber = resetCounterTemp.incrementAndGet();
            // Will be executed after it was inserted in the main thread
            Pregenerator.RunningLevel level = createLevel(uuid, levelNumber);
            log(String.format("Started Server: %s", level.hash));
            // Schedule the expiration of this level
            if (config.settings.expireAfterSeconds != -1) {
                ScheduledFuture<?> expf = scheduleLevelExpiration(level, level.expirationTimeStamp - new Date().getTime());
                this.runningLevelExpirationQueue.offer(new RunningLevelExpireFuture(uuid, expf));
            }
            // We have to schedule the debug message update, because level isn't yet returned, so we cannot just run it.
            service.schedule((Runnable) this.debugScreen::updateDebugMessage, 50, TimeUnit.MILLISECONDS);
            return level;
        }, delayInMs, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduleLevelExpiration(Pregenerator.RunningLevel level, long delayInMs) {
        return service.schedule(() -> {
            String uuid = level.uuid;
            boolean wasInQueue = queuedRunningLevelUUIDs.remove(uuid);
            // Remove self from queue
            this.runningLevelExpirationQueue.removeIf(f -> f.uuid.equals(uuid));
            boolean removedFromLevelQueue = this.runningLevelQueue.removeIf(f -> f.uuid.equals(uuid));
            if (!(wasInQueue && removedFromLevelQueue)) {
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

    private void removeScheduledExpiration(String uuid) {
        runningLevelExpirationQueue.stream()
                .filter(f -> f.uuid.equals(uuid))
                .forEach(f -> f.future.cancel(false));
        runningLevelExpirationQueue.removeIf(f -> f.uuid.equals(uuid));
    }

    private void rescheduleLevelCreations() {
        runningLevelQueue.stream().filter(f -> !this.isLevelRunning(f.uuid)).forEach(f -> {
            // Only cancel creation task, expiration task isn't scheduled yet.
            f.future.cancel(false);
            runningLevelQueue.remove(f);
        });
        this.lastScheduledWorldCreation = this.lastWorldCreation;
        refillQueueScheduled();
        debugScreen.updateDebugMessage();
    }

    private Pregenerator.RunningLevel createLevel(String uuid, int levelNumber) {
        try {
            Pregenerator.RunningLevel level = Pregenerator.pregenerate(uuid, levelNumber, client, config.settings.expireAfterSeconds, config.settings.difficulty);
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

    private boolean isInStandbyMode(long now) {
        return this.lastReset != 0 && now > this.lastReset + config.settings.expireAfterSeconds * 1000L;
    }

    public static void log(String message) {
        logger.log(Level.INFO, "[" + MOD_NAME + "] " + message);
    }

    public static void log(Level level, String message) {
        logger.log(level, "[" + MOD_NAME + "] " + message);
    }

    public static final class RunningLevelFuture {
        public final String uuid;
        public final long expectedCreationTimeStamp;
        public final ScheduledFuture<Pregenerator.RunningLevel> future;

        public RunningLevelFuture(String uuid, long expectedCreationTimeStamp, ScheduledFuture<Pregenerator.RunningLevel> future) {
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

    public static final class RunningLevelExpireFuture {
        public final String uuid;
        public final ScheduledFuture<?> future;

        public RunningLevelExpireFuture(String uuid, ScheduledFuture<?> future) {
            this.uuid = uuid;
            this.future = future;
        }
    }
}

