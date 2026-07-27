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

/**
 * @author Trifindo
 */
public class MapLayerState extends State {

    private final MapEditorHandler handler;
    private final int[] layerIndices;
    private final boolean fullState;

    private final HashMap<Integer, HashMap<Point, int[][]>> mapTileLayers = new HashMap<>();
    private final HashMap<Integer, HashMap<Point, int[][]>> mapHeightLayers = new HashMap<>();
    private final HashMap<Point, byte[][][]> mapCollisionLayers = new HashMap<>();

    public MapLayerState(String name, MapEditorHandler handler) {
        this(name, handler, true);
    }

    public MapLayerState(String name, MapEditorHandler handler, boolean fullState) {
        this(name, handler, fullState, null, handler.getActiveLayerIndex());
    }

    public MapLayerState(String name, MapEditorHandler handler, int... layerIndices) {
        this(name, handler, true, null, layerIndices);
    }

    public MapLayerState(String name, MapEditorHandler handler, boolean fullState, int... layerIndices) {
        this(name, handler, fullState, null, layerIndices);
    }

    public MapLayerState(String name, MapEditorHandler handler, Set<Point> mapCoords,
                         int... layerIndices) {
        this(name, handler, false, mapCoords, layerIndices);
    }

    private MapLayerState(String name, MapEditorHandler handler, boolean fullState,
                          Set<Point> requestedMaps, int... layerIndices) {
        super(name);
        this.handler = handler;
        this.fullState = fullState;
        this.layerIndices = normalizeLayerIndices(layerIndices, handler.getActiveLayerIndex());

        Set<Point> maps;
        if (requestedMaps != null) {
            maps = requestedMaps;
        } else if (fullState) {
            maps = handler.getMapMatrix().getMatrix().keySet();
        } else {
            maps = java.util.Collections.singleton(handler.getMapSelected());
        }
        for (int layerIndex : this.layerIndices) {
            HashMap<Point, int[][]> tileLayers = new HashMap<>();
            HashMap<Point, int[][]> heightLayers = new HashMap<>();
            for (Point mapCoords : maps) {
                MapData mapData = handler.getMapMatrix().getMap(mapCoords);
                if (mapData != null) {
                    Point key = new Point(mapCoords);
                    tileLayers.put(key, mapData.getGrid().cloneTileLayer(layerIndex));
                    heightLayers.put(key, mapData.getGrid().cloneHeightLayer(layerIndex));
                }
            }
            mapTileLayers.put(layerIndex, tileLayers);
            mapHeightLayers.put(layerIndex, heightLayers);
        }
        for (Point mapCoords : maps) {
            MapData mapData = handler.getMapMatrix().getMap(mapCoords);
            if (mapData != null) {
                mapCollisionLayers.put(new Point(mapCoords), cloneCollisionLayers(mapData));
            }
        }
    }

    @Override
    public void revertState() {
        for (int layerIndex : layerIndices) {
            for (Map.Entry<Point, int[][]> mapEntry : mapTileLayers.get(layerIndex).entrySet()) {
                handler.getMapMatrix().getMapAndCreate(mapEntry.getKey()).getGrid()
                        .setTileLayer(layerIndex, mapEntry.getValue());
            }
            for (Map.Entry<Point, int[][]> mapEntry : mapHeightLayers.get(layerIndex).entrySet()) {
                handler.getMapMatrix().getMapAndCreate(mapEntry.getKey()).getGrid()
                        .setHeightLayer(layerIndex, mapEntry.getValue());
            }
        }
        for (Map.Entry<Point, byte[][][]> mapEntry : mapCollisionLayers.entrySet()) {
            MapData mapData = handler.getMapMatrix().getMapAndCreate(mapEntry.getKey());
            byte[][][] layers = mapEntry.getValue();
            for (int i = 0; i < layers.length && i < mapData.getCollisions().getNumLayers(); i++) {
                mapData.getCollisions().setLayer(i, cloneLayer(layers[i]));
            }
        }

        if (fullState) {
            Set<Point> savedMaps = getKeySet();
            handler.getMapMatrix().getMatrix().entrySet()
                    .removeIf(entry -> !savedMaps.contains(entry.getKey()));
        }
    }

    public void updateState() {
        Point selectedMap = handler.getMapSelected();
        MapData mapData = handler.getMapMatrix().getMap(selectedMap);
        if (mapData == null) {
            return;
        }
        for (int layerIndex : layerIndices) {
            HashMap<Point, int[][]> tileLayers = mapTileLayers.get(layerIndex);
            HashMap<Point, int[][]> heightLayers = mapHeightLayers.get(layerIndex);
            tileLayers.putIfAbsent(new Point(selectedMap), mapData.getGrid().cloneTileLayer(layerIndex));
            heightLayers.putIfAbsent(new Point(selectedMap), mapData.getGrid().cloneHeightLayer(layerIndex));
        }
        mapCollisionLayers.putIfAbsent(new Point(selectedMap), cloneCollisionLayers(mapData));
    }

    private static int[] normalizeLayerIndices(int[] indices, int activeLayer) {
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        if (indices != null) {
            for (int index : indices) {
                if (index >= 0 && index < MapGrid.numLayers) {
                    normalized.add(index);
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(activeLayer);
        }
        return normalized.stream().mapToInt(Integer::intValue).toArray();
    }

    private static byte[][][] cloneCollisionLayers(MapData mapData) {
        int numLayers = mapData.getCollisions().getNumLayers();
        byte[][][] copy = new byte[numLayers][][];
        for (int i = 0; i < numLayers; i++) {
            copy[i] = mapData.getCollisions().cloneLayer(i);
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

    public int getLayerIndex() {
        return layerIndices[0];
    }

    public int[] getLayerIndices() {
        return Arrays.copyOf(layerIndices, layerIndices.length);
    }

    public boolean isFullState() {
        return fullState;
    }

    public Set<Point> getKeySet() {
        return new LinkedHashSet<>(mapCollisionLayers.keySet());
    }
}
