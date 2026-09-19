package eu.justnoone.geopackwiz.io;

import eu.justnoone.geopackwiz.gui.gl.GlHelper;

import java.util.List;

public interface ProgressReceiver {

    void printLog(String line) throws GlHelper.MinecraftStoppingException;
    void printLogOutsidePolling(String line) throws GlHelper.MinecraftStoppingException;
    void amendLastLog(String postfix) throws GlHelper.MinecraftStoppingException;
    void setProgress(float primary, float secondary) throws GlHelper.MinecraftStoppingException;
    void setInfo(String aux1, String aux2) throws GlHelper.MinecraftStoppingException;
    void setException(Exception exception) throws GlHelper.MinecraftStoppingException;

    /** Live per-pack status lines (e.g. "[..] My Pack", "[OK] My Pack"). */
    default void setPackStatus(List<String> lines) throws GlHelper.MinecraftStoppingException {
    }

    /** True once the user asked to abort the running sync (UI polls this). */
    default boolean isAborted() {
        return false;
    }
}
