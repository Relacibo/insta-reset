package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addStopRunButtonMixin(CallbackInfo info) {
        if (!InstaReset.instance().isModRunning()) {
            return;
        }
        Text text = new LiteralText("Stop Resets & Quit");

        //Add button to disable the auto reset and quit
        this.addButton(new ButtonWidget(0, this.height - 20, 100, 20, text, (buttonWidget) -> {
            InstaReset.instance().stop();
            buttonWidget.active = false;
        }));
    }
}
