package de.rcbnetwork.insta_reset;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;

import com.google.common.collect.Queues;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.Difficulty;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Config {
    private static final String FILE_NAME = "insta-reset.json";
    private Path configPath;


    public final Settings settings = new Settings();

    public String getFileName() {
        return this.configPath.getFileName().toString();
    }

    public final Queue<InstaReset.PastLevelInfo> pastLevelInfoQueue = Queues.newConcurrentLinkedQueue();

   static class DifficultyTypeAdapter extends TypeAdapter<Difficulty> {
        @Override
        public void write(JsonWriter out, Difficulty value) throws IOException {
            out.value(value.getName());
        }

        @Override
        public Difficulty read(JsonReader in) throws IOException {
            if (!in.peek().equals(JsonToken.STRING)) {
                return null;
            }
            return Difficulty.byName(in.nextString());
        }
    }

    public static class Settings {
        public Difficulty difficulty = Difficulty.EASY;
        public int resetCounter = 0;
        public int numberOfPregenLevels = 2;
        public int numberOfPregenLevelsInStandby = 0;
        public int expireAfterSeconds = 280;
        public int cleanupIntervalSeconds = 60;
        public boolean showStatusList = true;
        public int timeBetweenStartsMs = 1000;
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapter(Difficulty.class, new DifficultyTypeAdapter())
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load() {
        Config config;
        Path configPath = getConfigPath(FILE_NAME);
        File file = configPath.toFile();

        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                config = GSON.fromJson(reader, Config.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            }

            config.sanitize();
        } else {
            config = new Config();
        }
        config.configPath = configPath;
        try {
            config.writeChanges();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }
        return config;
    }

    public String getSettingsJSON() {
        return GSON.toJson(this.settings);
    }

    private void sanitize() {
        if (this.settings.difficulty == null) {
            this.settings.difficulty = Difficulty.EASY;
        }
        if (this.settings.numberOfPregenLevels < 1) {
            this.settings.numberOfPregenLevels = 1;
        }
        if (this.settings.numberOfPregenLevelsInStandby < 0) {
            this.settings.numberOfPregenLevelsInStandby = 0;
        } else if (this.settings.numberOfPregenLevelsInStandby > this.settings.numberOfPregenLevels) {
            this.settings.numberOfPregenLevelsInStandby = this.settings.numberOfPregenLevels;
        }
        if (this.settings.expireAfterSeconds <= 0) {
            this.settings.expireAfterSeconds = -1;
        }
        if (this.settings.cleanupIntervalSeconds <= 0) {
            this.settings.cleanupIntervalSeconds = -1;
        }
        int size = this.pastLevelInfoQueue.size();
        while (size > 5) {
            this.pastLevelInfoQueue.remove();
            size--;
        }
    }

    public void writeChanges() throws IOException {
        Path dir = this.configPath.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        try (FileWriter writer = new FileWriter(this.configPath.toFile())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            throw new RuntimeException("Could not save configuration file", e);
        }
    }

    private static Path getConfigPath(String name) {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve(name);
    }
}
