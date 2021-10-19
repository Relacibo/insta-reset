package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin extends Screen {
    protected LevelLoadingScreenMixin(Text title) {
        super(title);
    }

    @Unique
    Supplier<Iterator<String>> instaResetStatusTextSupplier = Collections::emptyIterator;

    @Inject(method = "<init>", at = @At("TAIL"))
    public void extendInit(CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        instaResetStatusTextSupplier = InstaReset.instance()::getDebugMessage;
    }

    @Inject(method = "render", at = @At("TAIL"))
    public void extendRender(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo info) {
        AtomicReference<Float> yAtom = new AtomicReference<>(1.0f);
        instaResetStatusTextSupplier.get().forEachRemaining((str) -> {
            float y = yAtom.get();
            this.textRenderer.draw(matrices, str, 0.0f, y, 0xffffff);
            yAtom.set(y + 10);
        });
    }
}
