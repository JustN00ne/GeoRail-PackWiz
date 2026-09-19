package eu.justnoone.geopackwiz.gui.gl;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import eu.justnoone.geopackwiz.GeoPackWiz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.DoubleBuffer;

public class GlHelper {

    public static void clearScreen(float r, float g, float b) {
        RenderSystem.clearColor(r, g, b, 1f);
        RenderSystem.clear(16640, Minecraft.ON_OSX);
    }

    private static ShaderInstance previousShader;
    private static Matrix4f lastProjectionMat;
    private static VertexSorting lastVertexSorting;

    public static void initGlStates() {
        previousShader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();
        lastProjectionMat = new Matrix4f(RenderSystem.getProjectionMatrix());
        lastVertexSorting = RenderSystem.getVertexSorting();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
    }

    public static void resetGlStates() {
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(() -> previousShader);
        RenderSystem.setProjectionMatrix(lastProjectionMat, lastVertexSorting);
    }

    public static final ResourceLocation PRELOAD_FONT_TEXTURE =
            new ResourceLocation(GeoPackWiz.MOD_ID, "textures/font/roboto.png");
    public static final SimpleFont preloadFont = new SimpleFont(PRELOAD_FONT_TEXTURE);

    private static BufferBuilder bufferBuilder;

    public static void begin(ResourceLocation texture) {
        bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        try {
            Minecraft.getInstance().getTextureManager().getTexture(texture).setFilter(true, false);
        } catch (RuntimeException ex) {
            if (registerFromClasspath(texture)) {
                Minecraft.getInstance().getTextureManager().getTexture(texture).setFilter(true, false);
            } else {
                GeoPackWiz.LOGGER.warn("Preload texture missing before resource load: {}", texture, ex);
            }
        }
        RenderSystem.setShaderTexture(0, texture);
    }

