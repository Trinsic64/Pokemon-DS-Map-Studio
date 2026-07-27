package editor.globaledit;

import editor.MainFrame;
import editor.grid.MapGrid;
import editor.handler.MapData;
import editor.handler.MapEditorHandler;
import editor.state.GlobalMapEditState;
import editor.tileselector.TileSelector;
import formats.collisions.CollisionDefaultsApplier;
import tileset.Tile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main-window-style workspace for editing selected layers across highlighted
 * matrix chunks. Each Apply is captured as one undoable map state.
 */
public final class GlobalMapEditDialog extends JDialog {

    private static final Color PREVIEW_BACKGROUND = new Color(0, 127, 127);

    private final MainFrame owner;
    private final MapEditorHandler handler;
    private final MatrixSelectionPanel matrixSelection;
    private final JCheckBox[] layerChecks = new JCheckBox[MapGrid.numLayers];
    private final LayerPreview[] layerPreviews = new LayerPreview[MapGrid.numLayers];
    private final JTabbedPane operations = new JTabbedPane();
    private final JLabel status = new JLabel("Choose chunks, layers, and an operation.");

    private final TileSelector sourceBrowser = new TileSelector();
    private final TileSelector replacementBrowser = new TileSelector();
    private final JLabel sourceSummary = new JLabel(" ");
    private final JLabel replacementSummary = new JLabel(" ");
    private final JLabel replaceSummary = new JLabel(" ");
    private int sourceTileIndex;
    private int replacementTileIndex;

    private final JCheckBox swapTiles = new JCheckBox("Swap both directions (A \u2194 B)");
    private final JCheckBox applyCollisionDefaults =
            new JCheckBox("Refresh smart collision defaults after replacement");

    private final JComboBox<String> copySourceLayer = createLayerCombo();
    private final JComboBox<String> copyTargetLayer = createLayerCombo();
    private final JCheckBox copyTiles = new JCheckBox("Tile layout", true);
    private final JCheckBox copyHeights = new JCheckBox("Height layout", true);

    private final JSpinner heightAmount = new JSpinner(new SpinnerNumberModel(1, -64, 64, 1));
    private final JCheckBox occupiedOnly = new JCheckBox("Only cells containing a tile", true);

    private final JCheckBox clearTiles = new JCheckBox("Clear tiles", true);
    private final JCheckBox clearHeights = new JCheckBox("Reset heights to zero", true);

    public GlobalMapEditDialog(MainFrame owner, MapEditorHandler handler) {
        super(owner, "Global Map Editor", ModalityType.MODELESS);
        this.owner = owner;
        this.handler = handler;
        this.matrixSelection = new MatrixSelectionPanel(handler);

        int selectedTile = clampTileIndex(handler.getTileIndexSelected());
        sourceTileIndex = selectedTile;
        replacementTileIndex = clampTileIndex(selectedTile + 1);
        sourceBrowser.initReadOnly(handler, sourceTileIndex,
                this::setSourceTileIndex, this::refreshTileBrowserLayouts);
        replacementBrowser.initReadOnly(handler, replacementTileIndex,
                this::setReplacementTileIndex, this::refreshTileBrowserLayouts);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        add(createHeading(), BorderLayout.NORTH);
        add(createWorkspace(), BorderLayout.CENTER);
        add(createButtonBar(), BorderLayout.SOUTH);

        selectCurrentLayer();
        copySourceLayer.setSelectedIndex(handler.getActiveLayerIndex());
        copyTargetLayer.setSelectedIndex((handler.getActiveLayerIndex() + 1) % MapGrid.numLayers);
        updateTileSummaries();
        installKeyboardActions();
        installWorkspaceRefresh();
        fitToDesktop();

        SwingUtilities.invokeLater(matrixSelection::showCurrentMap);
    }

    private JComponent createHeading() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        JLabel heading = new JLabel("<html><b>Global Map Editor</b><br>"
                + "<span style='font-size:9px'>Choose layers on the left, tiles from the "
                + "folder lists, and drag across the overview to choose map chunks.</span></html>");
        panel.add(heading, BorderLayout.WEST);

