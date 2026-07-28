package tileset;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TilesetRendererTest {

    @Test
    void convertsPremultipliedRgbToStraightAlphaAndClearsHiddenColor() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80804020);
        image.setRGB(1, 0, 0x00008080);
        image.setRGB(2, 0, 0xff123456);

        TilesetRenderer.convertPremultipliedPixelsToStraightAlpha(image);

        assertEquals(0x80ff8040, image.getRGB(0, 0));
        assertEquals(0x00000000, image.getRGB(1, 0));
        assertEquals(0xff123456, image.getRGB(2, 0));
    }
}
