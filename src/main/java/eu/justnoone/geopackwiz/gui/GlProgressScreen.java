package eu.justnoone.geopackwiz.gui;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.sync.SyncEngine;
import eu.justnoone.geopackwiz.sync.SyncState;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The pre-menu "boot" screen shown while packs are synced before the game's
 * initial load. It is a thin macOS-style progress screen — deep blue, a small
 * GeoRail logo, one thin progress line and almost nothing else. A small info
 * icon in the bottom-right corner opens the live log drawer. When the sync
 * fails, the background and logo fade to a deep red and the error fades in
 * with two actions: continue without the pack, or copy the error report
 * (which contains the live logs).
 *
 * The sync itself runs on a background thread ({@link SyncEngine}); this class
 * only renders and handles input, so the game never freezes on network I/O.
 */
public class GlProgressScreen {

    public static final ResourceLocation PRELOAD_GEORAIL_LOGO =
            new ResourceLocation(GeoPackWiz.MOD_ID, "textures/gui/georail_logo.png");

    // Palette — deep blue (normal) / deep red (failure); flat and dim.
    private static final int BG_TOP = 0xFF0B1030;
    private static final int BG_BOTTOM = 0xFF04071A;
    private static final int ERR_BG_TOP = 0xFF35080F;
    private static final int ERR_BG_BOTTOM = 0xFF100204;
    private static final int LOGO_COLOR = 0xFFA9C2FF;   // logo texture is white; tint GeoRail blue
    private static final int LOGO_ERROR_COLOR = 0xFFFF8D92;
    private static final int BAR_TRACK = 0x30FFFFFF;
    private static final int BAR_FILL = 0xFFDCE7FF;
    private static final int TEXT_MAIN = 0xFFE6EBFF;
    private static final int TEXT_DIM = 0xFF8E9BC4;

    private static final float ICON_SIZE = 24f;
    private static final float ICON_HIT = 40f;
    private static final float ICON_MARGIN = 14f;

    private final SyncEngine engine;
    private final SyncState state;

    private boolean logsOpen;
    private int logScrollUp;
    private boolean logFollow = true;

    private long errorSince = -1;
    private long copiedUntil;
    private boolean playWithoutChosen;
    private int renderFailures;

    private float mouseX;
    private float mouseY;
    private boolean mouseDownPrev;

    public GlProgressScreen(SyncEngine engine) {
        this.engine = engine;
        this.state = engine.state();
    }

    // ---- run loop ------------------------------------------------------------

    /** Blocks the render thread, rendering frames until the sync is resolved. */
    public void runBoot() {
        long lastFrame = System.nanoTime();
        try {
            while (true) {
                // Poll GLFW so the key / mouse state stays fresh. If a vanilla
                // input callback ever misbehaves mid-init, we cancel the boot
                // screen instead of letting it take the whole game down.
                try {
                    GLFW.glfwPollEvents();
                } catch (Throwable pollFailure) {
                    GeoPackWiz.LOGGER.warn("GeoPackWiz: input poll failed, disabling interactive boot screen.", pollFailure);
                    engine.cancel();
                    return;
                }
                if (Minecraft.getInstance().getWindow().shouldClose()) {
                    engine.cancel();
                    return;
                }

                handleInput();
                if (state.isAborted()) return; // user asked to skip

                boolean exitNow = state.succeeded() || state.isCancelled()
                        || (state.failed() && playWithoutChosen);
                try {
                    renderFrame();
                } catch (GlHelper.MinecraftStoppingException stop) {
                    throw stop;
                } catch (Throwable renderFailure) {
                    // One bad frame (driver hiccup, resolution change, ...) must
                    // never hang the boot screen — skip the frame, and if rendering
                    // keeps failing on this machine, skip the screen entirely so the
                    // game can still start without the pack.
                    renderFailures++;
                    if (renderFailures > 3) {
                        GeoPackWiz.LOGGER.warn("GeoPackWiz: boot screen could not render, continuing without it.", renderFailure);
                        engine.cancel();
                        return;
                    }
                }
                GlHelper.swapBuffer();
                if (exitNow) return;

                long now = System.nanoTime();
                long frameTime = now - lastFrame;
                lastFrame = now;
                long sleepMs = Math.max(0, 16_000_000L - frameTime) / 1_000_000L;
                if (sleepMs > 0) Thread.sleep(sleepMs);
            }
        } catch (GlHelper.MinecraftStoppingException ex) {
            engine.cancel();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            engine.cancel();
        }
    }

    // ---- input ---------------------------------------------------------------

