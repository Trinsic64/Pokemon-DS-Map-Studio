# Global Map Editor — Feature and Behaviour Reference

This document defines the current Global Map Editor behaviour and records the
agreed direction for the next Operations redesign. It is intended to keep the
interface, previews, apply scope, and undo behaviour consistent.

## Core Editing Model

The editor answers four separate questions:

1. **Which chunks are in the job?**  
   The cyan-highlighted chunks in the Matrix and the rows in **Selected
   chunks** are the same selection.
2. **Which layers are editable in each chunk?**  
   Every selected chunk stores its own L1–L9 checkboxes.
3. **What should change?**  
   Tile A, Tile B, and the current Operation define the proposed edit.
4. **How widely should it be applied?**  
   A row **Apply** affects one chunk. The bottom **Apply** affects the currently
   shown selected chunk. **Apply All** affects every selected chunk.

Every Apply or Apply All action is recorded as one complete Undo step.

## Matrix

- **Left-click or drag:** select or unselect chunks.
- **Right-click:** preview a chunk without adding it to the editing scope.
- **All / None / Invert:** change the selected chunk set.
- **Zoom controls:** change only the Matrix display size.
- The cyan overlay means a chunk is selected for editing.
- The yellow outline means a chunk is currently shown in Current Map and
  Preview Map.
- A right-clicked preview-only chunk cannot have edit layers assigned and
  cannot be applied until it is selected.

## Selected Chunks Table

Each row represents exactly one selected Matrix chunk.

| Control | Behaviour |
| --- | --- |
| Chunk | Matrix coordinates. Selecting the row opens that chunk in both map views. |
| L1–L9 | Independent edit-layer choices for that row only. |
| Apply | Applies the current Operation only to that row, using its checked layers. |
| Trash icon | Removes the row and unselects the corresponding Matrix chunk. |

The keyboard `Delete` key can remove selected table rows.

Newly selected chunks begin with every edit layer unchecked. Selecting a chunk
therefore opens it for viewing first; the user must explicitly check the layers
that the operation may change.

For operations that use checked layers, a chunk with no checked layers is
skipped by **Apply All**. If no requested chunk has a checked layer, nothing is
applied and the editor reports the missing scope.

## Layer Cards

The left layer column edits and previews the row currently selected in the
Selected Chunks table.

- **Checkbox:** includes that layer in edits for the currently shown selected
  chunk. It updates the matching L1–L9 table checkbox.
- **Eye:** shows or hides the layer in Current Map and Preview Map only. It
  never changes edit scope or exported map data.
- **Yellow card outline:** chooses the layer used by the height-number preview.
- **View All:** shows all layers in map height order.
- **Hide All:** hides all layers in both map previews without changing any
  L1–L9 edit checkbox.
- **Check All / Uncheck:** changes every L1–L9 edit checkbox for the currently
  shown selected chunk only.

Changing to another selected chunk loads that chunk's independent layer choices
into the layer cards.

## Tile A and Tile B

Both tile lists open in the **No Tile Selected** state.

### Tile A — Find

- A normal tile finds occurrences of that tile.
- **Set Empty** finds cells with no tile.
- **Use Main** imports the tile currently selected in the main PDSMS tile list.
- **Unselect** returns to No Tile Selected without choosing Empty.
- **Find** can select chunks using:
  - the current chunk's checked layers; or
  - all layers, assigning each found chunk only the layers in which Tile A was
    actually found.
- **Filter Chunk Tiles** filters the A list to tiles used by the checked layers
  of the selected chunk rows.

### Tile B — Replacement

- A normal tile is the replacement tile.
- **Set Empty** makes Tile B empty and therefore deletes matching Tile A cells.
- **Use Main** imports the main PDSMS tile selection.
- **Unselect** returns to No Tile Selected.

No Tile Selected and Empty Tile are intentionally different states. An
unselected tile blocks Apply; Empty Tile is an explicit editable value.

## Current Map and Preview Map

