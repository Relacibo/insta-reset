package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
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
        if (instaReset.isModRunning()) {
            instaReset.openNextLevel();
            return;
        }
        // Add new button for starting auto resets.
        int x = this.width / 2 - 124;
        int y = this.height / 4 + 48;
        this.addButton(new ButtonWidget(x, y, 20, 20, new LiteralText("IR"), (buttonWidget) -> {
            this.client.method_29970(new SaveLevelScreen(new TranslatableText("InstaReset - Preparing levels")));
            instaReset.start();
            instaReset.openNextLevel();
        }));
    }
}
