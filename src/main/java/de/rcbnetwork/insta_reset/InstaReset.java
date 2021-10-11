package de.rcbnetwork.insta_reset;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class InstaReset implements ClientModInitializer {
	MinecraftClient client;

	@Override
	public void onInitializeClient() {

	}

	private void createLevel() {
		this.client.method_29970(new SaveLevelScreen(new TranslatableText("createWorld.preparing")));
		if (this.method_29696()) {
			GeneratorOptions generatorOptions = this.moreOptionsDialog.getGeneratorOptions(this.hardcore);
			LevelInfo levelInfo2;
			if (generatorOptions.isDebugWorld()) {
				GameRules gameRules = new GameRules();
				((GameRules.BooleanRule)gameRules.get(GameRules.DO_DAYLIGHT_CYCLE)).set(false, (MinecraftServer)null);
				levelInfo2 = new LevelInfo(this.levelNameField.getText().trim(), GameMode.SPECTATOR, false, Difficulty.PEACEFUL, true, gameRules, DataPackSettings.SAFE_MODE);
			} else {
				levelInfo2 = new LevelInfo(this.levelNameField.getText().trim(), this.currentMode.defaultGameMode, this.hardcore, this.field_24290, this.cheatsEnabled && !this.hardcore, this.gameRules, this.field_25479);
			}

			this.client.method_29607(this.saveDirectoryName, levelInfo2, this.moreOptionsDialog.method_29700(), generatorOptions);
		}
	}

	private boolean method_29696() {
		if (this.field_25477 != null) {
			try {
				LevelStorage.Session session = this.client.getLevelStorage().createSession(this.saveDirectoryName);
				Throwable var2 = null;

				try {
					Stream<Path> stream = Files.walk(this.field_25477);
					Throwable var4 = null;

					try {
						Path path = session.getDirectory(WorldSavePath.DATAPACKS);
						Files.createDirectories(path);
						/*stream.filter((pathx) -> {
							return !pathx.equals(this.field_25477);
						}).forEach((path2) -> {
							method_29687(this.field_25477, path, path2);
						});*/
					} catch (Throwable var29) {
						var4 = var29;
						throw var29;
					} finally {
						if (stream != null) {
							if (var4 != null) {
								try {
									stream.close();
								} catch (Throwable var28) {
									var4.addSuppressed(var28);
								}
							} else {
								stream.close();
							}
						}

					}
				} catch (Throwable var31) {
					var2 = var31;
					throw var31;
				} finally {
					if (session != null) {
						if (var2 != null) {
							try {
								session.close();
							} catch (Throwable var27) {
								var2.addSuppressed(var27);
							}
						} else {
							session.close();
						}
					}

				}
			} catch (CreateWorldScreen.WorldCreationException | IOException var33) {
				field_25480.warn((String)"Failed to copy datapacks to world {}", (Object)this.saveDirectoryName, (Object)var33);
				SystemToast.method_29627(this.client, this.saveDirectoryName);
				this.client.openScreen(this.parent);
				this.removeTemporaryFile();
				return false;
			}

			this.removeTemporaryFile();
		}

		return true;
	}

	private void removeTemporaryFile() {
		if (this.field_25477 != null) {
			try {
				Stream<Path> stream = Files.walk(this.field_25477);
				Throwable var2 = null;

				try {
					stream.sorted(Comparator.reverseOrder()).forEach((path) -> {
						try {
							Files.delete(path);
						} catch (IOException var2) {
							field_25480.warn((String)"Failed to remove temporary file {}", (Object)path, (Object)var2);
						}

					});
				} catch (Throwable var12) {
					var2 = var12;
					throw var12;
				} finally {
					if (stream != null) {
						if (var2 != null) {
							try {
								stream.close();
							} catch (Throwable var11) {
								var2.addSuppressed(var11);
							}
						} else {
							stream.close();
						}
					}

				}
			} catch (IOException var14) {
				field_25480.warn((String)"Failed to list temporary dir {}", (Object)this.field_25477);
			}

			this.field_25477 = null;
		}

	}
}

