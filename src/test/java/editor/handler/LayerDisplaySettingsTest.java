package editor.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayerDisplaySettingsTest {

    @Test
    void layersStartFullyOpaque() {
        LayerDisplaySettings settings = new LayerDisplaySettings(4);

        for (int layer = 0; layer < 4; layer++) {
            assertEquals(100, settings.getOpacityPercent(layer));
            assertEquals(1.0f, settings.getOpacity(layer));
        }
    }

    @Test
    void convertsPercentageToRenderAlpha() {
        LayerDisplaySettings settings = new LayerDisplaySettings(1);

        settings.setOpacityPercent(0, 35);

        assertEquals(35, settings.getOpacityPercent(0));
        assertEquals(0.35f, settings.getOpacity(0), 0.0001f);
    }

    @Test
    void clampsOpacityToSliderRange() {
        LayerDisplaySettings settings = new LayerDisplaySettings(1);

        settings.setOpacityPercent(0, -20);
        assertEquals(0, settings.getOpacityPercent(0));

        settings.setOpacityPercent(0, 120);
        assertEquals(100, settings.getOpacityPercent(0));
    }

    @Test
    void rejectsNegativeLayerCounts() {
        assertThrows(IllegalArgumentException.class, () -> new LayerDisplaySettings(-1));
    }
}
