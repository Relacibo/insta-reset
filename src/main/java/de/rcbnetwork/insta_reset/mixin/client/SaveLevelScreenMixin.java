package de.rcbnetwork.insta_reset.mixin.client;

import de.rcbnetwork.insta_reset.InstaReset;
import de.rcbnetwork.insta_reset.InstaResetDebugScreen;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Mixin(SaveLevelScreen.class)
public class SaveLevelScreenMixin extends Screen {

    protected SaveLevelScreenMixin(Text title) {
        super(title);
    }

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
}
