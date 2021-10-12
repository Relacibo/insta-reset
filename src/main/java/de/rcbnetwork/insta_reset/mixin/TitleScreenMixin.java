package de.rcbnetwork.insta_reset.mixin;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    public void onInit(CallbackInfo info) {
        if (this.client.isDemo()) {
            return;
        }
        InstaReset instaReset = InstaReset.instance();
        if (instaReset.isModActive()) {
            InstaReset.instance().createLevel();
        }
        // Add new button for starting auto resets.
        int y = this.height / 4 + 48;
        this.addButton(new ButtonWidget(this.width / 2 - 124, y, 20, 20, LiteralText.EMPTY, (buttonWidget) -> {
            InstaReset.instance().createLevel();
        }));
    }
}