    private void handleInput() {
        boolean mouseDown = GlHelper.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean clicked = mouseDown && !mouseDownPrev;
        mouseDownPrev = mouseDown;
        float[] cursor = GlHelper.cursorScaled();
        mouseX = cursor[0];
        mouseY = cursor[1];

        if (GlHelper.keyDown(GLFW.GLFW_KEY_ESCAPE)) {
            if (logsOpen) {
                logsOpen = false;
            } else if (state.failed()) {
                playWithoutChosen = true;
            } else {
                engine.cancel();
            }
            return; // one keypress per frame
        }

        if (logsOpen) {
            if (GlHelper.keyDown(GLFW.GLFW_KEY_UP)) {
                logScrollUp++;
                logFollow = false;
            } else if (GlHelper.keyDown(GLFW.GLFW_KEY_DOWN)) {
                logScrollUp = Math.max(0, logScrollUp - 1);
            } else if (GlHelper.keyDown(GLFW.GLFW_KEY_PAGE_UP)) {
                logScrollUp += 12;
                logFollow = false;
            } else if (GlHelper.keyDown(GLFW.GLFW_KEY_PAGE_DOWN)) {
                logScrollUp = Math.max(0, logScrollUp - 12);
            } else if (GlHelper.keyDown(GLFW.GLFW_KEY_HOME)) {
                logScrollUp = Integer.MAX_VALUE;
                logFollow = false;
            } else if (GlHelper.keyDown(GLFW.GLFW_KEY_END)) {
                logScrollUp = 0;
                logFollow = true;
            }
        }

        if (!clicked) return;

        if (within(infoIconBounds())) {
            logsOpen = !logsOpen;
            return;
        }
        if (state.failed()) {
            if (within(playButtonBounds())) {
                playWithoutChosen = true;
                return;
            }
            if (within(reportButtonBounds())) {
                copiedUntil = System.currentTimeMillis() + 2500;
                copyToClipboard(state.buildReport());
                return;
            }
        }
        if (logsOpen && !within(logDrawerBounds())) {
            logsOpen = false;
        }
    }

    private boolean within(float[] rect) {
        return mouseX >= rect[0] && mouseX <= rect[0] + rect[2]
                && mouseY >= rect[1] && mouseY <= rect[1] + rect[3];
    }

    private void copyToClipboard(String text) {
        try {
            GLFW.glfwSetClipboardString(Minecraft.getInstance().getWindow().getWindow(), text);
        } catch (Exception ignored) {
        }
    }

    // ---- layout --------------------------------------------------------------

    private static float logoSize() {
        return Math.min(GlHelper.getWidth(), GlHelper.getHeight()) * 0.20f;
    }

    private static float logoX() {
        return (GlHelper.getWidth() - logoSize()) / 2f;
    }

    private static float logoY() {
        return GlHelper.getHeight() * 0.40f - logoSize() / 2f;
    }

    private static float barY() {
        return logoY() + logoSize() + logoSize() * 0.26f;
    }

    private static float barWidth() {
        return logoSize() * 1.5f;
    }

    private static float barX() {
        return (GlHelper.getWidth() - barWidth()) / 2f;
    }

    private static float[] infoIconBounds() {
        float x = GlHelper.getWidth() - ICON_SIZE - ICON_MARGIN - (ICON_HIT - ICON_SIZE) / 2f;
        float y = GlHelper.getHeight() - ICON_SIZE - ICON_MARGIN - (ICON_HIT - ICON_SIZE) / 2f;
        return new float[] { x, y, ICON_HIT, ICON_HIT };
    }

    private static float[] playButtonBounds() {
        float w = Math.min(270, GlHelper.getWidth() * 0.30f);
        float h = 46;
        float total = w * 2 + 20;
        return new float[] { (GlHelper.getWidth() - total) / 2f, GlHelper.getHeight() * 0.70f, w, h };
    }

    private static float[] reportButtonBounds() {
        float[] play = playButtonBounds();
        float w = Math.min(240, GlHelper.getWidth() * 0.26f);
        return new float[] { play[0] + play[2] + 20, play[1], w, play[3] };
    }

    private static float[] logDrawerBounds() {
        float w = Math.min(GlHelper.getWidth() * 0.46f, 560);
        float h = Math.max(120, GlHelper.getHeight() - ICON_MARGIN * 3 - ICON_SIZE - 20);
        return new float[] { GlHelper.getWidth() - w - ICON_MARGIN, ICON_MARGIN + 8, w, h };
    }

    // ---- frame ---------------------------------------------------------------

