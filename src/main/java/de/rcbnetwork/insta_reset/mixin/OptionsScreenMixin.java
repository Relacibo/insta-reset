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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public class OptionsScreenMixin extends Screen {
    @Unique
    final Text QUIT_BUTTON_MESSAGE = new LiteralText("Quit to main menu");
    @Unique
    final Text STOP_RESETTING_MESSAGE = new LiteralText("Stop Resetting");
    @Unique
    final Text START_RESETTING_MESSAGE = new LiteralText("Start Resetting");

    @Unique
    ButtonWidget quitButton;

    @Unique
    ButtonWidget toggleModButton;

    @Unique
    InstaReset.StateListener instaResetStateListener;

    @Shadow
    Screen parent;

    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void extendInit(CallbackInfo info) {
        //Add button to disable the auto reset and quit
        quitButton = this.addButton(new ButtonWidget(0, this.height - 44, 100, 20, QUIT_BUTTON_MESSAGE, (buttonWidget) -> {
            this.client.disconnect(new SaveLevelScreen(new TranslatableText("menu.savingLevel")));
            InstaReset.instance().stop();
            this.client.world.disconnect();
            this.client.openScreen(new TitleScreen());
        }));

        toggleModButton = this.addButton(new ButtonWidget(0, this.height - 20, 100, 20, new LiteralText(""), (buttonWidget) -> {
            if (InstaReset.instance().isModRunning()) {
                InstaReset.instance().stopAsync();;
            } else {
                InstaReset.instance().startAsync();
            }
        }));
        updateButtonsFromState(InstaReset.instance().getState());
        instaResetStateListener = (event) -> {
            updateButtonsFromState(event.newState);
        };
        InstaReset.instance().addStateListener(this.instaResetStateListener);
    }

    @Inject(method = "removed", at = @At("RETURN"))
    private void extendRemoved(CallbackInfo info) {
        InstaReset.instance().removeStateListener(this.instaResetStateListener);
    }

    @Unique
    void updateButtonsFromState(InstaReset.InstaResetState state) {
        Text message = state == InstaReset.InstaResetState.RUNNING ? STOP_RESETTING_MESSAGE : START_RESETTING_MESSAGE;
        switch (state) {
            case RUNNING:
                quitButton.active = true;
                toggleModButton.active = true;
                break;
            case STARTING:
            case STOPPING:
                quitButton.active = false;
                toggleModButton.active = false;
                break;
            case STOPPED:
                quitButton.active = false;
                toggleModButton.active = true;
                break;
        }
        toggleModButton.setMessage(message);
    }
}
