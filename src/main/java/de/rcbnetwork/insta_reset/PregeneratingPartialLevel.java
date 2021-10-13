package de.rcbnetwork.insta_reset;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import org.apache.commons.lang3.NotImplementedException;

public final class PregeneratingPartialLevel {

    public final String fileName;

    public final LevelInfo levelInfo;

    public final GeneratorOptions generatorOptions;

    public final Thread serverThread;

    public final ServerWorld serverWorld;

    public final SimpleRegistry<DimensionOptions> simpleRegistry;

    public PregeneratingPartialLevel(String fileName, LevelInfo levelInfo, GeneratorOptions generatorOptions, Thread serverThread, ServerWorld serverWorld, SimpleRegistry<DimensionOptions> simpleRegistry) {
        this.fileName = fileName;
        this.levelInfo = levelInfo;
        this.generatorOptions = generatorOptions;
        this.serverThread = serverThread;
        this.serverWorld = serverWorld;
        this.simpleRegistry = simpleRegistry;
    }
}
