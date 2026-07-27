package editor.globaledit;

import editor.MainFrame;
import editor.grid.MapGrid;
import editor.handler.MapData;
import editor.handler.MapEditorHandler;
import editor.state.GlobalMapEditState;
import formats.collisions.CollisionDefaultsApplier;
import tileset.Tile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Matrix-scoped editing for operations that would otherwise need to be
 * repeated in every map chunk.
 */
public final class GlobalMapEditDialog extends JDialog {

    private final MainFrame owner;
    private final MapEditorHandler handler;
    private final MatrixSelectionPanel matrixSelection;
    private final JCheckBox[] layerChecks = new JCheckBox[MapGrid.numLayers];
    private final JTabbedPane operations = new JTabbedPane();
    private final JLabel status = new JLabel("Choose chunks, layers, and an operation.");

    private final TileChoicePanel sourceTile;
    private final TileChoicePanel replacementTile;
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
        this.sourceTile = new TileChoicePanel("Find tile", handler);
        this.replacementTile = new TileChoicePanel("Replacement tile", handler);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel description = new JLabel("<html><b>Global Map Editor</b> \u2014 click or drag across the matrix "
                + "to highlight chunks. Every Apply is one undoable edit.</html>");
        add(description, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createMatrixPanel(), createOperationPanel());
        split.setResizeWeight(0.55);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
        add(createButtonBar(), BorderLayout.SOUTH);

        selectCurrentLayer();
        copySourceLayer.setSelectedIndex(handler.getActiveLayerIndex());
        copyTargetLayer.setSelectedIndex((handler.getActiveLayerIndex() + 1) % MapGrid.numLayers);
        int selectedTile = Math.max(0, Math.min(handler.getTileIndexSelected(),
                Math.max(0, handler.getTileset().size() - 1)));
        sourceTile.setTileIndex(selectedTile);
        replacementTile.setTileIndex(Math.min(selectedTile + 1,
                Math.max(0, handler.getTileset().size() - 1)));

