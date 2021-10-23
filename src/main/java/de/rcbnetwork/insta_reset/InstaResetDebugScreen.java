package de.rcbnetwork.insta_reset;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InstaResetDebugScreen {
    private final InstaReset instaReset;

    private final AtomicReference<List<String>> debugMessage = new AtomicReference<>(Collections.emptyList());
    private final AtomicInteger debugMessageLineCount = new AtomicInteger(0);

    public Stream<String> getDebugMessage() {
        return debugMessage.get().stream();
    }

    public int getDebugMessageLineCount() {
        return debugMessageLineCount.get();
    }

    protected void setDebugMessage(List<String> list) {
        debugMessage.set(list);
        debugMessageLineCount.set(list.size());
    }

    public InstaResetDebugScreen(InstaReset instaReset) {
        this.instaReset = instaReset;
    }

    public void render(MatrixStack matrices, TextRenderer textRenderer, int width, int height) {
        AtomicInteger iAtom = new AtomicInteger(0);
        int lineCount = getDebugMessageLineCount();
        getDebugMessage().forEach((str) -> {
            int i = iAtom.get();
            int y = height - (lineCount - i) * (textRenderer.fontHeight + 1);
            int x = width - textRenderer.getWidth(str) - 1;
            boolean isCurrentLevel = i == 6;
            int color = isCurrentLevel ? 0xaab00baa : 0x55ffffff;
            int backgroundColor = isCurrentLevel ? 0x12345678 : 0;
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(Tessellator.getInstance().getBuffer());
            textRenderer.draw(new LiteralText(str), x, y, color, false, matrices.peek().getModel(), immediate, true, backgroundColor, 0xf000f0);
            immediate.draw();
            iAtom.set(i + 1);
        });
    }

    protected void updateDebugMessage() {
        updateDebugMessage(new Date().getTime());
    }

    protected synchronized void updateDebugMessage(long now) {
        String nextLevelString = this.instaReset.getCurrentLevel() != null ? createDebugStringFromLevelInfo(instaReset.getCurrentLevel()) : "-";
        String currentTimeStamp = Long.toHexString(now);
        String configString = String.format("%s;%s", createSettingsDebugString(), currentTimeStamp);

        Stream<String> futureStrings = instaReset.getPregeneratingLevelFutureQueueStream().map(this::createDebugStringFromLevelFuture);
        Stream<String> levelStrings = instaReset.getPregeneratingLevelQueueStream().map(this::createDebugStringFromLevelInfo);
        Stream<String> pastStrings = instaReset.getPastLevelInfoQueueStream().map(this::createDebugStringFromPastLevel).map((s) -> String.format("%s", s));
        List<String> message = Stream.of(
                Stream.of(configString),
                pastStrings,
                Stream.of(nextLevelString),
                levelStrings,
                futureStrings
        ).flatMap(stream -> stream).collect(Collectors.toList());
        this.setDebugMessage(message);
    }

    private String createDebugStringFromPastLevel(InstaReset.PastLevelInfo info) {
        return String.format("%s:%s", info.hash.substring(0, 10), Long.toHexString(info.creationTimeStamp));
    }

    private String createDebugStringFromLevelInfo(Pregenerator.PregeneratingLevel level) {
        return String.format("%s:%s", level.hash.substring(0, 10), Long.toHexString(level.creationTimeStamp));
    }

    private String createDebugStringFromLevelFuture(InstaReset.PregeneratingLevelFuture future) {
        return Long.toHexString(future.expectedCreationTimeStamp);
    }

    private String createSettingsDebugString() {
        Config.Settings settings = instaReset.getSettings();
        return String.format("%s:%d:%d", settings.difficulty.getName().charAt(0), settings.resetCounter, settings.expireAfterSeconds);
    }
}