    private void renderFrame() throws GlHelper.MinecraftStoppingException {
        float width = GlHelper.getWidth();
        float height = GlHelper.getHeight();
        GlHelper.setMatScaledPixel();
        GlHelper.clearScreen(0f, 0f, 0f);

        float fade = errorFadeFactor();
        int bgTop = GlHelper.lerpColor(BG_TOP, ERR_BG_TOP, fade);
        int bgBottom = GlHelper.lerpColor(BG_BOTTOM, ERR_BG_BOTTOM, fade);
        GlHelper.drawVerticalGradient(0, 0, width, height, bgTop, bgBottom);

        int logoColor = GlHelper.lerpColor(LOGO_COLOR, LOGO_ERROR_COLOR, fade);
        GlHelper.begin(PRELOAD_GEORAIL_LOGO);
        GlHelper.blit(logoX(), logoY(), logoSize(), logoSize(), 0, 0, 1, 1, logoColor);
        GlHelper.end();

        if (state.failed()) {
            renderError(fade);
        } else {
            renderWorking();
        }

        renderInfoIcon();
        if (logsOpen) renderLogDrawer();
    }

    /** Eased 0..1 fade-in used when the sync fails. */
    private float errorFadeFactor() {
        if (errorSince < 0) {
            if (state.failed()) errorSince = state.failedAtMillis();
            return 0f;
        }
        long elapsed = System.currentTimeMillis() - errorSince;
        float t = Math.min(1f, elapsed / 800f);
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    // ---- working state -------------------------------------------------------

    private void renderWorking() {
        float width = GlHelper.getWidth();
        float y = barY();

        // One thin progress line: track + quiet fill. No glow, no animation.
        GlHelper.beginColor();
        GlHelper.blit(barX() - 1, y - 1, barWidth() + 2, 3, BAR_TRACK);
        GlHelper.end();
        float frac = Math.max(0f, Math.min(1f, state.progress()));
        if (frac > 0.004f) {
            GlHelper.beginColor();
            GlHelper.blit(barX(), y, Math.max(1.5f, barWidth() * frac), 1.5f, BAR_FILL);
            GlHelper.end();
        }

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
        String stage = state.stageText();
        if (stage == null || stage.isEmpty()) stage = "Preparing ...";
        float stageW = GlHelper.getStringWidth(stage, 16);
        GlHelper.drawString((width - stageW) / 2f, y + 14, stageW + 4, 21, 16, stage, 0xE6E6EBFF, false, false);

        String sub = state.subText();
        if (sub != null && !sub.isEmpty()) {
            float subW = GlHelper.getStringWidth(sub, 13);
            GlHelper.drawString((width - subW) / 2f, y + 36, subW + 4, 18, 13, sub, 0xB48E9BC4, false, false);
        }
        GlHelper.end();

        // Small live per-pack rows (progress for the parallel downloads).
        List<String> rows = state.packRows();
        if (!rows.isEmpty()) {
            float rowY = y + 64;
            GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
            int shown = 0;
            for (String row : rows) {
                if (shown >= 5) break;
                GlHelper.drawString(barX(), rowY, barWidth(), 17, 13, row, rowColor(row), false, true);
                rowY += 19;
                shown++;
            }
            GlHelper.end();
        }

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
        String hint = "Press ESC to skip and play without the pack";
        float hintW = GlHelper.getStringWidth(hint, 13);
        GlHelper.drawString((width - hintW) / 2f, GlHelper.getHeight() - 30, hintW + 4, 17, 13, hint, 0x66E6EBFF, false, false);
        GlHelper.end();
    }

    // ---- error state ---------------------------------------------------------

    private void renderError(float fade) {
        if (fade <= 0.003f) return;
        float width = GlHelper.getWidth();

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);

        String title = "The GeoRail pack could not be loaded";
        float titleSize = 25;
        float titleW = GlHelper.getStringWidth(title, titleSize);
        GlHelper.drawString((width - titleW) / 2f, barY() + 4, titleW + 4, 33, titleSize, title,
                GlHelper.lerpColor(0x00000000, 0xFFFFD7D9, fade), false, false);

        String message = shortMessage(state.failure());
        if (message.isEmpty()) message = "Check your internet connection and try again later.";
        float msgSize = 15;
        float msgW = GlHelper.getStringWidth(message, msgSize);
        GlHelper.drawString((width - Math.min(width * 0.8f, msgW)) / 2f, barY() + 46,
                Math.min(width * 0.8f, msgW) + 4, 21, msgSize, message,
                GlHelper.lerpColor(0x00000000, 0xFFE9C9C9, fade), false, false);

        String sub = "You can still join play.georail.eu — retry packs any time from Options > GeoRail Packs (K).";
        float subW = GlHelper.getStringWidth(sub, 12);
        GlHelper.drawString((width - subW) / 2f, barY() + 76, subW + 4, 17, 12, sub,
                GlHelper.lerpColor(0x00000000, 0x99AEB9E0, fade), false, false);
        GlHelper.end();

        renderButtons(fade);
    }

