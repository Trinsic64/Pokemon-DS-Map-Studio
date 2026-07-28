package editor.globaledit;

import editor.grid.MapGrid;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stores the independently selected edit layers for each chosen matrix chunk.
 */
final class ChunkLayerScopes {

    private final int defaultLayer;
    private final Map<Point, boolean[]> scopes = new LinkedHashMap<>();

    ChunkLayerScopes(int defaultLayer) {
        this.defaultLayer = Math.max(0,
                Math.min(defaultLayer, MapGrid.numLayers - 1));
    }

    void synchronize(Set<Point> selectedMaps) {
        scopes.keySet().removeIf(point -> !selectedMaps.contains(point));
        for (Point point : selectedMaps) {
            scopes.computeIfAbsent(new Point(point), ignored -> defaultScope());
        }
    }

    boolean contains(Point point) {
        return point != null && scopes.containsKey(point);
    }

    boolean isSelected(Point point, int layer) {
        boolean[] scope = scopes.get(point);
        return scope != null && validLayer(layer) && scope[layer];
    }

    void setSelected(Point point, int layer, boolean selected) {
        if (point == null || !validLayer(layer)) {
            return;
        }
        boolean[] scope = scopes.get(point);
        if (scope != null) {
            scope[layer] = selected;
        }
    }

    void setAll(Point point, boolean selected) {
        boolean[] scope = scopes.get(point);
        if (scope == null) {
            return;
        }
        for (int layer = 0; layer < scope.length; layer++) {
            scope[layer] = selected;
        }
    }

    void setLayers(Point point, int... layers) {
        if (point == null) {
            return;
        }
        boolean[] scope = new boolean[MapGrid.numLayers];
        for (int layer : layers) {
            if (validLayer(layer)) {
                scope[layer] = true;
            }
        }
        scopes.put(new Point(point), scope);
    }

    int[] getLayers(Point point) {
        boolean[] scope = scopes.get(point);
        if (scope == null) {
            return new int[0];
        }
        int count = 0;
        for (boolean selected : scope) {
            if (selected) {
                count++;
            }
        }
        int[] layers = new int[count];
        int next = 0;
        for (int layer = 0; layer < scope.length; layer++) {
            if (scope[layer]) {
                layers[next++] = layer;
            }
        }
        return layers;
    }

    Set<Integer> getLayerUnion(Set<Point> maps) {
        LinkedHashSet<Integer> layers = new LinkedHashSet<>();
        for (Point point : maps) {
            for (int layer : getLayers(point)) {
                layers.add(layer);
            }
        }
        return layers;
    }

    int countSelectedLayers(Set<Point> maps) {
        int count = 0;
        for (Point point : maps) {
            count += getLayers(point).length;
        }
        return count;
    }

    private boolean[] defaultScope() {
        boolean[] scope = new boolean[MapGrid.numLayers];
        scope[defaultLayer] = true;
        return scope;
    }

    private static boolean validLayer(int layer) {
        return layer >= 0 && layer < MapGrid.numLayers;
    }
}