        JLabel undoNote = new JLabel("Every Apply is one undoable edit.");
        undoNote.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(undoNote, BorderLayout.EAST);
        return panel;
    }

    private JComponent createWorkspace() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;

        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 8);
        panel.add(createLayerColumn(), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.22;
        panel.add(createTileBrowserPanel(
                "A. Find tile", sourceBrowser, sourceSummary, true), gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.56;
        panel.add(createCenterWorkspace(), gbc);

        gbc.gridx = 3;
        gbc.weightx = 0.22;
        gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(createTileBrowserPanel(
                "B. Replacement tile", replacementBrowser, replacementSummary, false), gbc);
        return panel;
    }

    private JComponent createLayerColumn() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));
        panel.setBorder(BorderFactory.createTitledBorder("Layers"));
        panel.setPreferredSize(new Dimension(126, 620));
        panel.setMinimumSize(new Dimension(116, 300));

        JPanel buttons = new JPanel(new GridLayout(1, 3, 2, 0));
        JButton all = compactButton("All", "Select every layer");
        all.addActionListener(e -> setAllLayers(true));
        JButton current = compactButton("Cur.", "Select the current layer only");
        current.addActionListener(e -> selectCurrentLayer());
        JButton none = compactButton("None", "Clear the layer selection");
        none.addActionListener(e -> setAllLayers(false));
        buttons.add(all);
        buttons.add(current);
        buttons.add(none);
        panel.add(buttons, BorderLayout.NORTH);

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        for (int i = 0; i < MapGrid.numLayers; i++) {
            int layer = i;
            layerChecks[i] = new JCheckBox("Layer " + (i + 1));
            layerChecks[i].setToolTipText("Include Layer " + (i + 1)
                    + " in Replace, Height, and Clear operations");
            layerPreviews[i] = new LayerPreview(layer);
            layerChecks[i].addItemListener(e -> layerPreviews[layer].repaint());

            JPanel row = new JPanel(new BorderLayout(0, 1));
            row.setBorder(new EmptyBorder(1, 2, 2, 2));
            row.add(layerChecks[i], BorderLayout.NORTH);
            row.add(layerPreviews[i], BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 82));
            rows.add(row);
        }

        JScrollPane scroll = new JScrollPane(rows,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(64);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent createTileBrowserPanel(String title, TileSelector browser,
                                               JLabel summary, boolean source) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setPreferredSize(new Dimension(225, 620));
        panel.setMinimumSize(new Dimension(160, 300));

        JButton useSelected = new JButton("Use main selection");
        useSelected.setFocusable(false);
        useSelected.setMargin(new Insets(2, 5, 2, 5));
        useSelected.setToolTipText("Use the tile currently selected in the main editor");
        useSelected.addActionListener(e -> {
            if (source) {
                setSourceTileIndex(handler.getTileIndexSelected());
                sourceBrowser.setReadOnlySelectedIndex(sourceTileIndex);
            } else {
                setReplacementTileIndex(handler.getTileIndexSelected());
                replacementBrowser.setReadOnlySelectedIndex(replacementTileIndex);
            }
        });
        panel.add(useSelected, BorderLayout.NORTH);

        JPanel browserSurface = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        browserSurface.add(browser);
        JScrollPane scroll = new JScrollPane(browserSurface);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        panel.add(scroll, BorderLayout.CENTER);

        summary.setBorder(new EmptyBorder(3, 2, 1, 2));
        summary.setHorizontalAlignment(SwingConstants.CENTER);
        summary.setVerticalAlignment(SwingConstants.TOP);
        summary.setPreferredSize(new Dimension(150, 48));
        panel.add(summary, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createCenterWorkspace() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setMinimumSize(new Dimension(430, 300));
        panel.add(createMatrixPanel(), BorderLayout.CENTER);
        panel.add(createOperationPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createMatrixPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Matrix chunks"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton selectAll = new JButton("Select All");
        selectAll.addActionListener(e -> matrixSelection.selectAll());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> matrixSelection.clearSelection());
        JButton invert = new JButton("Invert");
        invert.addActionListener(e -> matrixSelection.invertSelection());
        buttons.add(selectAll);
        buttons.add(clear);
        buttons.add(invert);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(matrixSelection.getSelectionLabel());
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(new JLabel("Zoom:"));

        JLabel zoomValue = new JLabel("50%");
        zoomValue.setPreferredSize(new Dimension(38, 20));
        JSlider zoom = new JSlider(25, 100, 50);
        zoom.setPreferredSize(new Dimension(100, 22));
        zoom.setToolTipText("Scale matrix thumbnails");
        zoom.addChangeListener(e -> {
            int percent = zoom.getValue();
            matrixSelection.setCellSize(
                    Math.max(16, MapData.mapThumbnailSize * percent / 100));
            zoomValue.setText(percent + "%");
        });
        buttons.add(zoom);
        buttons.add(zoomValue);
        panel.add(buttons, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(matrixSelection);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(32);
        scrollPane.getVerticalScrollBar().setUnitIncrement(32);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createOperationPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Operation"));
        panel.setPreferredSize(new Dimension(430, 210));

        operations.addTab("Replace / Swap", createReplacePanel());
        operations.addTab("Copy Layer", createCopyPanel());
        operations.addTab("Adjust Heights", createHeightPanel());
        operations.addTab("Clear", createClearPanel());
        panel.add(operations, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createReplacePanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(9, 10, 8, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        replaceSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(replaceSummary);
        panel.add(Box.createVerticalStrut(5));
        swapTiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyCollisionDefaults.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(swapTiles);
        panel.add(applyCollisionDefaults);
        panel.add(Box.createVerticalStrut(5));

        JLabel note = new JLabel("<html>Replace changes A into B. Swap also changes B into A "
                + "inside the highlighted chunks and selected layers.</html>");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(note);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createCopyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Copy from:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(copySourceLayer, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Paste into:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(copyTargetLayer, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        panel.add(copyTiles, gbc);
        gbc.gridy = 3;
        panel.add(copyHeights, gbc);
        gbc.gridy = 4;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("<html>The chosen source layer is copied to the target layer "
                + "inside every highlighted chunk.</html>"), gbc);
        return panel;
    }

    private JPanel createHeightPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Add to height:"), gbc);
        gbc.gridx = 1;
        panel.add(heightAmount, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        panel.add(occupiedOnly, gbc);
        gbc.gridy = 2;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("<html>Positive values raise tiles; negative values lower them. "
                + "Results remain inside the supported height range.</html>"), gbc);
        return panel;
    }

    private JPanel createClearPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(9, 10, 8, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        clearTiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        clearHeights.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(clearTiles);
        panel.add(clearHeights);
        panel.add(Box.createVerticalStrut(8));
        JLabel note = new JLabel("<html>Clears only the highlighted chunks and selected layers. "
                + "The entire clear can be undone in one step.</html>");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(note);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createButtonBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        status.setBorder(new EmptyBorder(0, 2, 0, 4));
        panel.add(status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton undo = new JButton("Undo");
        undo.setToolTipText("Undo the most recent map edit");
        undo.addActionListener(e -> {
            if (handler.getMapStateHandler().canGetPreviousState()) {
                owner.undoMapState();
                refreshWorkspaceVisuals();
                status.setText("Undid the most recent map edit.");
            } else {
                showValidation("There is no map edit to undo.");
            }
        });
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> applySelectedOperation());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(undo);
        buttons.add(apply);
        buttons.add(close);
        panel.add(buttons, BorderLayout.EAST);
        getRootPane().setDefaultButton(apply);
        return panel;
    }

    private void installKeyboardActions() {
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void installWorkspaceRefresh() {
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                sourceTileIndex = clampTileIndex(sourceTileIndex);
                replacementTileIndex = clampTileIndex(replacementTileIndex);
                sourceBrowser.setReadOnlySelectedIndex(sourceTileIndex);
                replacementBrowser.setReadOnlySelectedIndex(replacementTileIndex);
                sourceBrowser.updateLayout();
                replacementBrowser.updateLayout();
                updateTileSummaries();
                refreshWorkspaceVisuals();
            }
        });
    }

    private void fitToDesktop() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(1480, Math.max(980, usable.width - 80));
        int height = Math.min(900, Math.max(680, usable.height - 80));
        width = Math.min(width, usable.width);
        height = Math.min(height, usable.height);
        setMinimumSize(new Dimension(Math.min(1050, usable.width),
                Math.min(650, usable.height)));
        setSize(width, height);
        setLocation(usable.x + (usable.width - width) / 2,
                usable.y + (usable.height - height) / 2);
    }

    private void setSourceTileIndex(int index) {
        sourceTileIndex = clampTileIndex(index);
        updateTileSummaries();
    }

    private void setReplacementTileIndex(int index) {
        replacementTileIndex = clampTileIndex(index);
        updateTileSummaries();
    }

    private int clampTileIndex(int index) {
        return Math.max(0, Math.min(index, Math.max(0, handler.getTileset().size() - 1)));
    }

    private void updateTileSummaries() {
        sourceSummary.setText(tileSummary(sourceTileIndex));
        replacementSummary.setText(tileSummary(replacementTileIndex));
        replaceSummary.setText("<html><b>A: " + tileShortName(sourceTileIndex)
                + "</b> &nbsp;\u2192&nbsp; <b>B: " + tileShortName(replacementTileIndex)
                + "</b></html>");
    }

    private String tileSummary(int index) {
        if (handler.getTileset().size() == 0 || index >= handler.getTileset().size()) {
            return "No tile loaded";
        }
        Tile tile = handler.getTileset().get(index);
        String paletteName = tile.getPaletteName();
        return "<html><center><b>Tile " + index + "</b><br>" + escape(tile.getObjFilename())
                + (paletteName.isEmpty() ? "" : "<br>\"" + escape(paletteName) + "\"")
                + "</center></html>";
    }

    private String tileShortName(int index) {
        if (handler.getTileset().size() == 0 || index >= handler.getTileset().size()) {
            return "No tile";
        }
        Tile tile = handler.getTileset().get(index);
        String name = tile.getPaletteName().isEmpty()
                ? tile.getObjFilename() : tile.getPaletteName();
        if (name == null || name.isEmpty()) {
            name = "Tile";
        }
        if (name.length() > 28) {
            name = name.substring(0, 25) + "...";
        }
        return escape(name) + " [" + index + "]";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private void refreshTileBrowserLayouts() {
        sourceBrowser.updateLayout();
        replacementBrowser.updateLayout();
        owner.updateTileSelectorLayout();
    }

    private void applySelectedOperation() {
        Set<Point> maps = matrixSelection.getSelectedMaps();
        if (maps.isEmpty()) {
            showValidation("Select at least one matrix chunk.");
            return;
        }

        int tab = operations.getSelectedIndex();
        int[] layers = selectedLayers();
        if (tab != 1 && layers.length == 0) {
            showValidation("Select at least one layer.");
            return;
        }
        if (tab == 0 && handler.getTileset().size() == 0) {
            showValidation("Load a tileset before replacing tiles.");
            return;
        }

        int[] snapshotLayers = tab == 1
                ? new int[]{copyTargetLayer.getSelectedIndex()}
                : layers;
        String stateName = operationName(tab);
        GlobalMapEditState before =
                new GlobalMapEditState(stateName, handler, maps, snapshotLayers);
        int changed;
        int collisionChanges = 0;

        switch (tab) {
            case 0:
                if (sourceTileIndex == replacementTileIndex) {
                    showValidation("Choose two different tiles.");
                    return;
                }
                changed = GlobalMapOperations.replaceTiles(
                        handler.getMapMatrix().getMatrix(), maps, layers,
                        sourceTileIndex, replacementTileIndex, swapTiles.isSelected());
                if (changed > 0 && applyCollisionDefaults.isSelected()) {
                    for (Point point : maps) {
                        MapData mapData = handler.getMapMatrix().getMap(point);
                        if (mapData != null) {
                            collisionChanges += CollisionDefaultsApplier.apply(
                                    handler.getTileset(), mapData.getGrid(), mapData.getCollisions());
                        }
                    }
                }
                break;
            case 1:
                if (!copyTiles.isSelected() && !copyHeights.isSelected()) {
                    showValidation("Choose tile layout, height layout, or both.");
                    return;
                }
                if (copySourceLayer.getSelectedIndex() == copyTargetLayer.getSelectedIndex()) {
                    showValidation("Choose a different target layer.");
                    return;
                }
                changed = GlobalMapOperations.copyLayer(
                        handler.getMapMatrix().getMatrix(), maps,
                        copySourceLayer.getSelectedIndex(), copyTargetLayer.getSelectedIndex(),
                        copyTiles.isSelected(), copyHeights.isSelected());
                break;
            case 2:
                int amount = (Integer) heightAmount.getValue();
                if (amount == 0) {
                    showValidation("Choose a non-zero height adjustment.");
                    return;
                }
                changed = GlobalMapOperations.adjustHeights(
                        handler.getMapMatrix().getMatrix(), maps, layers, amount,
                        MapEditorHandler.minHeight, MapEditorHandler.maxHeight,
                        occupiedOnly.isSelected());
                break;
            case 3:
                if (!clearTiles.isSelected() && !clearHeights.isSelected()) {
                    showValidation("Choose tiles, heights, or both to clear.");
                    return;
                }
                int choice = JOptionPane.showConfirmDialog(this,
                        "Clear the chosen data in " + maps.size() + " chunk(s)?",
                        "Confirm global clear", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) {
                    return;
                }
                changed = GlobalMapOperations.clearLayers(
                        handler.getMapMatrix().getMatrix(), maps, layers,
                        clearTiles.isSelected(), clearHeights.isSelected());
                break;
            default:
                return;
        }

        if (changed == 0 && collisionChanges == 0) {
            status.setText("No matching cells needed changes.");
            return;
        }

        handler.addMapState(before);
        refreshMaps(maps, snapshotLayers);
        status.setText(stateName + ": changed " + changed + " cell(s) in "
                + maps.size() + " chunk(s)"
                + (collisionChanges > 0
                ? " and refreshed " + collisionChanges + " collision cell(s)." : "."));
    }

    private void refreshMaps(Set<Point> maps, int[] layers) {
        for (Point point : maps) {
            MapData mapData = handler.getMapMatrix().getMap(point);
            if (mapData == null) {
                continue;
            }
            for (int layer : layers) {
                mapData.getGrid().updateMapLayerGL(layer, handler.useRealTimePostProcessing());
            }
            mapData.updateMapThumbnail();
        }
        for (int layer : layers) {
            owner.getThumbnailLayerSelector().drawLayerThumbnail(layer);
        }
        owner.getThumbnailLayerSelector().repaint();
        owner.getMapDisplay().repaint();
        owner.updateMapMatrixDisplay();
        owner.updateViewMapInfo();
        refreshWorkspaceVisuals();
    }

    private void refreshWorkspaceVisuals() {
        for (LayerPreview preview : layerPreviews) {
            if (preview != null) {
                preview.repaint();
            }
        }
        matrixSelection.updateBounds();
        matrixSelection.revalidate();
        matrixSelection.repaint();
    }

    private int[] selectedLayers() {
        int count = 0;
        for (JCheckBox check : layerChecks) {
            if (check.isSelected()) {
                count++;
            }
        }
        int[] layers = new int[count];
        int next = 0;
        for (int i = 0; i < layerChecks.length; i++) {
            if (layerChecks[i].isSelected()) {
                layers[next++] = i;
            }
        }
        return layers;
    }

    private void setAllLayers(boolean selected) {
        for (JCheckBox check : layerChecks) {
            check.setSelected(selected);
        }
    }

    private void selectCurrentLayer() {
        setAllLayers(false);
        layerChecks[handler.getActiveLayerIndex()].setSelected(true);
    }

    private void showValidation(String message) {
        status.setText(message);
        Toolkit.getDefaultToolkit().beep();
    }

    private static String operationName(int tab) {
        switch (tab) {
            case 0:
                return "Global tile replace";
            case 1:
                return "Global layer copy";
            case 2:
                return "Global height adjustment";
            case 3:
                return "Global layer clear";
            default:
                return "Global map edit";
        }
    }

    private static JComboBox<String> createLayerCombo() {
        String[] layers = new String[MapGrid.numLayers];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = "Layer " + (i + 1);
        }
        return new JComboBox<>(layers);
    }

    private static JButton compactButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 2, 2, 2));
        button.setToolTipText(tooltip);
        return button;
    }

    private final class LayerPreview extends JComponent {

        private final int layer;

        private LayerPreview(int layer) {
            this.layer = layer;
            setPreferredSize(new Dimension(72, 58));
            setMinimumSize(new Dimension(60, 48));
            setToolTipText("Click to include or exclude Layer " + (layer + 1));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        layerChecks[LayerPreview.this.layer].setSelected(
                                !layerChecks[LayerPreview.this.layer].isSelected());
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int inset = 3;
                int drawWidth = Math.max(1, getWidth() - inset * 2);
                int drawHeight = Math.max(1, getHeight() - inset * 2);
                BufferedImage preview = renderLayerPreview(layer);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(preview, inset, inset, drawWidth, drawHeight, null);

                boolean selected = layerChecks[layer].isSelected();
                if (!selected) {
                    g2.setColor(new Color(0, 0, 0, 105));
                    g2.fillRect(inset, inset, drawWidth, drawHeight);
                } else {
                    g2.setColor(new Color(0, 220, 255, 45));
                    g2.fillRect(inset, inset, drawWidth, drawHeight);
                }
                g2.setStroke(new BasicStroke(selected ? 3 : 1));
                Color border = selected ? new Color(0, 220, 255)
                        : UIManager.getColor("Separator.foreground");
                g2.setColor(border == null ? Color.GRAY : border);
                g2.drawRect(inset, inset, drawWidth - 1, drawHeight - 1);
            } finally {
                g2.dispose();
            }
        }

        private BufferedImage renderLayerPreview(int layerIndex) {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = image.getGraphics();
            try {
                graphics.setColor(PREVIEW_BACKGROUND);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                int[][] grid = handler.getGrid().tileLayers[layerIndex];
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        int tileIndex = grid[x][y];
                        if (tileIndex < 0 || tileIndex >= handler.getTileset().size()) {
                            continue;
                        }
                        BufferedImage tile = handler.getTileset().get(tileIndex).getSmallThumbnail();
                        if (tile != null) {
                            graphics.drawImage(tile, x * 2,
                                    (MapGrid.cols - y - 1) * 2 - (tile.getHeight() - 2), null);
                        }
                    }
                }
            } finally {
                graphics.dispose();
            }
            return image;
        }
    }

    private static final class MatrixSelectionPanel extends JPanel {

        private final MapEditorHandler handler;
        private final Set<Point> selectedMaps = new LinkedHashSet<>();
        private final JLabel selectionLabel = new JLabel();
        private Point minimum = new Point();
        private Dimension matrixSize = new Dimension(1, 1);
        private Boolean dragSelectionState;
        private int cellSize = Math.max(16, MapData.mapThumbnailSize / 2);

        private MatrixSelectionPanel(MapEditorHandler handler) {
            this.handler = handler;
            setBackground(new Color(50, 53, 54));
            setToolTipText("");
            updateBounds();
            if (handler.mapSelectedExists()) {
                selectedMaps.add(new Point(handler.getMapSelected()));
            }
            updateSelectionLabel();

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    Point map = mapAt(event.getPoint());
                    if (map == null) {
                        return;
                    }
                    dragSelectionState = !selectedMaps.contains(map);
                    setSelected(map, dragSelectionState);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (dragSelectionState == null) {
                        return;
                    }
                    Point map = mapAt(event.getPoint());
                    if (map != null) {
                        setSelected(map, dragSelectionState);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    dragSelectionState = null;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private JLabel getSelectionLabel() {
            return selectionLabel;
        }

        private Set<Point> getSelectedMaps() {
            Set<Point> copy = new LinkedHashSet<>();
            for (Point point : selectedMaps) {
                copy.add(new Point(point));
            }
            return copy;
        }

        private void selectAll() {
            selectedMaps.clear();
            for (Point point : handler.getMapMatrix().getMatrix().keySet()) {
                selectedMaps.add(new Point(point));
            }
            selectionChanged();
        }

        private void clearSelection() {
            selectedMaps.clear();
            selectionChanged();
        }

        private void invertSelection() {
            Set<Point> inverted = new LinkedHashSet<>();
            for (Point point : handler.getMapMatrix().getMatrix().keySet()) {
                if (!selectedMaps.contains(point)) {
                    inverted.add(new Point(point));
                }
            }
            selectedMaps.clear();
            selectedMaps.addAll(inverted);
            selectionChanged();
        }

        private void setSelected(Point point, boolean selected) {
            if (selected) {
                selectedMaps.add(new Point(point));
            } else {
                selectedMaps.remove(point);
            }
            selectionChanged();
        }

        private void setCellSize(int cellSize) {
            this.cellSize = Math.max(16, Math.min(MapData.mapThumbnailSize, cellSize));
            updateBounds();
            revalidate();
            repaint();
        }

        private void showCurrentMap() {
            Point current = handler.getMapSelected();
            if (current == null) {
                return;
            }
            int x = (current.x - minimum.x) * cellSize;
            int y = (current.y - minimum.y) * cellSize;
            scrollRectToVisible(new Rectangle(x, y, cellSize, cellSize));
        }

        private void selectionChanged() {
            updateSelectionLabel();
            repaint();
        }

        private void updateSelectionLabel() {
            selectionLabel.setText(selectedMaps.size() + " / "
                    + handler.getMapMatrix().getMatrix().size() + " selected");
        }

        private void updateBounds() {
            selectedMaps.retainAll(handler.getMapMatrix().getMatrix().keySet());
            updateSelectionLabel();
            if (handler.getMapMatrix().getMatrix().isEmpty()) {
                minimum = new Point();
                matrixSize = new Dimension(1, 1);
            } else {
                minimum = handler.getMapMatrix().getMinCoords();
                matrixSize = handler.getMapMatrix().getMatrixSize();
            }
            Dimension preferred = new Dimension(
                    Math.max(1, matrixSize.width) * cellSize,
                    Math.max(1, matrixSize.height) * cellSize);
            if (!preferred.equals(getPreferredSize())) {
                setPreferredSize(preferred);
            }
        }

        private Point mapAt(Point pixel) {
            int matrixX = Math.floorDiv(pixel.x, cellSize) + minimum.x;
            int matrixY = Math.floorDiv(pixel.y, cellSize) + minimum.y;
            Point point = new Point(matrixX, matrixY);
            return handler.getMapMatrix().getMatrix().containsKey(point) ? point : null;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Point point = mapAt(event.getPoint());
            if (point == null) {
                return null;
            }
            return handler.getMapMatrix().getMapName(point)
                    + "  (" + point.x + ", " + point.y + ")";
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            updateBounds();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                for (Map.Entry<Point, MapData> entry
                        : handler.getMapMatrix().getMatrix().entrySet()) {
                    Point point = entry.getKey();
                    int x = (point.x - minimum.x) * cellSize;
                    int y = (point.y - minimum.y) * cellSize;
                    BufferedImage thumbnail = entry.getValue().getMapThumbnail();
                    if (thumbnail != null) {
                        g2.drawImage(thumbnail, x, y, cellSize, cellSize, null);
                    } else {
                        g2.setColor(PREVIEW_BACKGROUND);
                        g2.fillRect(x, y, cellSize, cellSize);
                    }

                    if (selectedMaps.contains(point)) {
                        g2.setColor(new Color(0, 220, 255, 85));
                        g2.fillRect(x, y, cellSize, cellSize);
                        g2.setColor(new Color(0, 240, 255));
                        g2.setStroke(new BasicStroke(Math.max(2, cellSize / 20f)));
                    } else {
                        g2.setColor(new Color(0, 0, 0, 115));
                        g2.fillRect(x, y, cellSize, cellSize);
                        g2.setColor(new Color(90, 90, 90));
                        g2.setStroke(new BasicStroke(1));
                    }
                    g2.drawRect(x, y, cellSize - 1, cellSize - 1);

                    if (point.equals(handler.getMapSelected())) {
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(1));
                        g2.drawRect(x + 2, y + 2,
                                Math.max(0, cellSize - 5), Math.max(0, cellSize - 5));
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