- **Current Map:** the existing map.
  - Click selects Tile A.
  - Shift-click selects Tile B.
  - Ctrl-click or right-click a matching occurrence includes or excludes it.
- **Preview Map:** the proposed result.
  - Click selects Tile B.
  - Shift-click selects Tile A.
- Hover outlines identify the complete visible tile footprint.
- Included matches use a faint combined highlight rather than a heavy grid of
  1×1 boxes.
- **Show matches** controls the faint persistent match highlight.
- **Reset** removes individual match exclusions.
- Eye visibility affects both previews, but not edit scope.
- Preview compositing follows the main map renderer's effective tile height,
  tile X/Y/Z offsets, and equal-depth layer precedence.

## Apply, Undo, and Close

- **Row Apply:** current operation, one chunk, that row's layers.
- **Apply** (bottom bar): current operation, currently shown selected chunk, that
  chunk's layers.
- **Apply All:** current operation, every selected chunk, each row's own layers.
- **Undo:** undoes the most recent map edit, including a multi-chunk Apply All.
- **Close:** closes the Global Map Editor; already applied work remains
  undoable in the main editor.

Map projects are saved through the normal PDSMS toolbar or File menu.

## Interim Operations

The current Operations area remains functional while its final layout is
designed in the next task.

### Tiles

Currently supports:

- Replace A with B.
- Swap A and B in both directions.
- Delete A by setting B to Empty.
- Fill empty cells by setting A to Empty and choosing a real B.
- Keep existing collision data.
- Rebuild configured smart collision defaults on changed maps.
- Keep height values unchanged.

The independent L1–L9 row scopes and individual match exclusions apply.

### Copy

Currently copies a complete source layer into a target layer inside each
applied chunk. Tile layout and height layout can be copied separately.

This is an interim, out-of-place feature. Its explicit source and target layer
selectors currently take precedence over the L1–L9 row checkboxes. It belongs
in the future **Layer Operations** area.

### Height

Currently raises or lowers cells in each chunk's checked layers, with optional
empty-cell exclusion and supported-range clamping.

This belongs in the future **Layer Operations** area.

### Clear

Currently clears tile data and/or resets height data to zero in each chunk's
checked layers. It requires confirmation.

This will be divided between tile-property choices and Layer Operations during
the Operations redesign.

## Confirmed Direction for the Next Operations Task

### Tile Operation

The minimal Tile Operation should present:

- Replace Tile A with Tile B.
- Collision result:
  - Keep collision properties.
  - Clear collision properties.
  - Replace with smart collision properties.
- Height result:
  - Keep height data.
  - Clear height data.

Delete remains Tile A replaced by Empty Tile B; it does not need a separate
competing workflow.

### Layer Operations

Planned actions:

- Increase or decrease height data in selected chunk layers.
- Swap two layers inside every applied chunk.
- Duplicate Layer X into Layer Y inside every applied chunk.
- Before overwriting a non-empty Layer Y, show a preflight warning that lists
  every affected chunk, for example:

  - Chunk (0, 0) has tiles on Layer Y.
  - Chunk (0, 1) has tiles on Layer Y.

  The user must confirm before those target layers are overwritten.

### Items to Resolve During That Task

- Whether **Swap Layer** moves tiles only, heights only, or both by selectable
  property.
- Whether layer collision data is included in Swap/Duplicate or remains a
  separate property choice.
- The exact neutral value used by **Clear Collision Properties**.
- Whether smart collision rebuilding should cover every cell in a changed map
  or only cells changed by the current operation.

These decisions are deliberately recorded rather than silently assumed.

## Control Placement

- Matrix selection, chunk rows, layer scopes, Apply, Undo, and Close are
  workspace controls.
- Replace/Swap/Delete, collision results, height results, match visibility, and
  match exclusions are Tile Operation controls.
- Copy, height adjustment, layer swap, layer duplication, and layer clearing
  belong in Layer Operations.

This separation avoids two controls appearing to perform the same job through
different parts of the interface.
