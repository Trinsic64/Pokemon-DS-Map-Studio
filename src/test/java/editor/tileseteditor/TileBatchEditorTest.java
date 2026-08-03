package editor.tileseteditor;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tileset.Tile;
import tileset.Tileset;
import tileset.TilesetMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileBatchEditorTest {

    @Test
    void materialReplacementTouchesOnlySelectedTilesThatUseSourceMaterial() {
        Tileset tileset = new Tileset();
        tileset.getMaterials().add(new TilesetMaterial());
        tileset.getMaterials().add(new TilesetMaterial());
        tileset.getMaterials().add(new TilesetMaterial());
        Tile first = add(tileset, 0, 1, 1);
        Tile second = add(tileset, 1, 2);
        Tile selectedWithoutMaterial = add(tileset, 2);
        Tile notSelected = add(tileset, 1);

        assertEquals(Arrays.asList(0, 1), TileBatchEditor.replaceMaterial(
                tileset, Arrays.asList(0, 1, 2), 1, 2));

        assertEquals(Arrays.asList(0, 2, 2), first.getTextureIDs());
        assertEquals(Arrays.asList(2, 2), second.getTextureIDs());
        assertEquals(Arrays.asList(2), selectedWithoutMaterial.getTextureIDs());
        assertEquals(Arrays.asList(1), notSelected.getTextureIDs());
    }

    private static Tile add(Tileset tileset, Integer... materials) {
        Tile tile = new Tile();
        tile.setTileset(tileset);
        tile.getTextureIDs().addAll(Arrays.asList(materials));
        tileset.addTile(tile);
        return tile;
    }
}
