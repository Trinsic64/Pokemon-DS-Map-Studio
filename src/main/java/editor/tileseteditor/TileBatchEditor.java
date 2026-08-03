package editor.tileseteditor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import tileset.Tile;
import tileset.Tileset;

/** Non-UI bulk tile operations shared by the Tile Editor and its tests. */
final class TileBatchEditor {

    private TileBatchEditor() {
    }

    /**
     * Replaces every occurrence of {@code oldMaterial} only on the selected
     * tiles. Selected tiles that do not use it are deliberately left alone.
     */
    static ArrayList<Integer> replaceMaterial(Tileset tileset,
            List<Integer> selectedIndices, int oldMaterial, int newMaterial) {
        ArrayList<Integer> affected = new ArrayList<>();
        if (oldMaterial == newMaterial || newMaterial < 0
                || newMaterial >= tileset.getMaterials().size()) {
            return affected;
        }
        for (int index : new LinkedHashSet<>(selectedIndices)) {
            if (index < 0 || index >= tileset.size()) {
                continue;
            }
            Tile tile = tileset.get(index);
            boolean changed = false;
            for (int i = 0; i < tile.getTextureIDs().size(); i++) {
                if (tile.getTextureIDs().get(i) == oldMaterial) {
                    tile.getTextureIDs().set(i, newMaterial);
                    changed = true;
                }
            }
            if (changed) {
                affected.add(index);
            }
        }
        return affected;
    }
}
