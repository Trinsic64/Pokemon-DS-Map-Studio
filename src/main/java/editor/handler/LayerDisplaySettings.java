package editor.handler;

import java.util.Arrays;

/**
 * Transient, editor-only display settings for terrain layers.
 *
 * These values are deliberately kept outside MapGrid so they can never be
 * written to a map or alter the tiles themselves.
 */
public final class LayerDisplaySettings {

    public static final int MIN_OPACITY = 0;
    public static final int MAX_OPACITY = 100;

    private final int[] opacityPercent;

    public LayerDisplaySettings(int layerCount) {
        if (layerCount < 0) {
            throw new IllegalArgumentException("Layer count cannot be negative");
        }
        opacityPercent = new int[layerCount];
        Arrays.fill(opacityPercent, MAX_OPACITY);
    }

    public int getOpacityPercent(int layerIndex) {
        return opacityPercent[layerIndex];
    }

    public float getOpacity(int layerIndex) {
        return getOpacityPercent(layerIndex) / 100.0f;
    }

    public void setOpacityPercent(int layerIndex, int opacity) {
        opacityPercent[layerIndex] = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, opacity));
    }
}
