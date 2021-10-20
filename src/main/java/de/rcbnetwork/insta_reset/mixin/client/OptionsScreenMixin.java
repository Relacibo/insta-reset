package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.realms.RealmsBridge;
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
    final Text STOP_RESETTING_MESSAGE = new LiteralText("Stop Resetting");
    @Unique
    final Text START_RESETTING_MESSAGE = new LiteralText("Start Resetting");

    @Unique
    ButtonWidget toggleModButton;

    @Unique
    InstaReset.StateListener instaResetStateListener;

    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void extendInit(CallbackInfo info) {
        toggleModButton = this.addButton(new ButtonWidget(0, this.height - 20, 100, 20, LiteralText.EMPTY, (buttonWidget) -> {
            buttonWidget.active = false;
            if (InstaReset.instance().isModRunning()) {
                InstaReset.instance().stop();
                ;
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
                toggleModButton.active = true;
                break;
            case STARTING:
            case STOPPING:
                toggleModButton.active = false;
                break;
            case STOPPED:
                toggleModButton.active = true;
                break;
        }
        toggleModButton.setMessage(message);
    }
}
