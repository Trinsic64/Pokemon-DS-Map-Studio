# Pokemon DS Map Studio 2.3.5 - Laptop Display Scaling

This patch improves the main editor on laptops using 125% Windows display
scaling and other layouts with less usable screen space.

- Reduced the main window's fixed minimum size so it can fit on narrower
  logical desktops.
- Made the top toolbar, vertical editing tools, and status readouts scroll
  when their contents exceed the available space.
- Kept the map editing area and right-side controls visible at laptop widths
  by allowing the map viewport to shrink and sizing the split pane after layout.

# Pokemon DS Map Studio 2.3.4 - Palette Workflow and Bulk Tile Editing

This patch expands palette-folder organization, makes folder imports safer,
and adds selection-aware editing to the Tileset Editor.

## Folder Import and Organization

- Added a folder-aware, two-pane import selector with native Ctrl/Shift tile
  selection and live new/duplicate counts.
- Added visual duplicate comparisons with per-tile **Keep Original** and
  **Overwrite** choices before import.
- Added a persistent **Favorites** folder. Favoriting a tile adds another
  palette occurrence without removing it from its existing folders.
- Added direct-child **Sort Subfolders** options for A-Z, Z-A, most tiles, and
  fewest tiles while leaving tiles, deeper ordering, and unrelated folders
  unchanged.
- Disabled accidental folder-tile dragging in the main map window while
  retaining palette arrangement tools in the Tileset Editor.

## Tileset Editor

- Material changes now replace the active source material across highlighted
  tiles that use it and skip selected tiles that do not.
- Tile size, offsets, tileability, global texture mapping, and texture scale
  changes now apply consistently across the highlighted tile selection.
- Improved duplicate placement so one-row groups stay on the same row when
  possible, multi-row shapes are preserved, and lower rows are shifted without
  introducing tile overlap.

## Global Map Editor

- Fixed stale viewport blitting that could duplicate Tileset B folder headers
  while scrolling upward through several open subfolders.

# Pokemon DS Map Studio 2.3.3 - Height Transfer and Tile List Polish

This patch makes height-only layer transfers explicit and restores a cleaner
flat transparency backdrop in tile folders.

## Improvements

- Added clear **Tiles only**, **Heights only**, and **Tiles + heights** data
  modes to the Global Layer Editor.
- **Heights only** copies height values from the chosen source layer to the
  target layer without changing tiles in either layer.
- Height-only operations use the existing current/all-selected-chunks scope,
  undo history, live preview highlighting, and target cell-change count.
- Removed the per-tile outline from transparency backdrops in tile folders
  while retaining the flat opaque grey transparency color.
- Kept the tile-list presentation change isolated from Global Map Editor map
  rendering.

# Pokemon DS Map Studio 2.3.2 - Building and Global Editing Release

This release brings the Building Enhancement and Global Map Editor work into
the Trinsic enhanced-tools line as PDSMS v2.3.2.

## Global Editing

- Added the **Global Tile Editor**, **Global Layer Editor**, and
  **Global Height Editor** for visually editing selected Matrix chunks.
- Added tile replacement, deletion through Empty Tile, bidirectional tile
  swaps, layer copy, cut and paste, complete layer swaps, height adjustment,
  and height reset.
- Added live operation diagrams with tile previews, source and target layers,
  match counts, target-change counts, and current/all-chunk scope.
- Added visible Current Map and Preview Map highlighting for affected tiles.
- Added independent L1-L9 scopes for every selected chunk, direct checkbox
  editing, clickable whole-column toggles, and right-click Check all /
  Uncheck all actions.
- Global edits across multiple maps and layers are recorded as one undoable
  operation.

## Tile and Selection Workflow

- Imported tile folders are immediately rendered and can be selectively
  included during import.
- Restored middle-mouse flood fill.
- Separated tile and height clipboard data so normal tile copy/cut/paste
  preserves destination heights.
- Added dedicated tile/height clipboard behavior while Height Edit Mode is
  active.
- Restored a consistent transparency backdrop in standard tile lists without
  changing Global Map Editor rendering.

## Integrated Improvements

- Preserved the safe **Replace and Remap** workflow for project-wide tile-ID
  remapping.
- Included the toolbar, selection, exporter, collision-label, file-dialog,
  dependency, and move-permission fixes integrated by this branch.
- Restricted local JAR loading to the three required bundled libraries so
  stray or legacy JARs cannot alter builds.

# Pokemon DS Map Studio 2.3.1 - Enhanced Tools Release

## Download and Packaging Improvements

