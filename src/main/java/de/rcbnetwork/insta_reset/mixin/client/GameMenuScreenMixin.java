package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Mixin(GameMenuScreen.class)
public class GameMenuScreenMixin extends Screen {

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Unique
    Supplier<Iterator<String>> instaResetStatusTextSupplier = Collections::emptyIterator;

    @Unique
    Text QUIT_MESSAGE = new TranslatableText("menu.returnToMenu");

    @Unique
    Text RESET_MESSAGE = new LiteralText("Reset without saving");

    @Unique
    ButtonWidget QUIT_BUTTON;

    @Unique
    boolean shiftDownLast = false;

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

    @Inject(method = "tick", at = @At("TAIL"))
    public void extendTick(CallbackInfo info) {
        boolean shiftDown = hasShiftDown();
        if (shiftDown ^ shiftDownLast) {
            shiftDownLast = shiftDown;
            QUIT_BUTTON.setMessage(shiftDown ? RESET_MESSAGE : QUIT_MESSAGE);
        }
    }

    @Redirect(method = "initWidgets", at = @At(value = "NEW", target = "net/minecraft/client/gui/widget/ButtonWidget", ordinal = 7))
    private ButtonWidget createExitButton(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
         QUIT_BUTTON = new ButtonWidget(x, y, width, height, message, (b) -> {
            boolean shiftPressed = hasShiftDown();
            InstaReset.instance().setCurrentServerShouldFlush(hasShiftDown());
            if (!shiftPressed) {
                InstaReset.instance().stop();
            }
            onPress.onPress(b);
        });
         return QUIT_BUTTON;
    }
}
