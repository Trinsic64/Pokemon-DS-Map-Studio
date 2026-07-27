package editor.globaledit;

import editor.grid.MapGrid;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalMapOperationsTest {

    @Test
    void replacesOnlyRequestedLayersAndCanSwapBothDirections() {
        MapGrid grid = new MapGrid(null);
        grid.tileLayers[0][0][0] = 4;
        grid.tileLayers[0][1][0] = 7;
        grid.tileLayers[1][0][0] = 4;

        int changed = GlobalMapOperations.replaceTiles(
                grid, new int[]{0}, 4, 7, true);

        assertEquals(2, changed);
        assertEquals(7, grid.tileLayers[0][0][0]);
        assertEquals(4, grid.tileLayers[0][1][0]);
        assertEquals(4, grid.tileLayers[1][0][0]);
    }

    @Test
    void mapPreviewExclusionsSkipOnlyTheChosenOccurrence() {
        MapGrid grid = new MapGrid(null);
        Point map = new Point(3, 7);
        grid.tileLayers[0][4][5] = 4;
        grid.tileLayers[0][6][5] = 4;

        int changed = GlobalMapOperations.replaceTiles(
                grid, map, new int[]{0}, 4, 9, false,
                Collections.singleton(new GlobalMapOperations.TileCell(map, 0, 4, 5)));

        assertEquals(1, changed);
        assertEquals(4, grid.tileLayers[0][4][5]);
        assertEquals(9, grid.tileLayers[0][6][5]);
    }

    @Test
    void copiesTileAndHeightLayoutsWithoutSharingArrays() {
        MapGrid grid = new MapGrid(null);
        grid.tileLayers[2][3][4] = 11;
        grid.heightLayers[2][3][4] = 6;

        int changed = GlobalMapOperations.copyLayer(grid, 2, 5, true, true);
        grid.tileLayers[2][3][4] = 12;
        grid.heightLayers[2][3][4] = 7;

        assertEquals(2, changed);
        assertEquals(11, grid.tileLayers[5][3][4]);
        assertEquals(6, grid.heightLayers[5][3][4]);
    }

    @Test
    void adjustsOccupiedHeightsAndClampsToSupportedRange() {
        MapGrid grid = new MapGrid(null);
        grid.tileLayers[0][0][0] = 2;
        grid.heightLayers[0][0][0] = 14;
        grid.heightLayers[0][1][0] = 4;

        int changed = GlobalMapOperations.adjustHeights(
                grid, new int[]{0}, 5, -15, 15, true);

        assertEquals(1, changed);
        assertEquals(15, grid.heightLayers[0][0][0]);
        assertEquals(4, grid.heightLayers[0][1][0]);
    }

    @Test
    void clearsOnlyTheChosenData() {
        MapGrid grid = new MapGrid(null);
        grid.tileLayers[3][1][2] = 9;
        grid.heightLayers[3][1][2] = 5;

        int changed = GlobalMapOperations.clearLayers(
                grid, new int[]{3}, true, false);

        assertEquals(1, changed);
        assertEquals(-1, grid.tileLayers[3][1][2]);
        assertEquals(5, grid.heightLayers[3][1][2]);
    }
}
