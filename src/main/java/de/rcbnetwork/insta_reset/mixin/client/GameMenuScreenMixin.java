package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.InstaResetDebugScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Unique
    Text QUIT_MESSAGE = new TranslatableText("menu.returnToMenu");

    @Unique
    Text RESET_MESSAGE = new LiteralText("Reset without saving");

    @Unique
    ButtonWidget QUIT_BUTTON;

    @Unique
    boolean shiftDownLast = false;

    @Unique
    InstaResetDebugScreen instaResetDebugScreen;

    @Inject(method = "<init>", at = @At("TAIL"))
    public void extendInit(CallbackInfo info) {
        instaResetDebugScreen = InstaReset.instance().debugScreen;
    }

    @Inject(method = "render", at = @At("TAIL"))
    public void extendRender(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo info) {
        instaResetDebugScreen.render(matrices, this.textRenderer, this.width, this.height);
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
