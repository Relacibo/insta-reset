package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.interfaces.FlushableServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {
    @Final
    @Shadow
    private ServerWorld world;

    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerChunkManager;save(Z)V"))
    private void closeRedirect(ServerChunkManager serverChunkManager, boolean flush) {
        if (((FlushableServer) world.getServer()).shouldFlush()) {
            serverChunkManager.save(true);
        }
    }
}
