package editor.globaledit;

import editor.grid.MapGrid;
import editor.handler.MapData;

import java.awt.Point;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Data-only bulk map operations shared by the global editor UI. */
public final class GlobalMapOperations {

    private GlobalMapOperations() {
    }

    public static int replaceTiles(Map<Point, MapData> matrix, Set<Point> selectedMaps,
                                   int[] layers, int fromTile, int toTile, boolean swap) {
        return replaceTiles(matrix, selectedMaps, layers, fromTile, toTile, swap,
                Collections.emptySet());
    }

    /**
     * Replaces matching cells except for occurrences explicitly excluded by
     * the Map Preview. An empty exclusion set preserves the original global
     * replace behavior.
     */
    public static int replaceTiles(Map<Point, MapData> matrix, Set<Point> selectedMaps,
                                   int[] layers, int fromTile, int toTile, boolean swap,
                                   Set<TileCell> excludedCells) {
        int changed = 0;
        for (Point mapCoords : selectedMaps) {
            MapData mapData = matrix.get(mapCoords);
            if (mapData == null) {
                continue;
            }
            changed += replaceTiles(mapData.getGrid(), mapCoords, layers,
                    fromTile, toTile, swap, excludedCells);
        }
        return changed;
    }

    static int replaceTiles(MapGrid grid, Point mapCoords, int[] layers,
                            int fromTile, int toTile, boolean swap,
                            Set<TileCell> excludedCells) {
        int changed = 0;
        for (int layer : layers) {
            int[][] tiles = grid.tileLayers[layer];
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    int current = tiles[x][y];
                    boolean match = current == fromTile || (swap && current == toTile);
                    if (!match || isExcluded(excludedCells, mapCoords, layer, x, y)) {
                        continue;
                    }
                    tiles[x][y] = current == fromTile ? toTile : fromTile;
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean isExcluded(Set<TileCell> excludedCells, Point mapCoords,
                                      int layer, int x, int y) {
        return excludedCells != null && !excludedCells.isEmpty()
                && excludedCells.contains(new TileCell(mapCoords, layer, x, y));
    }

    static int replaceTiles(MapGrid grid, int[] layers, int fromTile, int toTile, boolean swap) {
        int changed = 0;
        for (int layer : layers) {
            int[][] tiles = grid.tileLayers[layer];
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    int current = tiles[x][y];
                    if (current == fromTile) {
                        tiles[x][y] = toTile;
                        changed++;
                    } else if (swap && current == toTile) {
                        tiles[x][y] = fromTile;
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    public static int copyLayer(Map<Point, MapData> matrix, Set<Point> selectedMaps,
                                int sourceLayer, int targetLayer,
                                boolean copyTiles, boolean copyHeights) {
        if (sourceLayer == targetLayer || (!copyTiles && !copyHeights)) {
            return 0;
        }
        int changed = 0;
        for (Point mapCoords : selectedMaps) {
            MapData mapData = matrix.get(mapCoords);
            if (mapData == null) {
                continue;
            }
            changed += copyLayer(mapData.getGrid(), sourceLayer, targetLayer,
                    copyTiles, copyHeights);
        }
        return changed;
    }

    static int copyLayer(MapGrid grid, int sourceLayer, int targetLayer,
                         boolean copyTiles, boolean copyHeights) {
        if (sourceLayer == targetLayer || (!copyTiles && !copyHeights)) {
            return 0;
        }
        int changed = 0;
        if (copyTiles) {
            changed += countDifferences(grid.tileLayers[sourceLayer], grid.tileLayers[targetLayer]);
            grid.tileLayers[targetLayer] = grid.cloneTileLayer(sourceLayer);
        }
        if (copyHeights) {
            changed += countDifferences(grid.heightLayers[sourceLayer], grid.heightLayers[targetLayer]);
            grid.heightLayers[targetLayer] = grid.cloneHeightLayer(sourceLayer);
        }
        return changed;
    }

    public static int clearLayers(Map<Point, MapData> matrix, Set<Point> selectedMaps,
                                  int[] layers, boolean clearTiles, boolean resetHeights) {
        int changed = 0;
        for (Point mapCoords : selectedMaps) {
            MapData mapData = matrix.get(mapCoords);
            if (mapData == null) {
                continue;
            }
            changed += clearLayers(mapData.getGrid(), layers, clearTiles, resetHeights);
        }
        return changed;
    }

    static int clearLayers(MapGrid grid, int[] layers,
                           boolean clearTiles, boolean resetHeights) {
        int changed = 0;
        for (int layer : layers) {
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    if (clearTiles && grid.tileLayers[layer][x][y] != -1) {
                        grid.tileLayers[layer][x][y] = -1;
                        changed++;
                    }
                    if (resetHeights && grid.heightLayers[layer][x][y] != 0) {
                        grid.heightLayers[layer][x][y] = 0;
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    public static int adjustHeights(Map<Point, MapData> matrix, Set<Point> selectedMaps,
                                    int[] layers, int amount, int minimum, int maximum,
                                    boolean occupiedTilesOnly) {
        int changed = 0;
        for (Point mapCoords : selectedMaps) {
            MapData mapData = matrix.get(mapCoords);
            if (mapData == null) {
                continue;
            }
            changed += adjustHeights(mapData.getGrid(), layers, amount,
                    minimum, maximum, occupiedTilesOnly);
        }
        return changed;
    }

    static int adjustHeights(MapGrid grid, int[] layers, int amount,
                             int minimum, int maximum, boolean occupiedTilesOnly) {
        int changed = 0;
        for (int layer : layers) {
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    if (occupiedTilesOnly && grid.tileLayers[layer][x][y] < 0) {
                        continue;
                    }
                    int oldHeight = grid.heightLayers[layer][x][y];
                    int newHeight = Math.max(minimum, Math.min(maximum, oldHeight + amount));
                    if (newHeight != oldHeight) {
                        grid.heightLayers[layer][x][y] = newHeight;
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static int countDifferences(int[][] first, int[][] second) {
        int count = 0;
        for (int x = 0; x < first.length; x++) {
            for (int y = 0; y < first[x].length; y++) {
                if (first[x][y] != second[x][y]) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Identifies one tile occurrence inside one matrix chunk and layer. */
    public static final class TileCell {
        public final int mapX;
        public final int mapY;
        public final int layer;
        public final int x;
        public final int y;

        public TileCell(Point map, int layer, int x, int y) {
            this(map.x, map.y, layer, x, y);
        }

        public TileCell(int mapX, int mapY, int layer, int x, int y) {
            this.mapX = mapX;
            this.mapY = mapY;
            this.layer = layer;
            this.x = x;
            this.y = y;
        }

        public Point getMap() {
            return new Point(mapX, mapY);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TileCell)) {
                return false;
            }
            TileCell other = (TileCell) value;
            return mapX == other.mapX && mapY == other.mapY
                    && layer == other.layer && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mapX, mapY, layer, x, y);
        }
    }
}
