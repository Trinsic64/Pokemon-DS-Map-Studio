package tileset;

import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteFolderBundleIOTest {

    @TempDir
    Path tempDir;

    @Test
    void previewAndSelectiveImportOnlyAddChosenTiles() throws Exception {
        Tileset source = new Tileset();
        PaletteFolder folder = source.getOrCreatePaletteFolderWithParents("Terrain");
        source.addTile(createTile(source, "Terrain", 0, 1));
        source.addTile(createTile(source, "Terrain", 1, 2));

        Path bundle = tempDir.resolve("terrain." + PaletteFolderBundleIO.EXTENSION);
        PaletteFolderBundleIO.write(bundle.toFile(), source, folder);

        Tileset preview = PaletteFolderBundleIO.preview(bundle.toFile());
        assertEquals(2, preview.size());

        Tileset target = new Tileset();
        int added = PaletteFolderBundleIO.read(
                bundle.toFile(), target, Collections.singleton(1));

        assertEquals(1, added);
        assertEquals(1, target.size());
        assertEquals(2, target.get(0).getWidth());
        assertEquals(Integer.valueOf(1),
                target.get(0).getPaletteFolderSlots().get("Terrain"));
    }

    private static Tile createTile(Tileset tileset, String folder, int slot, int width) {
        Tile tile = new Tile();
        tile.setTileset(tileset);
        tile.setWidth(width);
        tile.setHeight(1);
        tile.setObjFilename("tile-" + slot + ".obj");
        tile.addPaletteFolder(folder, slot);
        tile.objDataToGlData();
        return tile;
    }
}