        setMinimumSize(new Dimension(860, 610));
        setSize(1050, 720);
        setLocationRelativeTo(owner);
    }

    private JPanel createMatrixPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("1. Matrix chunks"));

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
        buttons.add(matrixSelection.getSelectionLabel());
        panel.add(buttons, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(matrixSelection);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(32);
        scrollPane.getVerticalScrollBar().setUnitIncrement(32);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createOperationPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(createLayerFilterPanel(), BorderLayout.NORTH);

        operations.addTab("Replace / Swap", createReplacePanel());
        operations.addTab("Copy Layer", createCopyPanel());
        operations.addTab("Adjust Heights", createHeightPanel());
        operations.addTab("Clear", createClearPanel());
        panel.add(operations, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createLayerFilterPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                "2. Layers affected (Replace, Heights, Clear)"));

        JPanel checks = new JPanel(new GridLayout(3, 3, 4, 2));
        for (int i = 0; i < layerChecks.length; i++) {
            layerChecks[i] = new JCheckBox("Layer " + (i + 1));
            checks.add(layerChecks[i]);
        }
        panel.add(checks, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton all = new JButton("All");
        all.addActionListener(e -> setAllLayers(true));
        JButton current = new JButton("Current");
        current.addActionListener(e -> selectCurrentLayer());
        JButton none = new JButton("None");
        none.addActionListener(e -> setAllLayers(false));
        buttons.add(all);
        buttons.add(current);
        buttons.add(none);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createReplacePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JPanel previews = new JPanel(new GridLayout(1, 2, 8, 0));
        previews.add(sourceTile);
        previews.add(replacementTile);
        panel.add(previews, BorderLayout.CENTER);

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(swapTiles);
        options.add(applyCollisionDefaults);
        JLabel note = new JLabel("<html>Replace changes A into B. Swap also changes every B into A "
                + "within the highlighted chunks and checked layers.</html>");
        note.setBorder(new EmptyBorder(5, 2, 0, 2));
        options.add(note);
        panel.add(options, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCopyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Source:"), gbc);
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
        panel.add(new JLabel("<html>For every highlighted chunk, the source layer is copied "
                + "to the target layer in that same chunk.</html>"), gbc);
        return panel;
    }

    private JPanel createHeightPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
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
                + "Values are automatically kept inside the supported height range.</html>"), gbc);
        return panel;
    }

    private JPanel createClearPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(clearTiles);
        panel.add(clearHeights);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("<html>Clears only the highlighted chunks and checked layers. "
                + "You can undo the entire clear in one step.</html>"));
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
                matrixSelection.repaint();
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
                int from = sourceTile.getTileIndex();
                int to = replacementTile.getTileIndex();
                if (from == to) {
                    showValidation("Choose two different tiles.");
                    return;
                }
                changed = GlobalMapOperations.replaceTiles(
                        handler.getMapMatrix().getMatrix(), maps, layers,
                        from, to, swapTiles.isSelected());
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
                + (collisionChanges > 0 ? " and refreshed " + collisionChanges + " collision cell(s)." : "."));
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

    private static final class TileChoicePanel extends JPanel {
        private final MapEditorHandler handler;
        private final JSpinner tileIndex;
        private final JLabel tileName = new JLabel(" ");
        private final TilePreview preview;

        private TileChoicePanel(String title, MapEditorHandler handler) {
            super(new BorderLayout(4, 4));
            this.handler = handler;
            int maximum = Math.max(0, handler.getTileset().size() - 1);
            this.tileIndex = new JSpinner(new SpinnerNumberModel(0, 0, maximum, 1));
            this.preview = new TilePreview(handler, tileIndex);
            setBorder(BorderFactory.createTitledBorder(title));

            JPanel controls = new JPanel(new BorderLayout(4, 0));
            controls.add(new JLabel("Tile ID:"), BorderLayout.WEST);
            controls.add(tileIndex, BorderLayout.CENTER);
            JButton current = new JButton("Use selected");
            current.addActionListener(e -> setTileIndex(handler.getTileIndexSelected()));
            controls.add(current, BorderLayout.EAST);
            add(controls, BorderLayout.NORTH);
            add(preview, BorderLayout.CENTER);
            tileName.setHorizontalAlignment(SwingConstants.CENTER);
            add(tileName, BorderLayout.SOUTH);

            tileIndex.addChangeListener(e -> updatePreview());
            updatePreview();
        }

        private int getTileIndex() {
            return (Integer) tileIndex.getValue();
        }

        private void setTileIndex(int index) {
            SpinnerNumberModel model = (SpinnerNumberModel) tileIndex.getModel();
            int maximum = (Integer) model.getMaximum();
            tileIndex.setValue(Math.max(0, Math.min(index, maximum)));
        }

        private void updatePreview() {
            int index = getTileIndex();
            if (handler.getTileset().size() > 0 && index < handler.getTileset().size()) {
                Tile tile = handler.getTileset().get(index);
                String name = tile.getObjFilename();
                String paletteName = tile.getPaletteName();
                tileName.setText("<html><center>" + escape(name)
                        + (paletteName.isEmpty() ? "" : "<br>\"" + escape(paletteName) + "\"")
                        + "</center></html>");
            } else {
                tileName.setText("No tile loaded");
            }
            preview.repaint();
        }

        private static String escape(String text) {
            return text == null ? "" : text.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final class TilePreview extends JComponent {
        private final MapEditorHandler handler;
        private final JSpinner tileIndex;

        private TilePreview(MapEditorHandler handler, JSpinner tileIndex) {
            this.handler = handler;
            this.tileIndex = tileIndex;
            setPreferredSize(new Dimension(180, 180));
            setMinimumSize(new Dimension(120, 120));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int cell = 8;
                Color first = new Color(72, 72, 72);
                Color second = new Color(92, 92, 92);
                for (int x = 0; x < getWidth(); x += cell) {
                    for (int y = 0; y < getHeight(); y += cell) {
                        g2.setColor(((x / cell) + (y / cell)) % 2 == 0 ? first : second);
                        g2.fillRect(x, y, cell, cell);
                    }
                }
                int index = (Integer) tileIndex.getValue();
                if (index < 0 || index >= handler.getTileset().size()) {
                    return;
                }
                BufferedImage image = handler.getTileset().get(index).getThumbnail();
                if (image == null) {
                    image = handler.getTileset().get(index).getSmallThumbnail();
                }
                if (image == null) {
                    return;
                }
                double scale = Math.min((getWidth() - 12.0) / image.getWidth(),
                        (getHeight() - 12.0) / image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                Object oldInterpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2,
                        width, height, null);
                if (oldInterpolation != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class MatrixSelectionPanel extends JPanel {
        private static final int CELL_SIZE = MapData.mapThumbnailSize;
        private final MapEditorHandler handler;
        private final Set<Point> selectedMaps = new LinkedHashSet<>();
        private final JLabel selectionLabel = new JLabel();
        private Point minimum = new Point();
        private Dimension matrixSize = new Dimension(1, 1);
        private Boolean dragSelectionState;

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
            setPreferredSize(new Dimension(
                    Math.max(1, matrixSize.width) * CELL_SIZE,
                    Math.max(1, matrixSize.height) * CELL_SIZE));
        }

        private Point mapAt(Point pixel) {
            int matrixX = Math.floorDiv(pixel.x, CELL_SIZE) + minimum.x;
            int matrixY = Math.floorDiv(pixel.y, CELL_SIZE) + minimum.y;
            Point point = new Point(matrixX, matrixY);
            return handler.getMapMatrix().getMatrix().containsKey(point) ? point : null;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Point point = mapAt(event.getPoint());
            if (point == null) {
                return null;
            }
            return handler.getMapMatrix().getMapName(point) + "  (" + point.x + ", " + point.y + ")";
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
                    int x = (point.x - minimum.x) * CELL_SIZE;
                    int y = (point.y - minimum.y) * CELL_SIZE;
                    BufferedImage thumbnail = entry.getValue().getMapThumbnail();
                    if (thumbnail != null) {
                        g2.drawImage(thumbnail, x, y, CELL_SIZE, CELL_SIZE, null);
                    } else {
                        g2.setColor(new Color(0, 128, 128));
                        g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                    }

                    if (selectedMaps.contains(point)) {
                        g2.setColor(new Color(0, 220, 255, 85));
                        g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                        g2.setColor(new Color(0, 240, 255));
                        g2.setStroke(new BasicStroke(3));
                    } else {
                        g2.setColor(new Color(0, 0, 0, 115));
                        g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                        g2.setColor(new Color(90, 90, 90));
                        g2.setStroke(new BasicStroke(1));
                    }
                    g2.drawRect(x, y, CELL_SIZE - 1, CELL_SIZE - 1);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