    public static void beginColor() {
        bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static boolean registerFromClasspath(ResourceLocation texture) {
        String path = "/assets/" + texture.getNamespace() + "/" + texture.getPath();
        try (InputStream stream = GlHelper.class.getResourceAsStream(path)) {
            if (stream == null) {
                return false;
            }
            NativeImage image = NativeImage.read(stream);
            Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void end() {
        Tesselator.getInstance().end();
    }

    public static void swapBuffer() throws MinecraftStoppingException {
        Window window = Minecraft.getInstance().getWindow();
        if (window.shouldClose()) {
            throw new MinecraftStoppingException();
        } else {
            window.updateDisplay();
        }
    }

    public static void blit(float x1, float y1, float width, float height, float u1, float v1, float u2, float v2, int color) {
        float x2 = x1 + width;
        float y2 = y1 + height;
        withColor(bufferBuilder.vertex(x1, y1, 1f).uv(u1, v1), color).endVertex();
        withColor(bufferBuilder.vertex(x2, y1, 1f).uv(u2, v1), color).endVertex();
        withColor(bufferBuilder.vertex(x2, y2, 1f).uv(u2, v2), color).endVertex();
        withColor(bufferBuilder.vertex(x1, y2, 1f).uv(u1, v2), color).endVertex();
    }

    public static void blit(float x1, float y1, float width, float height, int color) {
        float x2 = x1 + width;
        float y2 = y1 + height;
        withColor(bufferBuilder.vertex(x1, y1, 1f).uv(preloadFont.whiteU, preloadFont.whiteV), color).endVertex();
        withColor(bufferBuilder.vertex(x2, y1, 1f).uv(preloadFont.whiteU, preloadFont.whiteV), color).endVertex();
        withColor(bufferBuilder.vertex(x2, y2, 1f).uv(preloadFont.whiteU, preloadFont.whiteV), color).endVertex();
        withColor(bufferBuilder.vertex(x1, y2, 1f).uv(preloadFont.whiteU, preloadFont.whiteV), color).endVertex();
    }

    public static void drawShadowString(float x1, float y1, float width, float height, float fontSize,
                                  String text, int color, boolean monospace, boolean noWrap) {
        drawString(x1 + fontSize / 16, y1 + fontSize / 16, width, height, fontSize, text, 0xFF222222, monospace, noWrap);
        drawString(x1, y1, width, height, fontSize, text, color, monospace, noWrap);
    }

    public static void drawString(float x1, float y1, float width, float height, float fontSize,
                                  String text, int color, boolean monospace, boolean noWrap) {
        float CHAR_SPACING = 0f;
        float LINE_SPACING = 0.25f;

        var x = x1;
        var y = y1;
        for (char chr : text.toCharArray()) {
            if (chr == '\n') {
                y += fontSize + LINE_SPACING * fontSize;
                x = x1;
            } else if (chr == '\r') {
                // Ignore CR
            } else if (chr == '\t') {
                // Align to 8 spaces
                float alignToPixels = (preloadFont.spaceWidthPl + CHAR_SPACING) * 8 * fontSize;
                x = (float) (Math.ceil((x - x1) / alignToPixels) * alignToPixels + x1);
            } else if (chr == ' ') {
                x += (preloadFont.spaceWidthPl + CHAR_SPACING) * fontSize;
            } else {
                SimpleFont.GlyphProperty glyph = preloadFont.getGlyph(chr);
                float advance = glyph.advancePl * fontSize;

                if (x + advance + CHAR_SPACING * fontSize > x1 + width) {
                    if (noWrap) {
                        continue;
                    } else {
                        y += fontSize + LINE_SPACING * fontSize;
                        x = x1;
                    }
                }
                if (y + fontSize > y1 + height) {
                    return;
                }

                blit(x + glyph.offsetXPl * fontSize, y + (preloadFont.baseLineYPl + glyph.offsetYPl) * fontSize,
                        glyph.widthPl * fontSize, glyph.heightPl * fontSize,
                        glyph.u1, glyph.v1, glyph.u2, glyph.v2, color);
                x += advance + CHAR_SPACING * fontSize;
            }
        }
    }

    public static float getStringWidth(String text, float fontSize) {
        float CHAR_SPACING = 0f;

        float width = 0;
        float x = 0;
        for (char chr : text.toCharArray()) {
            if (chr == '\n') {
                width = Math.max(width, x);
                x = 0;
            } else if (chr == '\r') {
                // Ignore CR
            } else if (chr == '\t') {
                // Align to 8 spaces
                float alignToPixels = (preloadFont.spaceWidthPl + CHAR_SPACING) * 8 * fontSize;
                x = (float) (Math.ceil(x / alignToPixels) * alignToPixels);
            } else if (chr == ' ') {
                x += (preloadFont.spaceWidthPl + CHAR_SPACING) * fontSize;
            } else {
                SimpleFont.GlyphProperty glyph = preloadFont.getGlyph(chr);
                x += glyph.advancePl * fontSize + CHAR_SPACING * fontSize;
            }
        }
        return Math.max(width, x);
    }

    public static void setMatIdentity() {
        RenderSystem.getModelViewStack().setIdentity();
    }

    public static void setMatPixel() {
        Matrix4f matrix = new Matrix4f().identity();
        matrix.scale(2f, -2f, 1f);
        matrix.translate(-0.5f, -0.5f, 0f);
        float rawWidth = Minecraft.getInstance().getWindow().getWidth();
        float rawHeight = Minecraft.getInstance().getWindow().getHeight();
        matrix.scale(1f / rawWidth, 1f / rawHeight, 1f);
        RenderSystem.setProjectionMatrix(matrix, VertexSorting.ORTHOGRAPHIC_Z);
    }

    public static void setMatScaledPixel() {
        Matrix4f matrix = new Matrix4f().identity();
        matrix.scale(2f, -2f, 1f);
        matrix.translate(-0.5f, -0.5f, 0f);
        matrix.scale(1f / getWidth(), 1f / getHeight(), 1f);
        RenderSystem.setProjectionMatrix(matrix, VertexSorting.ORTHOGRAPHIC_Z);
    }

    public static void enableScissor(float x, float y, float width, float height) {
        Matrix4f posMap = RenderSystem.getProjectionMatrix();
        Vector4f bottomLeft = new Vector4f(x, y + height, 0, 1);
        bottomLeft.mul(posMap);
        Vector4f topRight = new Vector4f(x + width, y, 0, 1);
        topRight.mul(posMap);
        float x1 = (float)Mth.map(bottomLeft.x, -1, 1, 0, Minecraft.getInstance().getWindow().getWidth());
        float y1 = (float)Mth.map(bottomLeft.y, -1, 1, 0, Minecraft.getInstance().getWindow().getHeight());
        float x2 = (float)Mth.map(topRight.x, -1, 1, 0, Minecraft.getInstance().getWindow().getWidth());
        float y2 = (float)Mth.map(topRight.y, -1, 1, 0, Minecraft.getInstance().getWindow().getHeight());
        RenderSystem.enableScissor((int)x1, (int)y1, (int)(x2 - x1), (int)(y2 - y1));
    }

    public static void disableScissor() {
        RenderSystem.disableScissor();
    }

    public static int getWidth() {
        int rawWidth = Minecraft.getInstance().getWindow().getWidth();
        if (rawWidth < 854) {
            return rawWidth;
        } else if (rawWidth < 1920) {
            return (int)((rawWidth - 854) * 1f / (1920 - 854) * (1366 - 854) + 854);
        } else {
            return 1366;
        }
    }

    public static int getHeight() {
        int rawWidth = Minecraft.getInstance().getWindow().getWidth();
        int rawHeight = Minecraft.getInstance().getWindow().getHeight();
        return (int)(rawHeight * (getWidth() * 1f / rawWidth));
    }

    public static void setMatCenterForm(float width, float height, float widthPercent) {
        Matrix4f matrix = new Matrix4f().identity();
        matrix.scale(2f, -2f, 1f);
        matrix.translate(-0.5f, -0.5f, 0f);
        float rawWidth = Minecraft.getInstance().getWindow().getWidth();
        float rawHeight = Minecraft.getInstance().getWindow().getHeight();
        matrix.scale(1f / rawWidth, 1f / rawHeight, 1f);
        float formRawWidth = rawWidth * widthPercent;
        float formRawHeight = height / width * formRawWidth;
        matrix.translate((rawWidth - formRawWidth) / 2f, (rawHeight - formRawHeight) / 2f, 0f);
        matrix.scale(formRawWidth / width, formRawHeight / height, 1f);
        RenderSystem.setProjectionMatrix(matrix, VertexSorting.ORTHOGRAPHIC_Z);
    }

    private static VertexConsumer withColor(VertexConsumer vc, int color) {
        int a = color >>> 24 & 0xFF;
        int r = color >>> 16 & 0xFF;
        int g = color >>> 8 & 0xFF;
        int b = color & 0xFF;
        return vc.color(r, g, b, a);
    }

    // ---- Drawing helpers for the pre-menu screens ---------------------------

    /** Vertical gradient drawn as N flat bands. Caller must not hold a buffer. */
    public static void drawVerticalGradient(float x, float y, float w, float h, int topColor, int bottomColor) {
        drawVerticalGradient(x, y, w, h, topColor, bottomColor, 24);
    }

    /** Vertical gradient drawn as N flat bands. Caller must not hold a buffer. */
    public static void drawVerticalGradient(float x, float y, float w, float h, int topColor, int bottomColor, int bands) {
        beginColor();
        try {
            int n = Math.max(2, bands);
            for (int i = 0; i < n; i++) {
                float t0 = i / (float) n;
                float t1 = (i + 1) / (float) n;
                float y0 = y + h * t0;
                float hh = h / n + 0.6f;
                blit(x, y0, w, hh, lerpColor(topColor, bottomColor, (t0 + t1) / 2f));
            }
        } finally {
            end();
        }
    }

    public static int lerpColor(int from, int to, float t) {
        float tt = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24 & 0xFF)) + (((to >>> 24 & 0xFF)) - ((from >>> 24 & 0xFF))) * tt);
        int r = Math.round(((from >>> 16 & 0xFF)) + (((to >>> 16 & 0xFF)) - ((from >>> 16 & 0xFF))) * tt);
        int g = Math.round(((from >>> 8 & 0xFF)) + (((to >>> 8 & 0xFF)) - ((from >>> 8 & 0xFF))) * tt);
        int b = Math.round(((from & 0xFF)) + (((to & 0xFF)) - ((from & 0xFF))) * tt);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Solid rounded rectangle (corner radius r). Caller must not hold a buffer. */
    public static void drawRoundedRect(float x, float y, float w, float h, float r, int color) {
        beginColor();
        try {
            drawRoundedRectInner(x, y, w, h, r, color);
        } finally {
            end();
        }
    }

    public static void drawRoundedRectInner(float x, float y, float w, float h, float r, int color) {
        float rad = Math.min(r, Math.min(w / 2f, h / 2f));
        // middle + 4 side pieces
        blit(x + rad, y, w - 2 * rad, h, color);
        blit(x, y + rad, rad, h - 2 * rad, color);
        blit(x + w - rad, y + rad, rad, h - 2 * rad, color);
        drawCircleQuadrant(x + rad, y + rad, rad, 180f, 270f, color); // top-left
        drawCircleQuadrant(x + w - rad, y + rad, rad, 270f, 360f, color); // top-right
        drawCircleQuadrant(x + rad, y + h - rad, rad, 90f, 180f, color); // bottom-left
        drawCircleQuadrant(x + w - rad, y + h - rad, rad, 0f, 90f, color); // bottom-right
    }

    /** Filled pie-slice sweep from startDeg to endDeg (screen y-down). */
    private static void drawCircleQuadrant(float cx, float cy, float radius, float startDeg, float endDeg, int color) {
        int segments = 10;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) Math.toRadians(startDeg + (endDeg - startDeg) * i / segments);
            float a1 = (float) Math.toRadians(startDeg + (endDeg - startDeg) * (i + 1) / segments);
            float am = (a0 + a1) / 2f;
            float p0x = cx + (float) Math.cos(a0) * radius;
            float p0y = cy + (float) Math.sin(a0) * radius;
            float p1x = cx + (float) Math.cos(a1) * radius;
            float p1y = cy + (float) Math.sin(a1) * radius;
            float pmx = cx + (float) Math.cos(am) * radius;
            float pmy = cy + (float) Math.sin(am) * radius;
            quad(cx, cy, p0x, p0y, pmx, pmy, p1x, p1y, color);
        }
    }

    /** Two triangles forming the quad (cx,cy)-(p0)-(mid)-(p1). */
    private static void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        tri(x0, y0, x1, y1, x2, y2, color);
        tri(x0, y0, x2, y2, x3, y3, color);
    }

    private static void tri(float x0, float y0, float x1, float y1, float x2, float y2, int color) {
        int a = color >>> 24 & 0xFF;
        int r = color >>> 16 & 0xFF;
        int g = color >>> 8 & 0xFF;
        int b = color & 0xFF;
        bufferBuilder.vertex(x0, y0, 1f).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(x1, y1, 1f).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(x2, y2, 1f).color(r, g, b, a).endVertex();
    }

    // ---- Shapes for the boot screen UI --------------------------------------

    /** Filled circle. Caller must not hold a buffer. */
    public static void drawDisc(float cx, float cy, float radius, int color) {
        drawDisc(cx, cy, radius, color, 26);
    }

    /** Filled circle. Caller must not hold a buffer. */
    public static void drawDisc(float cx, float cy, float radius, int color, int segments) {
        beginColor();
        try {
            for (int i = 0; i < segments; i++) {
                float a0 = (float) Math.toRadians(360.0 * i / segments);
                float a1 = (float) Math.toRadians(360.0 * (i + 1) / segments);
                tri(cx, cy,
                        cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius,
                        cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius, color);
            }
        } finally {
            end();
        }
    }

    /** Ring (annulus). Caller must not hold a buffer. */
    public static void drawRing(float cx, float cy, float outerRadius, float thickness, int color) {
        drawRing(cx, cy, outerRadius, thickness, color, 26);
    }

    /** Ring (annulus). Caller must not hold a buffer. */
    public static void drawRing(float cx, float cy, float outerRadius, float thickness, int color, int segments) {
        beginColor();
        try {
            float inner = Math.max(0f, outerRadius - thickness);
            for (int i = 0; i < segments; i++) {
                float a0 = (float) Math.toRadians(360.0 * i / segments);
                float a1 = (float) Math.toRadians(360.0 * (i + 1) / segments);
                float ox0 = cx + (float) Math.cos(a0) * outerRadius;
                float oy0 = cy + (float) Math.sin(a0) * outerRadius;
                float ox1 = cx + (float) Math.cos(a1) * outerRadius;
                float oy1 = cy + (float) Math.sin(a1) * outerRadius;
                float ix0 = cx + (float) Math.cos(a0) * inner;
                float iy0 = cy + (float) Math.sin(a0) * inner;
                float ix1 = cx + (float) Math.cos(a1) * inner;
                float iy1 = cy + (float) Math.sin(a1) * inner;
                tri(ox0, oy0, ix0, iy0, ox1, oy1, color);
                tri(ox1, oy1, ix0, iy0, ix1, iy1, color);
            }
        } finally {
            end();
        }
    }

    // ---- Input polling (render thread only) ---------------------------------

    public static boolean keyDown(int glfwKey) {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), glfwKey);
    }

    public static boolean mouseDown(int glfwButton) {
        return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), glfwButton) == GLFW.GLFW_PRESS;
    }

    /** Cursor position in the scaled pixel coordinate space used by the screens. */
    public static float[] cursorScaled() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer xb = stack.mallocDouble(1);
            DoubleBuffer yb = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(window, xb, yb);
            double rx = xb.get(0);
            double ry = yb.get(0);
            float rawW = Minecraft.getInstance().getWindow().getWidth();
            float rawH = Minecraft.getInstance().getWindow().getHeight();
            return new float[] { (float) (rx * getWidth() / rawW), (float) (ry * getHeight() / rawH) };
        }
    }

    public static class MinecraftStoppingException extends RuntimeException {
        public MinecraftStoppingException() {
            super("Minecraft is now stopping.");
        }
    }
}
