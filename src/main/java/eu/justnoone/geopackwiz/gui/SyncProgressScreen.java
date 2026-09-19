package eu.justnoone.geopackwiz.gui;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.GeoPackWizClient;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.sync.SyncEngine;
import eu.justnoone.geopackwiz.sync.SyncState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * In-game counterpart of the boot screen. Used when a sync has to run while the
 * game is already running (joining the GeoRail server after an offline boot, or
 * "Sync now" from the config screen). The sync engine runs on a background
 * thread — this screen only shows progress and never freezes the game.
 */
public class SyncProgressScreen extends Screen {

    private static final int BG_TOP = 0xFF0B1030;
    private static final int BG_BOTTOM = 0xFF04071A;
    private static final int ERR_BG_TOP = 0xFF35080F;
    private static final int ERR_BG_BOTTOM = 0xFF100204;

    private final SyncEngine engine;
    private final SyncState state;

    private boolean logsOpen;
    private int logScrollUp;
    private boolean logFollow = true;
    private boolean failureUiAdded;
    private boolean reportCopied;
    private long copiedUntil;

    private Button logsButton;
    private Button playButton;
    private Button reportButton;

    public SyncProgressScreen(SyncEngine engine) {
        super(Component.literal("GeoRail Pack Sync"));
        this.engine = engine;
        this.state = engine.state();
    }

    @Override
    protected void init() {
        super.init();
        this.logsButton = Button.builder(Component.literal("Logs"),
                (btn) -> {
                    logsOpen = !logsOpen;
                    btn.setMessage(Component.literal(logsOpen ? "Hide logs" : "Logs"));
                }).bounds(this.width - 74, this.height - 26, 60, 20).build();
        addRenderableWidget(logsButton);
    }

    @Override
    public void tick() {
        if (engine.state().isFinished() && !failureUiAdded) {
            if (engine.state().succeeded() || engine.state().isCancelled()) {
                GeoPackWizClient.onSessionSyncFinished(engine);
                return;
            }
            if (engine.state().failed()) {
                addFailureWidgets();
            }
        }
    }

    private void addFailureWidgets() {
        failureUiAdded = true;
        int cw = Math.min(240, this.width / 2 - 24);
        int x = this.width / 2 - cw - 10;
        this.playButton = Button.builder(Component.literal("Play without the pack"), (btn) -> {
            engine.cancel();
            GeoPackWizClient.onSessionSyncFinished(engine);
        }).bounds(x, this.height - 64, cw, 20).build();
        addRenderableWidget(playButton);

        int x2 = this.width / 2 + 10;
        this.reportButton = Button.builder(Component.literal("Report error"), (btn) -> {
            copyToClipboard(state.buildReport());
            reportCopied = true;
            copiedUntil = System.currentTimeMillis() + 2500;
            btn.setMessage(Component.literal("Report copied"));
        }).bounds(x2, this.height - 64, Math.min(220, this.width / 2 - 24), 20).build();
        addRenderableWidget(reportButton);

        if (logsButton != null) logsButton.active = true;
    }

