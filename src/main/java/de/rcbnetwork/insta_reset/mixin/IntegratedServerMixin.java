package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.interfaces.InitiallyHibernatingServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Mixin(IntegratedServer.class)
public class IntegratedServerMixin implements InitiallyHibernatingServer {
    @Shadow
    boolean paused;

    @Unique
    AtomicReference<Boolean> hibernating = new AtomicReference<>(InstaReset.instance().isModRunning());

    @Override
    public boolean isHibernating() {
        return hibernating.get();
    }

    @Override
    public void wakeUp() {
        hibernating.set(false);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    void extendInit(CallbackInfo info) {
        this.paused = hibernating.get();
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/server/integrated/IntegratedServer;getProfiler()Lnet/minecraft/util/profiler/Profiler;", shift = At.Shift.BEFORE))
    void addHibernation(BooleanSupplier shouldKeepTicking, CallbackInfo info) {
        this.paused = hibernating.get() || this.paused;
    }
}
