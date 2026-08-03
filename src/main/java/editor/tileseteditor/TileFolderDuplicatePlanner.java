package editor.tileseteditor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tileset.PaletteFolder;
import tileset.Tile;
import tileset.Tileset;

/**
 * Places Tile Editor duplicates without allowing them to cover another palette
 * occurrence.  A one-row selection is copied to its right when there is room;
 * larger shapes are copied below as a block and the rows below are shifted.
 */
final class TileFolderDuplicatePlanner {

    private TileFolderDuplicatePlanner() {
    }

    static void apply(Tileset tileset, List<Tile> sources, List<Tile> duplicates) {
        Map<String, Plan> plans = buildPlans(tileset, sources);
        //Tile equality intentionally compares tile data, so equal-looking
        //tiles must still be treated as distinct palette objects here.
        Set<Tile> sourceSet = Collections.newSetFromMap(new IdentityHashMap<>());
        sourceSet.addAll(sources);
        Set<Tile> duplicateSet = Collections.newSetFromMap(new IdentityHashMap<>());
        duplicateSet.addAll(duplicates);
        for (Map.Entry<String, Plan> entry : plans.entrySet()) {
            applyPlan(tileset, entry.getKey(), entry.getValue(), sources,
                    duplicates, sourceSet, duplicateSet);
        }
    }

    private static Map<String, Plan> buildPlans(Tileset tileset, List<Tile> sources) {
        Map<String, Plan> plans = new LinkedHashMap<>();
        for (Tile source : sources) {
            for (Map.Entry<String, Integer> membership
                    : source.getPaletteFolderSlots().entrySet()) {
                PaletteFolder folder = tileset.getPaletteFolder(membership.getKey());
                if (folder == null || folder.getColumns() <= 0 || membership.getValue() < 0) {
                    continue;
                }
                plans.computeIfAbsent(membership.getKey(), ignored -> new Plan(folder))
                        .add(source, membership.getValue());
            }
        }
        return plans;
    }

    private static void applyPlan(Tileset tileset, String path, Plan plan,
            List<Tile> sources, List<Tile> duplicates, Set<Tile> sourceSet,
            Set<Tile> duplicateSet) {
        int columns = plan.folder.getColumns();
        if (plan.canPlaceBeside(tileset, path, sourceSet, duplicateSet)) {
            int targetColumn = plan.maxRight;
            for (int i = 0; i < sources.size(); i++) {
                Integer slot = plan.sourceSlots.get(sources.get(i));
                if (slot == null) {
                    continue;
                }
                int sourceColumn = slot % columns;
                int duplicateColumn = targetColumn + sourceColumn - plan.minColumn;
                duplicates.get(i).setPaletteSlot(path,
                        plan.minRow * columns + duplicateColumn);
            }
        } else {
            int insertionRow = plan.maxBottom;
            boolean boundaryMoved;
            do {
                boundaryMoved = false;
                for (Tile tile : tileset.getTiles()) {
                    if (sourceSet.contains(tile) || duplicateSet.contains(tile)) {
                        continue;
                    }
                    int slot = tile.getPaletteSlot(path);
                    if (slot < 0) {
                        continue;
                    }
                    int row = slot / columns;
                    int bottom = row + Math.max(1, tile.getPaletteDisplayHeight());
                    if (row < insertionRow && bottom > insertionRow) {
                        insertionRow = bottom;
                        boundaryMoved = true;
                    }
                }
            } while (boundaryMoved);

            int blockHeight = Math.max(1, plan.maxBottom - plan.minRow);
            for (Tile tile : tileset.getTiles()) {
                if (sourceSet.contains(tile) || duplicateSet.contains(tile)) {
                    continue;
                }
                int slot = tile.getPaletteSlot(path);
                if (slot >= 0 && slot / columns >= insertionRow) {
                    tile.setPaletteSlot(path, slot + blockHeight * columns);
                }
            }
            for (int i = 0; i < sources.size(); i++) {
                Integer slot = plan.sourceSlots.get(sources.get(i));
                if (slot == null) {
                    continue;
                }
                int sourceRow = slot / columns;
                int sourceColumn = slot % columns;
                duplicates.get(i).setPaletteSlot(path,
                        (insertionRow + sourceRow - plan.minRow) * columns
                                + sourceColumn);
            }
        }

        int requiredRows = plan.folder.getRows();
        for (Tile tile : tileset.getTiles()) {
            int slot = tile.getPaletteSlot(path);
            if (slot >= 0) {
                requiredRows = Math.max(requiredRows, slot / columns
                        + Math.max(1, tile.getPaletteDisplayHeight()));
            }
        }
        plan.folder.setRows(requiredRows);
        plan.folder.setCollapsed(false);
    }

    private static final class Plan {
        final PaletteFolder folder;
        final Map<Tile, Integer> sourceSlots = new IdentityHashMap<>();
        int minRow = Integer.MAX_VALUE;
        int maxStartRow = Integer.MIN_VALUE;
        int minColumn = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        int maxBottom = Integer.MIN_VALUE;

        Plan(PaletteFolder folder) {
            this.folder = folder;
        }

        void add(Tile tile, int slot) {
            sourceSlots.put(tile, slot);
            int row = slot / folder.getColumns();
            int column = slot % folder.getColumns();
            minRow = Math.min(minRow, row);
            maxStartRow = Math.max(maxStartRow, row);
            minColumn = Math.min(minColumn, column);
            maxRight = Math.max(maxRight,
                    column + Math.max(1, tile.getPaletteDisplayWidth()));
            maxBottom = Math.max(maxBottom,
                    row + Math.max(1, tile.getPaletteDisplayHeight()));
        }

        boolean canPlaceBeside(Tileset tileset, String path, Set<Tile> sourceSet,
                Set<Tile> duplicateSet) {
            if (minRow != maxStartRow) {
                return false;
            }
            int blockWidth = maxRight - minColumn;
            if (maxRight + blockWidth > folder.getColumns()) {
                return false;
            }
            ArrayList<Rectangle> proposed = new ArrayList<>();
            for (Map.Entry<Tile, Integer> entry : sourceSlots.entrySet()) {
                int slot = entry.getValue();
                int sourceColumn = slot % folder.getColumns();
                Rectangle bounds = bounds(entry.getKey(),
                        maxRight + sourceColumn - minColumn, minRow);
                for (Rectangle other : proposed) {
                    if (bounds.intersects(other)) {
                        return false;
                    }
                }
                proposed.add(bounds);
            }
            for (Tile tile : tileset.getTiles()) {
                if (sourceSet.contains(tile) || duplicateSet.contains(tile)) {
                    continue;
                }
                int slot = tile.getPaletteSlot(path);
                if (slot < 0) {
                    continue;
                }
                Rectangle occupied = bounds(tile, slot % folder.getColumns(),
                        slot / folder.getColumns());
                for (Rectangle candidate : proposed) {
                    if (candidate.intersects(occupied)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static Rectangle bounds(Tile tile, int column, int row) {
            return new Rectangle(column, row,
                    Math.max(1, tile.getPaletteDisplayWidth()),
                    Math.max(1, tile.getPaletteDisplayHeight()));
        }
    }
}