    private void copyToClipboard(String text) {
        try {
            GLFW.glfwSetClipboardString(Minecraft.getInstance().getWindow().getWindow(), text);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (logsOpen) {
                logsOpen = false;
                if (logsButton != null) logsButton.setMessage(Component.literal("Logs"));
                return true;
            }
            engine.cancel();
            GeoPackWizClient.onSessionSyncFinished(engine);
            return true;
        }
        if (logsOpen) {
            if (keyCode == 264) { // down
                logScrollUp = Math.max(0, logScrollUp - 1);
            } else if (keyCode == 265) { // up
                logScrollUp++;
                logFollow = false;
            } else if (keyCode == 266) { // page up
                logScrollUp += 10;
                logFollow = false;
            } else if (keyCode == 267) { // page down
                logScrollUp = Math.max(0, logScrollUp - 10);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (logsOpen && mouseX > this.width - this.width * 0.46f - 8) {
            if (delta > 0) {
                logScrollUp++;
                logFollow = false;
            } else {
                logScrollUp = Math.max(0, logScrollUp - 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean failed = state.failed();
        float fade = failed ? Math.min(1f, (System.currentTimeMillis() - state.failedAtMillis()) / 800f) : 0f;

        int top = GlHelper.lerpColor(BG_TOP, ERR_BG_TOP, fade);
        int bottom = GlHelper.lerpColor(BG_BOTTOM, ERR_BG_BOTTOM, fade);
        guiGraphics.fillGradient(0, 0, this.width, this.height, top, bottom);

        if (failed) {
            renderError(guiGraphics, fade);
        } else {
            renderProgress(guiGraphics);
        }

        if (logsOpen) renderLogs(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderProgress(GuiGraphics g) {
        int barW = (int) Math.min(this.width * 0.5f, 460);
        int barX = (this.width - barW) / 2;
        int barY = this.height / 2 - 2;

        g.fill(barX - 2, barY - 2, barX + barW + 2, barY + 4, 0x30FFFFFF);
        int fill = (int) (barW * Math.max(0f, Math.min(1f, state.progress())));
        if (fill > 0) g.fill(barX, barY - 1, barX + fill, barY + 2, 0xFFDCE7FF);

        String stage = state.stageText();
        if (stage == null || stage.isEmpty()) stage = "Preparing ...";
        g.drawCenteredString(this.font, stage, this.width / 2, barY - 30, 0xFFE6EBFF);

        String sub = state.subText();
        if (sub != null && !sub.isEmpty()) {
            g.drawCenteredString(this.font, sub, this.width / 2, barY + 18, 0xFF8E9BC4);
        }

        List<String> rows = state.packRows();
        int ry = barY + 42;
        for (int i = 0; i < Math.min(rows.size(), 6); i++) {
            String row = rows.get(i);
            g.drawCenteredString(this.font, row, this.width / 2, ry, rowColor(row));
            ry += 14;
        }

        String hint = "Downloading in the background — press ESC to cancel";
        g.drawCenteredString(this.font, hint, this.width / 2, this.height - 26, 0xFF5A658F);
    }

    private void renderError(GuiGraphics g, float fade) {
        if (fade < 0.05f) return;
        int titleColor = lerpA(0xFFDDD3D3, fade);
        g.drawCenteredString(this.font, "The GeoRail pack could not be loaded",
                this.width / 2, this.height / 2 - 60, titleColor);

        String message = shortMessage(state.failure());
        if (message.isEmpty()) message = "Check your internet connection and try again later.";
        g.drawCenteredString(this.font, message, this.width / 2, this.height / 2 - 32, lerpA(0xFFB9A6A6, fade));

        if (reportCopied && System.currentTimeMillis() < copiedUntil) {
            g.drawCenteredString(this.font, "Report copied to your clipboard", this.width / 2, this.height - 40, 0xFF8E9BC4);
        }
    }

    private void renderLogs(GuiGraphics g) {
        int w = Math.min((int) (this.width * 0.46f), 480);
        int x = this.width - w - 8;
        int h = this.height - 90;
        int y = 8;

        g.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0x99000000);
        g.fill(x, y, x + w, y + h, 0xE6070B20);
        g.fill(x, y, x + 2, y + h, 0x55FFFFFF);

        g.drawString(this.font, "GeoRail Pack Sync v" + GeoPackWiz.MOD_VERSION + " — live log",
                x + 8, y + 8, 0xFFE6EBFF);

        List<String> logs = state.logs();
        int lineH = 12;
        int bodyX = x + 8;
        int bodyY = y + 24;
        int visible = Math.max(1, (h - 32) / lineH);
        if (logFollow) logScrollUp = 0;
        int maxUp = Math.max(0, logs.size() - visible);
        logScrollUp = Math.min(logScrollUp, maxUp);
        int start = Math.max(0, logs.size() - visible - logScrollUp);

        int drawY = bodyY;
        for (int i = start; i < logs.size(); i++) {
            String line = logs.get(i);
            int maxChars = Math.max(10, (w - 16) / 6);
            if (line.length() > maxChars) line = line.substring(0, maxChars - 1) + "…";
            g.drawString(this.font, line, bodyX, drawY, 0xB4C6D2F2);
            drawY += lineH;
        }
    }

    private static int rowColor(String row) {
        if (row.startsWith("[OK]") || row.startsWith("[cached]")) return 0xFF7FB88A;
        if (row.startsWith("[ERR]")) return 0xFFFFA0A0;
        return 0xFFB9C6E8;
    }

    private static int lerpA(int color, float fade) {
        int r = color >>> 16 & 0xFF;
        int g = color >>> 8 & 0xFF;
        int b = color & 0xFF;
        int a = (int) (255 * Math.max(0f, Math.min(1f, fade)));
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static String shortMessage(Throwable t) {
        if (t == null) return "";
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return msg == null || msg.isEmpty() ? c.getClass().getSimpleName() : msg;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
