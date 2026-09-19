package eu.justnoone.geopackwiz.gui;


import eu.justnoone.geopackwiz.Config;
import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.GeoPackWizClient;
import eu.justnoone.geopackwiz.ServerConfig;
import eu.justnoone.geopackwiz.io.network.GeopakClient;
import eu.justnoone.geopackwiz.ram.RamPack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int LIST_TOP = 130;

    public ConfigScreen() {
        super(Component.literal("GeoRail Pack Sync"));
    }

    private Button refreshBtn;
    private Button syncBtn;

    private final List<Button> packRowButtons = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private List<GeopakClient.RemotePack> remotePacks = List.of();
    private boolean fetching = false;
    private String statusText = "";
    private int listScroll = 0;

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.ROOT, "%.1f KiB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", n / 1048576.0);
    }

    @Override
    protected void init() {
        super.init();

        final int margin = 20;
        this.refreshBtn = Button.builder(Component.literal("Refresh pack list"), (btn) -> fetchPacks())
                .bounds(this.width - margin - 130, 98, 130, 20).build();
        addRenderableWidget(this.refreshBtn);

        int bottomY = this.height - 30;
        this.syncBtn = Button.builder(Component.literal("Sync now"), (btn) -> {
            saveSelection();
            // Runs the real sync in the background and shows the in-game progress
            // screen; the game stays fully responsive (no freeze while offline).
            // Packs are applied automatically when it finishes.
            GeoPackWizClient.startSessionSync();
        }).bounds(margin, bottomY, 110, 20).build();
        addRenderableWidget(this.syncBtn);
        addRenderableWidget(Button.builder(Component.literal("Done"), (btn) -> {
            saveSelection();
            assert minecraft != null;
            minecraft.setScreen(null);
        }).bounds(this.width - margin - 80, bottomY, 80, 20).build());

        selected.clear();
        selected.addAll(GeoPackWiz.CONFIG.websitePacks.value);

        refreshPackRows();
        fetchPacks();
    }

    private void saveSelection() {
        GeoPackWiz.CONFIG.websitePacks.value = new ArrayList<>(selected);
        GeoPackWiz.CONFIG.websitePacks.persist();
        try {
            GeoPackWiz.CONFIG.save();
        } catch (Exception e) {
            GeoPackWiz.LOGGER.error("Failed to save GeoPackWiz config", e);
        }
    }

    // ---- Pack list -----------------------------------------------------------

    private void fetchPacks() {
        if (fetching) return;
        fetching = true;
        refreshBtn.active = false;
        statusText = "Fetching pack list ...";

        CompletableFuture.supplyAsync(() -> {
            try {
                return GeopakClient.fetchPackList(ServerConfig.WEBSITE_BASE_URL, ServerConfig.API_KEY);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }).whenComplete((packs, err) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                fetching = false;
                refreshBtn.active = true;
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    statusText = "Could not reach the pack website: " + cause.getMessage();
                    refreshPackRows();
                    return;
                }
                remotePacks = packs;
                selected.retainAll(packs.stream().map(p -> p.id).toList());
                statusText = "Connected — " + packs.size() + " pack(s), " + selected.size() + " selected.";
                refreshPackRows();
            });
        });
    }

    private void refreshPackRows() {
        for (Button btn : packRowButtons) removeWidget(btn);
        packRowButtons.clear();

        int maxVisible = Math.max(1, (this.height - LIST_TOP - 60) / ROW_HEIGHT);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, remotePacks.size() - maxVisible)));

        for (int i = listScroll; i < Math.min(remotePacks.size(), listScroll + maxVisible); i++) {
            GeopakClient.RemotePack pack = remotePacks.get(i);
            boolean isSelected = selected.contains(pack.id);
            String label = (isSelected ? "(+) " : "( ) ") + pack.title + "  ·  " + formatBytes(pack.zipSize);
            int y = LIST_TOP + (i - listScroll) * ROW_HEIGHT;
            Button row = Button.builder(Component.literal(label), (btn) -> togglePack(pack.id))
                    .bounds(20, y, this.width - 40, ROW_HEIGHT - 2).build();
            row.setMessage(Component.literal(label));
            addRenderableWidget(row);
            packRowButtons.add(row);
        }
    }

    private void togglePack(String id) {
        if (!selected.add(id)) selected.remove(id);
        saveSelection();
        refreshPackRows();
        statusText = remotePacks.size() + " pack(s), " + selected.size() + " selected.";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX > 20 && mouseX < this.width - 20 && mouseY > LIST_TOP) {
            listScroll -= (int) delta;
            refreshPackRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ---- Rendering -----------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        renderBackground(guiGraphics);
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xFF0D1230, 0xFF070B1E);

        int titleColor = 0xFF7FB0FF;
        guiGraphics.drawString(this.font, "GeoRail Pack Sync", 20, 14, titleColor, false);
        guiGraphics.drawString(this.font, "Server: " + ServerConfig.WEBSITE_BASE_URL + "   (built into the mod)", 20, 36, 0xFFB9C2E8, false);
        guiGraphics.drawString(this.font, "Applied only on GeoRail servers (any *.georail domain or "
                + String.join(", ", ServerConfig.ENABLED_EXTRA_HOSTS)
                + ")   (off on other servers and singleplayer)", 20, 52, 0xFFB9C2E8, false);

        String loadState = RamPack.isLoaded()
                ? "loaded in RAM (merged)"
                : "not loaded yet — if the boot sync failed, join " + ServerConfig.ENABLED_SERVER_ADDRESS
                        + " or click Sync now to retry";
        guiGraphics.drawString(this.font, "Pack state: " + loadState, 20, 68, 0xFF9AA3C7, false);

        guiGraphics.drawString(this.font, "Select the packs to sync (empty = all):", 20, 88, 0xFF9AA3C7, false);

        guiGraphics.drawString(this.font, statusText.isEmpty() ? "Fetching ..." : statusText, 20, 102, 0xFFE8ECFF, false);

        int maxVisible = Math.max(1, (this.height - LIST_TOP - 60) / ROW_HEIGHT);
        if (remotePacks.size() > maxVisible) {
            guiGraphics.drawString(this.font, "Scroll to see more (" + (listScroll + 1) + "-"
                    + Math.min(listScroll + maxVisible, remotePacks.size()) + " of " + remotePacks.size() + ")",
                    this.width - 20 - 190, LIST_TOP - 12, 0xFF5A6390, false);
        }

        long lastSync = GeoPackWiz.CONFIG.lastSyncTime.value;
        String lastSyncText = lastSync > 0 ? formatAgo(lastSync) : "never";
        guiGraphics.drawString(this.font, "Last synced: " + lastSyncText, 20, this.height - 52, 0xFF9AA3C7, false);

        super.render(guiGraphics, mouseX, mouseY, delta);
    }

    private static String formatAgo(long epochMillis) {
        long secs = (System.currentTimeMillis() - epochMillis) / 1000;
        if (secs < 60) return secs + " s ago";
        if (secs < 3600) return (secs / 60) + " min ago";
        if (secs < 86400) return (secs / 3600) + " h ago";
        return (secs / 86400) + " d ago";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
