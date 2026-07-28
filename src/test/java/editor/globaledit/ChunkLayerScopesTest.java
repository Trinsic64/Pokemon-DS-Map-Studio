package editor.globaledit;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLayerScopesTest {

    @Test
    void newChunksStartWithNoEditLayersSelected() {
        ChunkLayerScopes scopes = new ChunkLayerScopes();
        Point first = new Point(2, 8);

        scopes.synchronize(Set.of(first));

        assertArrayEquals(new int[0], scopes.getLayers(first));
    }

    @Test
    void chunksKeepIndependentLayerSelections() {
        ChunkLayerScopes scopes = new ChunkLayerScopes();
        Point first = new Point(2, 8);
        Point second = new Point(3, 8);
        scopes.synchronize(new LinkedHashSet<>(Set.of(first, second)));

        scopes.setLayers(first, 1, 4);
        scopes.setLayers(second, 2, 7);

        assertArrayEquals(new int[]{1, 4}, scopes.getLayers(first));
        assertArrayEquals(new int[]{2, 7}, scopes.getLayers(second));
        assertTrue(scopes.isSelected(first, 4));
        assertFalse(scopes.isSelected(second, 4));
        assertEquals(Set.of(1, 2, 4, 7),
                scopes.getLayerUnion(Set.of(first, second)));
        assertEquals(4, scopes.countSelectedLayers(Set.of(first, second)));
    }

    @Test
    void deselectedChunksAreRemovedFromTheScopeModel() {
        ChunkLayerScopes scopes = new ChunkLayerScopes();
        Point first = new Point(2, 8);
        Point second = new Point(3, 8);
        scopes.synchronize(new LinkedHashSet<>(Set.of(first, second)));

        scopes.synchronize(Set.of(second));

        assertFalse(scopes.contains(first));
        assertTrue(scopes.contains(second));
    }
}
