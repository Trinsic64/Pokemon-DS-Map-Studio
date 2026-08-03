package tileset;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
