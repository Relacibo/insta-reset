package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;

@Mixin(OptionsScreen.class)
public class OptionsScreenMixin extends Screen {
    @Unique
    final Text QUIT_BUTTON_MESSAGE = new LiteralText("Quit to main menu");
    @Unique
    final Text STOP_RESETTING_MESSAGE = new LiteralText("Stop Resetting");
    @Unique
    final Text START_RESETTING_MESSAGE = new LiteralText("Start Resetting");

    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void extendInit(CallbackInfo info) {
        boolean modIsRunning = InstaReset.instance().isModRunning();

        //Add button to disable the auto reset and quit
        ButtonWidget quitButton = this.addButton(new ButtonWidget(0, this.height - 44, 100, 20, QUIT_BUTTON_MESSAGE, (buttonWidget) -> {
            InstaReset.instance().stop();
            buttonWidget.active = false;
            this.client.world.disconnect();
            this.client.disconnect(new SaveLevelScreen(new TranslatableText("menu.savingLevel")));
            this.client.openScreen(new TitleScreen());
        }));
        Text toggleButtonText = modIsRunning ? STOP_RESETTING_MESSAGE : START_RESETTING_MESSAGE;

        this.addButton(new ButtonWidget(0, this.height - 20, 100, 20, toggleButtonText, (buttonWidget) -> {
            boolean isRunning = InstaReset.instance().isModRunning();
            if (isRunning) {
                InstaReset.instance().stop();
            } else {
                InstaReset.instance().start();
            }
            isRunning = InstaReset.instance().isModRunning();
            quitButton.active = isRunning;
            buttonWidget.setMessage(isRunning ? STOP_RESETTING_MESSAGE : START_RESETTING_MESSAGE);
        }));
    }
}
