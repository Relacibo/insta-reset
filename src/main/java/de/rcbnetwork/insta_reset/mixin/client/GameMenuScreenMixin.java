package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public class GameMenuScreenMixin extends Screen {

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Redirect(method = "initWidgets", at = @At(value = "NEW", target = "net/minecraft/client/gui/widget/ButtonWidget", ordinal = 7))
    private ButtonWidget createExitButton(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
        return new ButtonWidget(x, y, width, height, message, (b) -> {
            long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
            // Is shift pressed
            boolean shiftPressed = InputUtil.isKeyPressed(windowHandle, 340) || InputUtil.isKeyPressed(windowHandle, 344);
            InstaReset.instance().setCurrentServerShouldFlush(shiftPressed);
            if (!shiftPressed) {
                InstaReset.instance().stop();
            }
            onPress.onPress(b);
        });
    }
}