- Added a ready-to-run Windows package with its own Java runtime and `.exe` launcher.
- Added a portable ZIP with Windows and Linux/macOS launch scripts.
- Included the expected root-level `converter` folder, Xerces runtime DLLs, and setup instructions.
- Included README and release notes inside each distribution.
- Added automated tagged releases and SHA-256 checksum generation.
- `g3dcvtr.exe` remains user-supplied and is not redistributed.
- Added `xerces-c_2_5_0.dll`, which is required by the tested `g3dcvtr.exe` build; it may coexist with `xerces-c_2_8.dll`.

This release expands Pokemon DS Map Studio with new map-selection tools, Smart Drawing workflows, tile organization, portable metadata, and collision-default editing.

## New Map Selection Tools

- Rectangle selection
- Lasso selection
- Wand selection
- Move selected tiles
- Copy, cut, paste, duplicate, and delete selections
- Rotate and flip selections
- Fill a selection with the currently selected tile
- Selection operations across map boundaries
- Undo support for selection edits

### Selection Shortcuts

| Action | Shortcut |
| --- | --- |
| Select all | `Ctrl+A` |
| Copy | `Ctrl+C` |
| Cut | `Ctrl+X` |
| Paste | `Ctrl+V` |
| Fill selection | `Ctrl+F` |
| Deselect | `Ctrl+D` |
| Delete selection | `Delete` |

Choose a selection tool from the main toolbar, select the required tiles, and then use the toolbar actions, keyboard shortcuts, or selection context menu.

## Smart Tools

- Smart freehand drawing with automatic edges and corners
- Smart line drawing
- Smart rectangle drawing
- Smart circle drawing
- Smart wand selection
- Smart rotation and flipping
- Inverted Smart Drawing shapes using the right mouse button
- Smart Drawing templates generated from arranged folder tiles

Select a Smart Drawing reference, enable **Smart Tools**, and use the normal drawing or shape tools on the map. Draw with the left mouse button for the standard form or the right mouse button for its inverted form.

## Tile Folders

- Create folders and nested subfolders
- Arrange tiles in custom grid layouts
- Keep empty layout spaces without creating placeholder tile IDs
- Drag and drop tiles between folders
- Move or remove tiles using the tile context menu
- Pin folders for access while browsing long tilesets
- Expand, collapse, resize, reorder, and scroll folders
- Give folder tiles independent display sizes without changing game tile dimensions
- Duplicate tile groups while preserving their folder arrangement
- Organize Smart Drawing templates into expandable groups
- Keep folder order independent from the game-facing **All Tiles** order

Right-click a folder or tile in the Tile List to access its organization, layout, display-size, movement, import, and export commands. Folder layouts are available in both the main window and the Tileset Editor.

## Portable Tile Metadata

- Export individual folders as portable bundles
- Import folders without replacing existing folder structures
- Import bundles containing tiles that are not already in the destination tileset
- Preserve folder hierarchy and layout slots
- Preserve tile names and folder display sizes
- Preserve collision defaults
- Preserve Smart Drawing organization

Use the folder context menu to export a folder bundle. Import the bundle into another compatible tileset to add its folder structure and included metadata.

## Collision Defaults

- Define collision defaults for both collision layers
- Create collision footprints larger than a tile's game dimensions
- Use folder display size as the collision-footprint reference
- Paint or clear collision values one cell at a time
- Fill or clear an entire collision layer
- Copy and paste one collision layer
- Copy and paste complete collision defaults
- Edit collision defaults from tile context menus
- Dedicated Collision Defaults workspace in the Tileset Editor
- Automatically apply defaults while drawing with **Auto Coll.**

Open the **Collision Defaults** tab in the Tileset Editor and select a tile from the Tile Selector. Choose a collision value, then left-click or drag to paint cells. Right-click a footprint cell to sample its collision value. Use **Copy Layer**, **Paste Layer**, **Copy All**, and **Paste All** to reuse settings efficiently.

Enable **Auto Coll.** in the main map tools when collision defaults should be applied automatically as tiles are painted.

## Tileset Editor Enhancements

- Multi-tile selection follows the visible folder layout order
- Move and organize folder tiles without changing **All Tiles** ordering
- Duplicate selected tile arrangements into new layout space
- Create Smart Drawing templates from selected folder layouts
- Assign and remove multiple selected tiles from folders
- Edit collision defaults while browsing the tileset

Use the Tile Selector to choose one or more tiles. Folder-based selection and movement operate on the folder arrangement, while **All Tiles** continues to represent the tileset's game-data order.

## Credits

- **Pokemon DS Map Studio:** [AdAstra-LD](https://github.com/AdAstra-LD/Pokemon-DS-Map-Studio) and the original PDSMS contributors
- **Enhanced tools and feature design:** [Trinsic64](https://github.com/Trinsic64)
- **Development assistance:** Claude Code and OpenAI Codex

Pokemon is a trademark of Nintendo, Creatures Inc., and GAME FREAK inc. This is an unofficial community project and is not affiliated with or endorsed by those companies.
