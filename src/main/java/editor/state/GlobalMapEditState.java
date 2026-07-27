package editor.state;

import editor.grid.MapGrid;
import editor.handler.MapData;
import editor.handler.MapEditorHandler;

import java.awt.Point;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Undo snapshot for one matrix-scoped edit across selected maps and layers. */
public final class GlobalMapEditState extends MapState {

    private final MapEditorHandler handler;
    private final LinkedHashSet<Point> mapCoords = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> layerIndices = new LinkedHashSet<>();
    private final HashMap<Integer, HashMap<Point, int[][]>> tileLayers = new HashMap<>();
    private final HashMap<Integer, HashMap<Point, int[][]>> heightLayers = new HashMap<>();
    private final HashMap<Point, byte[][][]> collisionLayers = new HashMap<>();

    public GlobalMapEditState(String name, MapEditorHandler handler, Set<Point> maps,
                              int... layers) {
        super(name);
        this.handler = handler;
        for (Point point : maps) {
            if (handler.getMapMatrix().getMap(point) != null) {
                mapCoords.add(new Point(point));
            }
        }
        for (int layer : layers) {
            if (layer >= 0 && layer < MapGrid.numLayers) {
                layerIndices.add(layer);
            }
        }
        if (layerIndices.isEmpty()) {
            layerIndices.add(handler.getActiveLayerIndex());
        }
        capture();
    }

    private void capture() {
        for (Integer layer : layerIndices) {
            HashMap<Point, int[][]> savedTiles = new HashMap<>();
            HashMap<Point, int[][]> savedHeights = new HashMap<>();
            for (Point point : mapCoords) {
                MapData mapData = handler.getMapMatrix().getMap(point);
                if (mapData != null) {
                    savedTiles.put(new Point(point), mapData.getGrid().cloneTileLayer(layer));
                    savedHeights.put(new Point(point), mapData.getGrid().cloneHeightLayer(layer));
                }
            }
            tileLayers.put(layer, savedTiles);
            heightLayers.put(layer, savedHeights);
        }
        for (Point point : mapCoords) {
            MapData mapData = handler.getMapMatrix().getMap(point);
            if (mapData != null) {
                collisionLayers.put(new Point(point), cloneCollisionLayers(mapData));
            }
        }
    }

    @Override
    public void revertState() {
        for (Integer layer : layerIndices) {
            for (Map.Entry<Point, int[][]> entry : tileLayers.get(layer).entrySet()) {
                handler.getMapMatrix().getMapAndCreate(entry.getKey()).getGrid()
                        .setTileLayer(layer, entry.getValue());
            }
            for (Map.Entry<Point, int[][]> entry : heightLayers.get(layer).entrySet()) {
                handler.getMapMatrix().getMapAndCreate(entry.getKey()).getGrid()
                        .setHeightLayer(layer, entry.getValue());
            }
        }
        for (Map.Entry<Point, byte[][][]> entry : collisionLayers.entrySet()) {
            MapData mapData = handler.getMapMatrix().getMapAndCreate(entry.getKey());
            byte[][][] savedLayers = entry.getValue();
            for (int layer = 0;
                 layer < savedLayers.length && layer < mapData.getCollisions().getNumLayers();
                 layer++) {
                mapData.getCollisions().setLayer(layer, cloneLayer(savedLayers[layer]));
            }
        }
    }

    @Override
    public MapState captureCurrentState() {
        return new GlobalMapEditState("Map Edit", handler, mapCoords,
                layerIndices.stream().mapToInt(Integer::intValue).toArray());
    }

    @Override
    public Set<Point> getAffectedMaps() {
        LinkedHashSet<Point> copy = new LinkedHashSet<>();
        for (Point point : mapCoords) {
            copy.add(new Point(point));
        }
        return copy;
    }

    @Override
    public Set<Integer> getAffectedLayers() {
        return new LinkedHashSet<>(layerIndices);
    }

    private static byte[][][] cloneCollisionLayers(MapData mapData) {
        int count = mapData.getCollisions().getNumLayers();
        byte[][][] copy = new byte[count][][];
        for (int layer = 0; layer < count; layer++) {
            copy[layer] = mapData.getCollisions().cloneLayer(layer);
        }
        return copy;
    }

    private static byte[][] cloneLayer(byte[][] layer) {
        byte[][] copy = new byte[layer.length][];
        for (int i = 0; i < layer.length; i++) {
            copy[i] = Arrays.copyOf(layer[i], layer[i].length);
        }
        return copy;
    }
}
