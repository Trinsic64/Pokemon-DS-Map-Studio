package tileset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteFolderBehaviorTest {

    @TempDir
    Path tempDir;

    @Test
    void sortingDirectChildrenDoesNotMoveParentDescendantsOrUnrelatedFolders() {
        Tileset tileset = new Tileset();
        PaletteFolder parent = tileset.getOrCreatePaletteFolder("Terrain");
        PaletteFolder beta = tileset.getOrCreatePaletteFolder("Terrain/Beta");
        PaletteFolder unrelated = tileset.getOrCreatePaletteFolder("Buildings");
        PaletteFolder grandchild = tileset.getOrCreatePaletteFolder("Terrain/Beta/Inner");
        PaletteFolder alpha = tileset.getOrCreatePaletteFolder("Terrain/Alpha");

        int parentPosition = tileset.getPaletteFolders().indexOf(parent);
        int unrelatedPosition = tileset.getPaletteFolders().indexOf(unrelated);
        int grandchildPosition = tileset.getPaletteFolders().indexOf(grandchild);

        tileset.sortDirectSubfolders("Terrain", Comparator.comparing(
                PaletteFolder::getPath, String.CASE_INSENSITIVE_ORDER));

        ArrayList<PaletteFolder> children = tileset.getDirectSubfolders("Terrain");
        assertEquals("Terrain/Alpha", children.get(0).getPath());
        assertEquals("Terrain/Beta", children.get(1).getPath());
        assertEquals(parentPosition, tileset.getPaletteFolders().indexOf(parent));
        assertEquals(unrelatedPosition, tileset.getPaletteFolders().indexOf(unrelated));
        assertEquals(grandchildPosition, tileset.getPaletteFolders().indexOf(grandchild));
        assertSame(alpha, tileset.getPaletteFolders().get(1));
        assertSame(beta, tileset.getPaletteFolders().get(4));
    }

    @Test
    void favoritesArePaletteOccurrencesAndSurviveMetadataRoundTrip() throws Exception {
        Tileset source = new Tileset();
        Tile favorite = createTile(source);
        source.getOrCreatePaletteFolder("Terrain");
        favorite.addPaletteFolder("Terrain", 2);
        source.addTile(favorite);
        source.setFavorite(favorite, true);

        assertTrue(source.isFavorite(favorite));
        assertTrue(favorite.isInPaletteFolder(PaletteFolder.FAVORITES));
        assertEquals(0, favorite.getPaletteSlot(PaletteFolder.FAVORITES));
        assertTrue(favorite.isInPaletteFolder("Terrain"));
        assertEquals(PaletteFolder.FAVORITES,
                source.getPaletteFolders().get(0).getPath());

        Path metadata = tempDir.resolve("favorites.meta");
        TileMetadataIO.writeFile(metadata.toString(), source);
        Tileset loaded = new Tileset();
        loaded.addTile(createTile(loaded));
        TileMetadataIO.readFile(metadata.toString(), loaded, true);

        assertTrue(loaded.isFavorite(loaded.get(0)));
        assertTrue(loaded.get(0).isInPaletteFolder(PaletteFolder.FAVORITES));

        loaded.setFavorite(loaded.get(0), false);
        assertFalse(loaded.isFavorite(loaded.get(0)));
    }

    private static Tile createTile(Tileset tileset) {
        Tile tile = new Tile();
        tile.setTileset(tileset);
        tile.setWidth(1);
        tile.setHeight(1);
        tile.objDataToGlData();
        return tile;
    }
}
