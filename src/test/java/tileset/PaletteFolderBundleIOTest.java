package tileset;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void selectedSubfolderImportsOnlyItsTilesAndRequiredParents() throws Exception {
        Tileset source = new Tileset();
        PaletteFolder root = source.getOrCreatePaletteFolderWithParents("Terrain");
        source.getOrCreatePaletteFolderWithParents("Terrain/Grass");
        source.getOrCreatePaletteFolderWithParents("Terrain/Rock");
        source.getOrCreatePaletteFolderWithParents("Terrain/Unused");
        source.addTile(createTile(source, "Terrain/Grass", 0, 1));
        source.addTile(createTile(source, "Terrain/Grass", 1, 1));
        source.addTile(createTile(source, "Terrain/Rock", 0, 2));

        Path bundle = tempDir.resolve("folders." + PaletteFolderBundleIO.EXTENSION);
        PaletteFolderBundleIO.write(bundle.toFile(), source, root);

        Tileset target = new Tileset();
        PaletteFolderBundleIO.read(bundle.toFile(), target, Set.of(0, 1));

        assertEquals(2, target.size());
        assertNotNull(target.getPaletteFolder("Terrain"));
        assertNotNull(target.getPaletteFolder("Terrain/Grass"));
        assertNull(target.getPaletteFolder("Terrain/Rock"));
        assertNull(target.getPaletteFolder("Terrain/Unused"));
    }

    @Test
    void tileChosenFromOneFolderDoesNotImportItsOtherPlacements() throws Exception {
        Tileset source = new Tileset();
        PaletteFolder root = source.getOrCreatePaletteFolderWithParents("Terrain");
        source.getOrCreatePaletteFolderWithParents("Terrain/Grass");
        source.getOrCreatePaletteFolderWithParents("Terrain/Favorites");
        Tile tile = createTile(source, "Terrain/Grass", 0, 1);
        tile.addPaletteFolder("Terrain/Favorites", 0);
        source.addTile(tile);

        Path bundle = tempDir.resolve("placements." + PaletteFolderBundleIO.EXTENSION);
        PaletteFolderBundleIO.write(bundle.toFile(), source, root);

        Tileset target = new Tileset();
        PaletteFolderBundleIO.readWithChoices(bundle.toFile(), target,
                Collections.singleton(0), Collections.emptyMap(),
                Collections.singletonMap(0, Set.of("Terrain/Grass")));

        assertNotNull(target.getPaletteFolder("Terrain/Grass"));
        assertNull(target.getPaletteFolder("Terrain/Favorites"));
        assertTrue(target.get(0).isInPaletteFolder("Terrain/Grass"));
        assertFalse(target.get(0).isInPaletteFolder("Terrain/Favorites"));
    }

    @Test
    void overwriteReplacesEditsAtStableIndexAndKeepsLocalFolderMemberships()
            throws Exception {
        Tileset source = new Tileset();
        PaletteFolder folder = source.getOrCreatePaletteFolderWithParents("Terrain");
        Tile incoming = createTile(source, "Terrain", 0, 1);
        incoming.setPaletteName("Bundle version");
        source.addTile(incoming);

        Path bundle = tempDir.resolve("overwrite." + PaletteFolderBundleIO.EXTENSION);
        PaletteFolderBundleIO.write(bundle.toFile(), source, folder);
        Tileset preview = PaletteFolderBundleIO.preview(bundle.toFile());

        Tileset target = new Tileset();
        target.getOrCreatePaletteFolder("Local");
        Tile edited = preview.get(0).clone();
        edited.setPaletteFolder("Local");
        edited.setPaletteSlot(3);
        edited.setPaletteName("My edited version");
        target.addTile(edited);

        Map<Integer, Integer> matches = PaletteFolderBundleIO.findDuplicateIndices(
                preview, target, Collections.singleton(0));
        assertEquals(Integer.valueOf(0), matches.get(0));
        Map<Integer, PaletteFolderBundleIO.DuplicateChoice> choices = new LinkedHashMap<>();
        choices.put(0, PaletteFolderBundleIO.DuplicateChoice.OVERWRITE);

        PaletteFolderBundleIO.ImportResult result =
                PaletteFolderBundleIO.readWithChoices(bundle.toFile(), target,
                        Collections.singleton(0), choices);

        assertEquals(1, target.size());
        assertEquals("Bundle version", target.get(0).getPaletteName());
        assertTrue(target.get(0).isInPaletteFolder("Local"));
        assertTrue(target.get(0).isInPaletteFolder("Terrain"));
        assertEquals(0, result.getAdded());
        assertEquals(1, result.getOverwritten());
        assertEquals(0, result.getKept());
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
