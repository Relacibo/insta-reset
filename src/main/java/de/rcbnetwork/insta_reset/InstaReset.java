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
import java.util.concurrent.atomic.AtomicLong;
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
    private final Random random = new Random();
    private final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
    private final Queue<PregeneratingLevelFuture> pregeneratingLevelFutureQueue = Queues.newConcurrentLinkedQueue();
    private final Queue<Pregenerator.PregeneratingLevel> pregeneratingLevelQueue = Queues.newConcurrentLinkedQueue();

    private AtomicReference<Pregenerator.PregeneratingLevel> currentLevel = new AtomicReference<>();
    private PregeneratingLevelFuture currentLevelFuture = null;


    public Pregenerator.PregeneratingLevel getCurrentLevel() {
        return this.currentLevel.get();
    }

    private final AtomicReference<String> debugMessage = new AtomicReference<>("");

    public String getDebugMessage() {
        return debugMessage.get();
    }

    private final AtomicReference<InstaResetState> state = new AtomicReference<>(InstaResetState.STOPPED);
    private final List<StateListener> stateListeners = new ArrayList<>();

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

    public static final class PregeneratingLevelFuture {
        public final String hash;
        public final long expectedCreationTimeStamp;
        public final Future<Pregenerator.PregeneratingLevel> future;

        public PregeneratingLevelFuture(String hash, long creationTimeStamp, Future<Pregenerator.PregeneratingLevel> future) {
            this.hash = hash;
            this.expectedCreationTimeStamp = creationTimeStamp;
            this.future = future;
        }
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

    private boolean isLevelExpired(Pregenerator.PregeneratingLevel level) {
        return level.expirationTimeStamp < new Date().getTime();
    }

    private void removeExpiredLevelsFromQueue() {
        this.pregeneratingLevelQueue.stream().filter((elem) -> isLevelExpired(elem)).forEach((elem) -> {
            this.pregeneratingLevelQueue.remove(elem);
            removeLevelAsync(elem);
        });
    }

    public void openNextLevel() {
        this.transferFinishedFutures();
        this.removeExpiredLevelsFromQueue();
        Pregenerator.PregeneratingLevel next = pregeneratingLevelQueue.poll();
        if (next == null) {
            // Cannot be expired, because only just finished initializing!
            PregeneratingLevelFuture future = pollNextLevelFuture();
            if (future == null) {
                future = createPregeneratingLevel(true);
                if (future == null) {
                    this.stop();
                    this.client.method_29970(null);
                }
            }
            this.currentLevelFuture = future;
            this.updateDebugMessage();
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
        this.updateDebugMessage();
        this.config.settings.resetCounter++;
        try {
            config.writeChanges();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.refillQueueScheduled();
        this.openCurrentLevel();
    }

    public void startAsync() {
        Thread thread = new Thread(this::start);
        thread.start();
    }

    public void start() {
        log("Starting!");
        this.setState(InstaResetState.STARTING);
        Pregenerator.PregeneratingLevel level = this.currentLevel.get();
        if (client.getNetworkHandler() == null) {
            openNextLevel();
        } else if (level != null) {
            ((FlushableServer) (level.server)).setShouldFlush(true);
            refillQueueScheduled();
        }
        this.setState(InstaResetState.RUNNING);
    }

    public void stopAsync() {
        Thread thread = new Thread(this::stop);
        thread.start();
    }

    public PregeneratingLevelFuture pollNextLevelFuture() {
        AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        AtomicReference<PregeneratingLevelFuture> next = new AtomicReference<>();
        pregeneratingLevelFutureQueue.forEach((future) -> {
            if (future.expectedCreationTimeStamp < min.get()) {
                min.set(future.expectedCreationTimeStamp);
                next.set(future);
            }
        });
        pregeneratingLevelFutureQueue.remove(next.get());
        return next.get();
    }

    public void stop() {
        log("Stopping!");
        this.setState(InstaResetState.STOPPING);
        PregeneratingLevelFuture future = pollNextLevelFuture();
        while (future != null) {
            try {
                removeLevelAsync(future.future.get());
            } catch (Exception e) {
                log(Level.ERROR, String.format("Error stopping level: %s - %s", future.hash, e.getMessage()));
            }
            future = pollNextLevelFuture();
        }
        Pregenerator.PregeneratingLevel level = pregeneratingLevelQueue.poll();
        while (level != null) {
            removeLevelAsync(level);
            level = pregeneratingLevelQueue.poll();
        }
        level = currentLevel.get();
        if (level != null) {
            ((FlushableServer) (level.server)).setShouldFlush(false);
        }
        this.setState(InstaResetState.STOPPED);
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
                createDebugStringFromLevel(this.currentLevel.get());
        nextLevelString = String.format("Next: %s", nextLevelString);

        Stream<String> futureStrings = pregeneratingLevelFutureQueue.stream().map(this::createDebugStringFromLevelFuture);
        Stream<String> levelStrings = pregeneratingLevelQueue.stream().map(this::createDebugStringFromLevel);
        List<String> completeList = Stream.concat(Stream.concat(futureStrings, levelStrings), Stream.of(nextLevelString)).collect(Collectors.toList());
        Collections.reverse(completeList);
        String res = String.join("\n", completeList);
        this.debugMessage.set(String.format("%s\n(Now: %s)", res, now));
    }

    private String createDebugStringFromLevel(Pregenerator.PregeneratingLevel level) {
        return String.format("Hash: %s, Creation: %d", level.hash.substring(0, 10), level.creationTimeStamp);
    }

    private String createDebugStringFromLevelFuture(PregeneratingLevelFuture future) {
        return String.format("Hash: %s, Scheduled creation: %d", future.hash.substring(0, 10), future.expectedCreationTimeStamp);
    }

    void refillQueueScheduled() {
        for (int i = pregeneratingLevelQueue.size(); i < this.config.settings.numberOfPregeneratingLevels; i++) {
            PregeneratingLevelFuture future = createPregeneratingLevel(false);
            if (future == null) {
                this.stop();
                this.client.method_29970(null);
                return;
            }
            this.pregeneratingLevelFutureQueue.offer(future);
            log(String.format("Scheduled level %s for %s", future.hash, future.expectedCreationTimeStamp));
        }

    }

    private void transferFinishedFutures() {
        pregeneratingLevelFutureQueue.stream().filter(f ->
                f.future.isDone()
        ).flatMap(f -> {
            try {
                pregeneratingLevelFutureQueue.remove(f);
                return Stream.of(f.future.get());
            } catch (Exception e) {
                log(Level.ERROR, String.format("Pregeneration failed: %s", f.hash));
                return Stream.empty();
            }
        }).sorted(Comparator.comparingLong(level -> level.creationTimeStamp)
        ).forEach(pregeneratingLevelQueue::offer);
    }

    public PregeneratingLevelFuture createPregeneratingLevel(boolean now) {
        int expireAfterSeconds = config.settings.expireAfterSeconds;
        // set delay to a number between 300 and 800 ms (or to 0 if now is set)
        long delay = !now ? random.nextInt(501) + 300 : 0;
        long expectedCreationTimeStamp = new Date().getTime() + delay;
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

        // Schedule the task. If the level is needed right now, the delay is set to 0, otherwise there is a random delay (for performance reasons).
        Future<Pregenerator.PregeneratingLevel> future = service.schedule(() -> {
            try {
                return Pregenerator.pregenerate(client, seedHash, fileName, generatorOptions, registryTracker, levelInfo, expireAfterSeconds);
            } catch (Exception e) {
                log(Level.ERROR, String.format("Pregeneration Initialization failed: %s", seedHash));
                e.printStackTrace();
                return null;
            }
        }, delay, TimeUnit.MILLISECONDS);
        return new PregeneratingLevelFuture(seedHash, expectedCreationTimeStamp, future);
    }

    private String generateLevelName() {
        String levelName = String.format("Speedrun #%d", this.config.settings.resetCounter + pregeneratingLevelQueue.size() + 1);
        return levelName;
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

    public static void log(String message) {
        logger.log(Level.INFO, "[" + MOD_NAME + "] " + message);
    }

    public static void log(Level level, String message) {
        logger.log(level, "[" + MOD_NAME + "] " + message);
    }
}