    private void renderButtons(float fade) {
        if (fade <= 0.05f) return;
        float[] play = playButtonBounds();
        float[] report = reportButtonBounds();

        int playBg = GlHelper.lerpColor(0x00000000, within(play) ? 0x30FFFFFF : 0x22FFFFFF, fade);
        GlHelper.drawRoundedRect(play[0], play[1], play[2], play[3], 9, playBg);

        int reportBg = GlHelper.lerpColor(0x00000000, within(report) ? 0x70FF6B72 : 0x4DFF6B72, fade);
        GlHelper.drawRoundedRect(report[0], report[1], report[2], report[3], 9, reportBg);

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
        String playText = "Play without the pack";
        float pw = GlHelper.getStringWidth(playText, 16);
        GlHelper.drawString(play[0] + (play[2] - pw) / 2f, play[1] + 14, pw + 4, 22, 16, playText,
                GlHelper.lerpColor(0x00000000, 0xFFE9EEFF, fade), false, false);

        String reportText = System.currentTimeMillis() < copiedUntil ? "Report copied" : "Report error";
        float rw = GlHelper.getStringWidth(reportText, 16);
        GlHelper.drawString(report[0] + (report[2] - rw) / 2f, report[1] + 14, rw + 4, 22, 16, reportText,
                GlHelper.lerpColor(0x00000000, 0xFFFFDCDC, fade), false, false);
        GlHelper.end();

        if (System.currentTimeMillis() < copiedUntil) {
            GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
            String copied = "The report (including the live log) is on your clipboard.";
            float cw = GlHelper.getStringWidth(copied, 13);
            GlHelper.drawString((GlHelper.getWidth() - cw) / 2f, report[1] + report[3] + 12, cw + 4, 17, 13,
                    copied, 0xB48E9BC4, false, false);
            GlHelper.end();
        }
    }

    private static int rowColor(String row) {
        if (row.startsWith("[OK]") || row.startsWith("[cached]")) return 0xA08FDEA0;
        if (row.startsWith("[ERR]")) return 0xAAFF9E9E;
        return 0xCCB9C6E8;
    }

    // ---- info icon + log drawer ----------------------------------------------

    private void renderInfoIcon() {
        float cx = (infoIconBounds()[0] + infoIconBounds()[2] / 2f);
        float cy = (infoIconBounds()[1] + infoIconBounds()[3] / 2f);
        boolean over = within(infoIconBounds());
        int color = over ? 0xE6E6EBFF : 0x99C6D2F2;

        // small "i" glyph inside a ring, approximating the lucide info icon
        GlHelper.drawRing(cx, cy, 9.5f, 1.6f, color, 24);
        GlHelper.beginColor();
        GlHelper.blit(cx - 1.1f, cy - 5.5f, 2.2f, 2.2f, color);            // dot
        GlHelper.blit(cx - 1.1f, cy - 1.4f, 2.2f, 4.6f, color);            // stem
        GlHelper.end();
    }

    private void renderLogDrawer() {
        float[] b = logDrawerBounds();
        float x = b[0];
        float y = b[1];
        float w = b[2];
        float h = b[3];

        GlHelper.beginColor();
        GlHelper.blit(x - 6, y - 6, w + 12, h + 12, 0x59000000);
        GlHelper.blit(x, y, w, h, 0xE6070B20);
        GlHelper.blit(x, y, 2, h, 0x40FFFFFF);
        GlHelper.end();

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
        GlHelper.drawString(x + 14, y + 10, w - 28, 20, 15,
                "GeoRail Pack Sync v" + GeoPackWiz.MOD_VERSION + " — live log", 0xFFE6EBFF, false, false);
        GlHelper.end();

        List<String> logs = state.logs();
        float lineH = 19;
        float bodyY = y + 36;
        float usable = h - 48;
        int visible = Math.max(1, (int) (usable / lineH));
        if (logFollow) logScrollUp = 0;
        int maxUp = Math.max(0, logs.size() - visible);
        logScrollUp = Math.min(logScrollUp, maxUp);
        int start = Math.max(0, logs.size() - visible - logScrollUp);

        GlHelper.begin(GlHelper.PRELOAD_FONT_TEXTURE);
        GlHelper.enableScissor(x + 10, bodyY, w - 20, usable);
        float lineY = bodyY;
        for (int i = start; i < logs.size(); i++) {
            GlHelper.drawString(x + 12, lineY, w - 24, lineH, 13, logs.get(i), 0xB4C6D2F2, false, true);
            lineY += lineH;
        }
        GlHelper.disableScissor();
        GlHelper.end();
    }

    private static String shortMessage(Throwable t) {
        if (t == null) return "";
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return msg == null || msg.isEmpty() ? c.getClass().getSimpleName() : msg;
    }
}
