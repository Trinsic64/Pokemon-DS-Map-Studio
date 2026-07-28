package editor.globaledit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalMapEditDialogTest {

    @Test
    void currentAndPreviewMapsUseOppositeTilePickDirections() {
        assertTrue(GlobalMapEditDialog.picksSourceTile(false, false));
        assertFalse(GlobalMapEditDialog.picksSourceTile(false, true));
        assertFalse(GlobalMapEditDialog.picksSourceTile(true, false));
        assertTrue(GlobalMapEditDialog.picksSourceTile(true, true));
    }

    @Test
    void higherNumberedLayersPaintFirstAtEqualDepth() {
        int comparison = GlobalMapEditDialog.comparePreviewDrawOrder(
                3, 8, 8, 4,
                3, 8, 1, 4);

        assertTrue(comparison < 0);
    }
}
