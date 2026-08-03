package editor.tileseteditor;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tileset.PaletteFolder;
import tileset.Tile;
import tileset.Tileset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileFolderDuplicatePlannerTest {

    @Test
    void oneRowSelectionDuplicatesBesideOriginalsWhenItFits() {
        Tileset tileset = tilesetWithFolder(8);
        Tile left = add(tileset, 0);
        Tile right = add(tileset, 1);
        Tile below = add(tileset, 8);
        Tile leftCopy = duplicate(tileset, left);
        Tile rightCopy = duplicate(tileset, right);

        TileFolderDuplicatePlanner.apply(tileset,
                Arrays.asList(left, right), Arrays.asList(leftCopy, rightCopy));

        assertEquals(2, leftCopy.getPaletteSlot("Shape"));
        assertEquals(3, rightCopy.getPaletteSlot("Shape"));
        assertEquals(8, below.getPaletteSlot("Shape"));
    }

    @Test
    void overflowingOneRowSelectionGetsANewRowAndPushesRowsBelow() {
        Tileset tileset = tilesetWithFolder(8);
        Tile a = add(tileset, 5);
        Tile b = add(tileset, 6);
        Tile c = add(tileset, 7);
        Tile belowLeft = add(tileset, 8);
        Tile belowRight = add(tileset, 9);
        Tile aCopy = duplicate(tileset, a);
        Tile bCopy = duplicate(tileset, b);
        Tile cCopy = duplicate(tileset, c);

        TileFolderDuplicatePlanner.apply(tileset, Arrays.asList(a, b, c),
                Arrays.asList(aCopy, bCopy, cCopy));

        assertEquals(13, aCopy.getPaletteSlot("Shape"));
        assertEquals(14, bCopy.getPaletteSlot("Shape"));
        assertEquals(15, cCopy.getPaletteSlot("Shape"));
        assertEquals(16, belowLeft.getPaletteSlot("Shape"));
        assertEquals(17, belowRight.getPaletteSlot("Shape"));
    }

    @Test
    void multiRowSelectionKeepsItsShapeAndPushesLowerShapeAsAUnit() {
        Tileset tileset = tilesetWithFolder(8);
        Tile topLeft = add(tileset, 0);
        Tile topRight = add(tileset, 1);
        Tile bottomLeft = add(tileset, 8);
        Tile bottomRight = add(tileset, 9);
        Tile lowerLeft = add(tileset, 16);
        Tile lowerRight = add(tileset, 17);
        Tile copy0 = duplicate(tileset, topLeft);
        Tile copy1 = duplicate(tileset, topRight);
        Tile copy2 = duplicate(tileset, bottomLeft);
        Tile copy3 = duplicate(tileset, bottomRight);

        TileFolderDuplicatePlanner.apply(tileset,
                Arrays.asList(topLeft, topRight, bottomLeft, bottomRight),
                Arrays.asList(copy0, copy1, copy2, copy3));

        assertEquals(16, copy0.getPaletteSlot("Shape"));
        assertEquals(17, copy1.getPaletteSlot("Shape"));
        assertEquals(24, copy2.getPaletteSlot("Shape"));
        assertEquals(25, copy3.getPaletteSlot("Shape"));
        assertEquals(32, lowerLeft.getPaletteSlot("Shape"));
        assertEquals(33, lowerRight.getPaletteSlot("Shape"));
    }

    private static Tileset tilesetWithFolder(int columns) {
        Tileset tileset = new Tileset();
        PaletteFolder folder = tileset.getOrCreatePaletteFolder("Shape");
        folder.setColumns(columns);
        folder.setRows(4);
        return tileset;
    }

    private static Tile add(Tileset tileset, int slot) {
        Tile tile = new Tile();
        tile.setTileset(tileset);
        tile.setWidth(1);
        tile.setHeight(1);
        tile.addPaletteFolder("Shape", slot);
        tile.objDataToGlData();
        tileset.addTile(tile);
        return tile;
    }

    private static Tile duplicate(Tileset tileset, Tile source) {
        Tile copy = source.clone();
        tileset.addTile(copy);
        return copy;
    }
}
