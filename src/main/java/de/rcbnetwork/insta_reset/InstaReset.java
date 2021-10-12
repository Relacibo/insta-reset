package de.rcbnetwork.insta_reset;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.FileNameUtil;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.CallbackI;

public class InstaReset implements ClientModInitializer {
	private static InstaReset _instance;
	public static InstaReset instance() {
		return _instance;
	}
	public static final String MOD_ID = "insta-reset";
	private Logger logger = LogManager.getLogger();
	private Config config;
	private MinecraftClient client;
	private boolean modActive = false;

	public boolean isModActive() {
		return this.modActive;
	}

	public void stop() {
		this.modActive = false;
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

	/***
	 * Modified version of CreateWorldScreen.java:245
	 */
	public void createLevel() {
		String levelName = this.generateLevelName();
		String fileName = this.generateFileName(levelName);
		// this.client.method_29970(new SaveLevelScreen(new TranslatableText("createWorld.preparing")));
		// At this point in the original code datapacks are copied into the world folder. (CreateWorldScreen.java:247)

		// Shorter version of original code (MoreOptionsDialog.java:80 & MoreOptionsDialog.java:302)
		GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions().withHardcore(false, OptionalLong.empty());
		LevelInfo levelInfo = new LevelInfo(levelName, GameMode.SURVIVAL, false, this.config.settings.difficulty, false, new GameRules(), DataPackSettings.SAFE_MODE);
		this.modActive = true;
		this.config.settings.resetCounter++;
		try {
			config.writeChanges();
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.client.method_29607(fileName, levelInfo, RegistryTracker.create(), generatorOptions);
	}

	private String generateLevelName() {
		String levelName = String.format("Speedrun #%d", this.config.settings.resetCounter);
		return levelName;
	}

	private String generateFileName(String levelName) {
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
		return fileName;
	}

	@Environment(EnvType.CLIENT)
	static class WorldCreationException extends RuntimeException {
		public WorldCreationException(Throwable throwable) {
			super(throwable);
		}
	}
}

