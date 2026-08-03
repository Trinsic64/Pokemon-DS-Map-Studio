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
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Main-window-style workspace for editing selected layers across highlighted
 * matrix chunks. Each Apply is captured as one undoable map state.
 */
public final class GlobalMapEditDialog extends JDialog {

    private static final Color PREVIEW_BACKGROUND = new Color(0, 127, 127);
    private static final Color COPY_SOURCE_COLOR = new Color(255, 170, 45);
    private static final Color COPY_TARGET_COLOR = new Color(45, 210, 255);
    private static final int VIEW_PADDING = 6;
    static final int EMPTY_TILE = -1;
    static final int NO_TILE_SELECTED = -2;

    static boolean picksSourceTile(boolean previewMap, boolean shiftDown) {
        return previewMap == shiftDown;
    }

    static int comparePreviewDrawOrder(
            float leftDepth, int leftY, int leftLayer, int leftX,
            float rightDepth, int rightY, int rightLayer, int rightX) {
        int comparison = Float.compare(leftDepth, rightDepth);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(rightLayer, leftLayer);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(rightY, leftY);
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(leftX, rightX);
    }

    private final MainFrame owner;
    private final MapEditorHandler handler;
    private final MatrixSelectionPanel matrixSelection;
    private final ChunkLayerScopes chunkLayerScopes;
    private final ChunkScopeTableModel chunkScopeModel;
    private final JTable chunkScopeTable;
    private final JCheckBox[] layerChecks = new JCheckBox[MapGrid.numLayers];
    private final LayerScopePreview[] layerPreviews = new LayerScopePreview[MapGrid.numLayers];
    private final boolean[] previewLayerVisible = new boolean[MapGrid.numLayers];
    private JButton checkAllLayersButton;
    private JButton uncheckAllLayersButton;
    private final JTabbedPane operations = new JTabbedPane();
    private final JLabel status = new JLabel("Choose chunks, layers, and an operation.");

    private final TileSelector sourceBrowser = new TileSelector();
    private final TileSelector replacementBrowser = new TileSelector();
    private final JLabel replaceSummary = new JLabel(" ");
    private final JLabel sourceHeaderSummary = new JLabel(" ");
    private final JLabel replacementHeaderSummary = new JLabel(" ");
    private final JLabel sourceHeaderIcon = new JLabel();
    private final JLabel replacementHeaderIcon = new JLabel();
    private final JToggleButton filterSourceToSelection =
            new JToggleButton("Filter Chunk Tiles");
    private final JToggleButton filterReplacementToSelection =
            new JToggleButton("Filter Chunk Tiles");
    private final JLabel sourceFilterStatus = new JLabel("Showing all tiles");
    private final JLabel replacementFilterStatus = new JLabel("Showing all tiles");
    private int sourceTileIndex;
    private int replacementTileIndex;
    private int selectedPreviewLayer;

    private final JRadioButton replaceMode = new JRadioButton("Replace A \u2192 B", true);
    private final JRadioButton swapTiles = new JRadioButton("Swap A \u2194 B");
    private final JRadioButton deleteMode = new JRadioButton("Delete A");
    private final JRadioButton keepCollisions =
            new JRadioButton("Keep collisions", true);
    private final JRadioButton refreshCollisionDefaults =
            new JRadioButton("Rebuild smart collisions");

    private final JComboBox<String> copySourceLayer = createLayerCombo();
    private final JComboBox<String> copyTargetLayer = createLayerCombo();
    private final JRadioButton copyTilesOnly =
            new JRadioButton("Tiles only", true);
    private final JRadioButton copyHeightsOnly =
            new JRadioButton("Heights only");
    private final JRadioButton copyTilesAndHeights =
            new JRadioButton("Tiles + heights");
    private final JRadioButton copySelectedTileOnly =
            new JRadioButton("Selected Tile A only", true);
    private final JRadioButton copyEntireLayer =
            new JRadioButton("Entire layer");
    private final JRadioButton copyAndPaste =
            new JRadioButton("Copy \u2014 keep source", true);
    private final JRadioButton cutAndPaste =
            new JRadioButton("Cut and paste \u2014 clear source tiles");
    private final JRadioButton swapLayerContents =
            new JRadioButton("Swap layer contents");
    private final JCheckBox copyAllSelectedChunks =
            new JCheckBox("All selected chunks");
    private final JToggleButton[] copySourceButtons =
            new JToggleButton[MapGrid.numLayers];
    private final JToggleButton[] copyTargetButtons =
            new JToggleButton[MapGrid.numLayers];
    private boolean updatingCopyLayerControls;

    private final JSpinner heightAmount = new JSpinner(new SpinnerNumberModel(1, -64, 64, 1));
    private final JCheckBox occupiedOnly = new JCheckBox("Occupied only", true);
    private final JRadioButton adjustHeightsMode =
            new JRadioButton("Adjust heights", true);
    private final JRadioButton resetHeightsMode =
            new JRadioButton("Set heights to 0");

    private final JComboBox<MapChoice> previewMapChoice = new JComboBox<>();
    private final JTextArea previewStats = new JTextArea(2, 20);
    private final OperationDiagram[] operationDiagrams =
            new OperationDiagram[3];
    private final JButton resetCellSelection = new JButton("Include all matches");
    private final JCheckBox showMatchHighlights = new JCheckBox("Show matches", true);
    private final JCheckBox showHeightNumbers = new JCheckBox("Height numbers");
    private final MapPreviewCanvas beforePreview;
    private final MapPreviewCanvas afterPreview;
    private final Set<GlobalMapOperations.TileCell> excludedMatches = new LinkedHashSet<>();
    private Point previewMap;
    private Point previewOnlyMap;
    private boolean updatingPreviewMapChoice;
    private boolean updatingChunkScopeTable;
    private boolean updatingLayerChecks;

    public GlobalMapEditDialog(MainFrame owner, MapEditorHandler handler) {
        super(owner, "Global Map Editor", ModalityType.MODELESS);
        this.owner = owner;
        this.handler = handler;
        Arrays.fill(previewLayerVisible, true);
        selectedPreviewLayer = handler.getActiveLayerIndex();
        this.chunkLayerScopes = new ChunkLayerScopes();
        this.matrixSelection =
                new MatrixSelectionPanel(handler, this::matrixSelectionChanged,
                        this::previewMatrixChunk);
        this.chunkScopeModel = new ChunkScopeTableModel();
        this.chunkScopeTable = new JTable(chunkScopeModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                return chunkScopeTooltip(this, event);
            }
        };
        this.beforePreview = new MapPreviewCanvas(false);
        this.afterPreview = new MapPreviewCanvas(true);

        sourceTileIndex = NO_TILE_SELECTED;
        replacementTileIndex = NO_TILE_SELECTED;
        sourceBrowser.initReadOnly(handler, -1,
                this::setSourceTileIndex, this::refreshTileBrowserLayouts);
        replacementBrowser.initReadOnly(handler, -1,
                this::setReplacementTileIndex, this::refreshTileBrowserLayouts);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(6, 6, 6, 6));

        add(createBody(), BorderLayout.CENTER);
        add(createButtonBar(), BorderLayout.SOUTH);

        chunkLayerScopes.synchronize(matrixSelection.getSelectedMaps());
        copySourceLayer.setSelectedIndex(handler.getActiveLayerIndex());
        copyTargetLayer.setSelectedIndex((handler.getActiveLayerIndex() + 1) % MapGrid.numLayers);
        installReactiveControls();
        installPreviewMapChoiceListener();
        updateTileSummaries();
        updateTileModeAvailability();
        refreshMapChoices(null);
        loadLayerChecksForPreviewMap();
        refreshChunkScopeTable();
        refreshPreview();
        installKeyboardActions();
        installWorkspaceRefresh();
        fitToDesktop();

        SwingUtilities.invokeLater(matrixSelection::showCurrentMap);
    }

    private JComponent createBody() {
        return createWorkspace();
    }

    private JComponent createLayerScopePanel() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));
        panel.setBorder(BorderFactory.createTitledBorder("Layers"));
        panel.setPreferredSize(new Dimension(102, 720));
        panel.setMinimumSize(new Dimension(94, 420));

        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        for (int i = 0; i < layerChecks.length; i++) {
            int layer = i;
            layerChecks[i] = new JCheckBox();
            layerChecks[i].setToolTipText("Include Layer " + (i + 1)
                    + " for the selected chunk");
            layerChecks[i].addItemListener(e -> {
                if (!updatingLayerChecks && previewMap != null
                        && chunkLayerScopes.contains(previewMap)) {
                    chunkLayerScopes.setSelected(previewMap, layer,
                            layerChecks[layer].isSelected());
                    chunkScopeModel.fireLayerChanged(previewMap, layer);
                }
                layerPreviews[layer].repaint();
                refreshSourceFilter();
                refreshPreview();
            });
            layerPreviews[i] = new LayerScopePreview(layer);
            checks.add(layerPreviews[i]);
        }
        JScrollPane layerScroll = new JScrollPane(checks,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        layerScroll.setBorder(BorderFactory.createEmptyBorder());
        layerScroll.getVerticalScrollBar().setUnitIncrement(64);
        panel.add(layerScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 3));
        JButton viewAll = compactButton("View All", "Show every layer in map height order");
        viewAll.addActionListener(e -> setAllPreviewLayersVisible(true));
        JButton hideAll = compactButton("Hide All",
                "Hide every layer in Current Map and Preview Map without changing edit scope");
        hideAll.addActionListener(e -> setAllPreviewLayersVisible(false));
        checkAllLayersButton = compactButton("Check All",
                "Include every layer for the selected chunk");
        checkAllLayersButton.addActionListener(e -> setAllLayers(true));
        uncheckAllLayersButton = compactButton("Uncheck",
                "Remove every layer from the selected chunk");
        uncheckAllLayersButton.addActionListener(e -> setAllLayers(false));
        buttons.add(viewAll);
        buttons.add(hideAll);
        buttons.add(checkAllLayersButton);
        buttons.add(uncheckAllLayersButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createWorkspace() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        gbc.insets = new Insets(0, 0, 0, 8);

        gbc.gridx = 0;
        gbc.weightx = 0;
        panel.add(createLayerScopePanel(), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0;
        panel.add(createTileBrowserPanel("A. Find", sourceBrowser, true), gbc);

        gbc.gridx = 2;
        gbc.weightx = 1;
        panel.add(createCenterWorkspace(), gbc);

        gbc.gridx = 3;
        gbc.weightx = 0;
        panel.add(createTileBrowserPanel("B. Replace", replacementBrowser, false), gbc);

        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(createRightRail(), gbc);
        return panel;
    }

    private JComponent createTileBrowserPanel(String title, TileSelector browser,
                                               boolean source) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setPreferredSize(new Dimension(source ? 160 : 168, 760));
        panel.setMinimumSize(new Dimension(source ? 148 : 156, 520));
        panel.add(createHeaderTilePanel(
                source ? "Find Tile (A)" : "Replacement Tile (B)",
                source ? sourceHeaderSummary : replacementHeaderSummary,
                source ? sourceHeaderIcon : replacementHeaderIcon), BorderLayout.NORTH);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JToggleButton filterToggle = source
                ? filterSourceToSelection : filterReplacementToSelection;
        JLabel filterStatus = source
                ? sourceFilterStatus : replacementFilterStatus;
        filterToggle.setFocusable(false);
        filterToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        filterToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                filterToggle.getPreferredSize().height));
        Font filterFont = filterToggle.getFont();
        filterToggle.setFont(filterFont.deriveFont(
                Math.max(9f, filterFont.getSize2D() - 2f)));
        filterToggle.setToolTipText(
                "Show only tiles used by selected chunks in the active operation layers");
        filterToggle.addActionListener(e -> refreshSourceFilter());
        controls.add(filterToggle);

        filterStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterStatus.setHorizontalAlignment(SwingConstants.LEFT);
        filterStatus.setForeground(
                UIManager.getColor("Label.disabledForeground"));
        controls.add(filterStatus);
        controls.add(Box.createVerticalStrut(5));

        JButton useEmpty = new JButton("Use Empty Tile");
        useEmpty.setFocusable(false);
        useEmpty.setMargin(new Insets(2, 5, 2, 5));
        useEmpty.setAlignmentX(Component.CENTER_ALIGNMENT);
        useEmpty.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                useEmpty.getPreferredSize().height));
        useEmpty.setToolTipText(source
                ? "Find cells that contain no tile"
                : "Replace tile A with an empty cell, which deletes the matched tile");
        useEmpty.addActionListener(e -> {
            if (source) {
                setSourceTileIndex(EMPTY_TILE);
                status.setText("Find tile A set to Empty Tile.");
            } else {
                setReplacementTileIndex(EMPTY_TILE);
                status.setText("Replacement tile B set to Empty Tile (delete A).");
            }
        });
        controls.add(useEmpty);
        controls.add(Box.createVerticalStrut(3));

        JButton useSelected = new JButton("Use Main");
        useSelected.setFocusable(false);
        useSelected.setMargin(new Insets(2, 5, 2, 5));
        useSelected.setAlignmentX(Component.CENTER_ALIGNMENT);
        useSelected.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                useSelected.getPreferredSize().height));
        useSelected.setToolTipText(
                "Use the tile currently selected in the main PDSMS tile list");
        useSelected.addActionListener(e -> {
            int mainTile = handler.getTileIndexSelected();
            if (mainTile < 0 || mainTile >= handler.getTileset().size()) {
                showValidation("Select a tile in the main PDSMS tile list first.");
                return;
            }
            if (source) {
                setSourceTileIndex(mainTile);
            } else {
                setReplacementTileIndex(mainTile);
            }
        });
        controls.add(useSelected);
        controls.add(Box.createVerticalStrut(3));

        JButton unselect = new JButton("Unselect");
        unselect.setFocusable(false);
        unselect.setMargin(new Insets(2, 5, 2, 5));
        unselect.setAlignmentX(Component.CENTER_ALIGNMENT);
        unselect.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                unselect.getPreferredSize().height));
        unselect.setToolTipText(source
                ? "Clear tile A without choosing Empty Tile"
                : "Clear tile B without choosing Empty Tile");
        unselect.addActionListener(e -> {
            if (source) {
                setSourceTileIndex(NO_TILE_SELECTED);
            } else {
                setReplacementTileIndex(NO_TILE_SELECTED);
            }
            status.setText((source ? "Find tile A" : "Replacement tile B")
                    + " unselected.");
        });
        controls.add(unselect);

        controls.add(Box.createVerticalStrut(3));
        JButton findInMatrix = new JButton("Find");
        findInMatrix.setFocusable(false);
        findInMatrix.setAlignmentX(Component.CENTER_ALIGNMENT);
        findInMatrix.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                findInMatrix.getPreferredSize().height));
        findInMatrix.setToolTipText(
                "Automatically select every matrix chunk containing tile "
                        + (source ? "A" : "B"));
        findInMatrix.addActionListener(
                e -> showFindScopeMenu(findInMatrix, source));
        controls.add(findInMatrix);

        JPanel browserPanel = new JPanel(new BorderLayout());
        browserPanel.setBorder(BorderFactory.createTitledBorder(title));
        JPanel browserSurface = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        browserSurface.add(browser);
        JScrollPane scroll = new JScrollPane(browserSurface);
        //TileSelector paints folder headers into a backing image and also
        //paints sticky headers.  JViewport's default BLIT scrolling can copy
        //those transient header pixels into the tile body (most visibly in
        //the replacement/B browser while scrolling upward).  Always repaint
        //the viewport instead of copying stale pixels.
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                int columns = Math.max(4, Math.min(8,
                        scroll.getViewport().getExtentSize().width / 16));
                browser.setReadOnlyColumnCount(columns);
            }
        });
        browserPanel.add(scroll, BorderLayout.CENTER);
        panel.add(browserPanel, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createCenterWorkspace() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setMinimumSize(new Dimension(620, 480));
        JPanel mapPreviews = createMapPreviewPanel();
        mapPreviews.setPreferredSize(new Dimension(620, 430));
        mapPreviews.setMinimumSize(new Dimension(560, 360));
        panel.add(mapPreviews, BorderLayout.NORTH);
        panel.add(createOperationPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMatrixPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Matrix"));
        panel.setPreferredSize(new Dimension(420, 500));

        JPanel selectionButtons = new JPanel(new GridLayout(1, 3, 4, 0));
        JButton selectAll = new JButton("All");
        selectAll.setToolTipText("Select every matrix chunk");
        selectAll.addActionListener(e -> matrixSelection.selectAll());
        JButton clear = new JButton("None");
        clear.setToolTipText("Unselect every matrix chunk");
        clear.addActionListener(e -> matrixSelection.clearSelection());
        JButton invert = new JButton("Invert");
        invert.setToolTipText("Invert the current matrix selection");
        invert.addActionListener(e -> matrixSelection.invertSelection());
        selectionButtons.add(selectAll);
        selectionButtons.add(clear);
        selectionButtons.add(invert);

        JLabel zoomValue = new JLabel("50%");
        zoomValue.setHorizontalAlignment(SwingConstants.CENTER);
        zoomValue.setPreferredSize(new Dimension(42, 24));
        JSlider zoom = new JSlider(25, 200, 50);
        zoom.setToolTipText("Matrix zoom: 25% to 200%");
        zoom.addChangeListener(e -> {
            int percent = zoom.getValue();
            matrixSelection.setCellSize(
                    Math.max(16, MapData.mapThumbnailSize * percent / 100));
            zoomValue.setText(percent + "%");
        });
        JButton zoomOut = compactButton("\u2212", "Zoom matrix out");
        zoomOut.setPreferredSize(new Dimension(32, 24));
        zoomOut.addActionListener(e -> zoom.setValue(
                Math.max(zoom.getMinimum(), zoom.getValue() - 25)));
        JButton zoomIn = compactButton("+", "Zoom matrix in");
        zoomIn.setPreferredSize(new Dimension(32, 24));
        zoomIn.addActionListener(e -> zoom.setValue(
                Math.min(zoom.getMaximum(), zoom.getValue() + 25)));
        JPanel zoomControls = new JPanel(new BorderLayout(3, 0));
        zoomControls.add(zoomOut, BorderLayout.WEST);
        zoomControls.add(zoom, BorderLayout.CENTER);
        JPanel zoomEnd = new JPanel(new BorderLayout(3, 0));
        zoomEnd.add(zoomValue, BorderLayout.WEST);
        zoomEnd.add(zoomIn, BorderLayout.EAST);
        zoomControls.add(zoomEnd, BorderLayout.EAST);

        JPanel matrixFooter = new JPanel();
        matrixFooter.setLayout(new BoxLayout(matrixFooter, BoxLayout.Y_AXIS));
        selectionButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                selectionButtons.getPreferredSize().height));
        matrixFooter.add(selectionButtons);
        matrixFooter.add(Box.createVerticalStrut(3));
        matrixSelection.getSelectionLabel().setToolTipText(
                "Right-click any chunk to preview it without changing this selection");
        matrixFooter.add(matrixSelection.getSelectionLabel());
        matrixFooter.add(zoomControls);

        JScrollPane scrollPane = new JScrollPane(matrixSelection);
        scrollPane.setPreferredSize(new Dimension(384, 384));
        scrollPane.setMinimumSize(new Dimension(320, 320));
        scrollPane.getHorizontalScrollBar().setUnitIncrement(32);
        scrollPane.getVerticalScrollBar().setUnitIncrement(32);
        panel.add(new SquareViewport(scrollPane, VIEW_PADDING), BorderLayout.CENTER);
        panel.add(matrixFooter, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createHeaderTilePanel(String title, JLabel summary, JLabel icon) {
        JPanel panel = new JPanel(new BorderLayout(4, 2));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setPreferredSize(new Dimension(150, 78));
        summary.setHorizontalAlignment(SwingConstants.LEFT);
        icon.setHorizontalAlignment(SwingConstants.LEFT);
        icon.setVerticalAlignment(SwingConstants.CENTER);
        panel.add(summary, BorderLayout.NORTH);
        panel.add(icon, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMapPreviewPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));

        JPanel currentPanel = new JPanel(new BorderLayout());
        currentPanel.setBorder(BorderFactory.createTitledBorder("Current Map"));
        currentPanel.setToolTipText(
                "Click to pick A, Shift-click to pick B, or Ctrl/right-click a match");
        currentPanel.add(new SquareViewport(beforePreview, 2),
                BorderLayout.CENTER);
        panel.add(currentPanel);

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("Preview Map"));
        previewPanel.setToolTipText(
                "The proposed result; click a visible tile for B or Shift-click for A");
        previewPanel.add(new SquareViewport(afterPreview, 2),
                BorderLayout.CENTER);
        panel.add(previewPanel);
        return panel;
    }

    private JPanel createRightRail() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setPreferredSize(new Dimension(430, 720));
        panel.setMinimumSize(new Dimension(380, 520));
        panel.add(createMatrixPanel(), BorderLayout.NORTH);
        panel.add(createSelectedChunksPanel(), BorderLayout.CENTER);
        panel.add(createActionButtons(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSelectedChunksPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Selected chunks"));

        chunkScopeTable.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        chunkScopeTable.setFillsViewportHeight(true);
        chunkScopeTable.setRowSelectionAllowed(true);
        chunkScopeTable.setColumnSelectionAllowed(false);
        chunkScopeTable.setRowHeight(24);
        chunkScopeTable.getTableHeader().setReorderingAllowed(false);
        chunkScopeTable.getTableHeader().setToolTipText(
                "Click L1-L9 to toggle that layer for every selected chunk; "
                        + "right-click for explicit Check all / Uncheck all actions.");
        chunkScopeTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int viewColumn = chunkScopeTable.getTableHeader()
                        .columnAtPoint(event.getPoint());
                if (viewColumn < 0) {
                    return;
                }
                int modelColumn =
                        chunkScopeTable.convertColumnIndexToModel(viewColumn);
                if (modelColumn < 1 || modelColumn > MapGrid.numLayers) {
                    return;
                }
                int layer = modelColumn - 1;
                if (SwingUtilities.isRightMouseButton(event)) {
                    showLayerColumnMenu(event, layer);
                } else if (SwingUtilities.isLeftMouseButton(event)
                        && event.getClickCount() == 1) {
                    toggleChunkLayerColumn(layer);
                }
            }
        });
        chunkScopeTable.setToolTipText(
                "Each row stores an independent L1-L9 edit scope for one selected chunk");
        chunkScopeTable.getColumnModel().getColumn(0).setPreferredWidth(54);
        chunkScopeTable.getColumnModel().getColumn(0).setMinWidth(48);
        chunkScopeTable.getColumnModel().getColumn(0).setMaxWidth(62);
        DefaultTableCellRenderer chunkRenderer =
                new DefaultTableCellRenderer();
        chunkRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        chunkScopeTable.getColumnModel().getColumn(0)
                .setCellRenderer(chunkRenderer);
        for (int column = 1; column <= MapGrid.numLayers; column++) {
            chunkScopeTable.getColumnModel().getColumn(column).setPreferredWidth(23);
            chunkScopeTable.getColumnModel().getColumn(column).setMinWidth(22);
            chunkScopeTable.getColumnModel().getColumn(column).setMaxWidth(27);
        }
        int applyColumn = MapGrid.numLayers + 1;
        int removeColumn = MapGrid.numLayers + 2;
        chunkScopeTable.getColumnModel().getColumn(applyColumn).setPreferredWidth(48);
        chunkScopeTable.getColumnModel().getColumn(applyColumn).setMinWidth(44);
        chunkScopeTable.getColumnModel().getColumn(applyColumn).setMaxWidth(56);
        chunkScopeTable.getColumnModel().getColumn(removeColumn).setPreferredWidth(40);
        chunkScopeTable.getColumnModel().getColumn(removeColumn).setMinWidth(40);
        chunkScopeTable.getColumnModel().getColumn(removeColumn).setMaxWidth(40);
        chunkScopeTable.getColumnModel().getColumn(applyColumn).setCellRenderer(
                new ChunkActionButtonRenderer("Apply", null,
                        "Apply the current operation only to this chunk"));
        chunkScopeTable.getColumnModel().getColumn(applyColumn).setCellEditor(
                new ChunkActionButtonEditor("Apply", null,
                        "Apply the current operation only to this chunk",
                        this::applySelectedOperationToChunk));
        Icon trashIcon = new TrashIcon();
        chunkScopeTable.getColumnModel().getColumn(removeColumn).setCellRenderer(
                new ChunkActionButtonRenderer("", trashIcon,
                        "Remove this chunk from the editing scope"));
        chunkScopeTable.getColumnModel().getColumn(removeColumn).setCellEditor(
                new ChunkActionButtonEditor("", trashIcon,
                        "Remove this chunk from the editing scope",
                        this::removeSelectedChunk));
        chunkScopeTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || updatingChunkScopeTable) {
                return;
            }
            int viewRow = chunkScopeTable.getSelectionModel()
                    .getLeadSelectionIndex();
            if (viewRow < 0) {
                return;
            }
            int modelRow = chunkScopeTable.convertRowIndexToModel(viewRow);
            Point point = chunkScopeModel.getPoint(modelRow);
            if (point != null) {
                previewMatrixChunk(point);
                status.setText("Showing selected chunk "
                        + handler.getMapMatrix().getMapName(point)
                        + " (" + point.x + ", " + point.y + ").");
            }
        });
        chunkScopeTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                "removeSelectedChunks");
        chunkScopeTable.getActionMap().put("removeSelectedChunks",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        removeSelectedChunkRows();
                    }
                });

        JScrollPane scroll = new JScrollPane(chunkScopeTable);
        scroll.setPreferredSize(new Dimension(410, 220));
        scroll.setMinimumSize(new Dimension(330, 130));
        panel.add(scroll, BorderLayout.CENTER);

        JLabel layerScope = new JLabel(
                "Edit boxes directly; click an L1-L9 header to toggle its whole column.");
        layerScope.setForeground(UIManager.getColor("Label.disabledForeground"));
        layerScope.setToolTipText(
                "Right-click an L1-L9 header for Check all and Uncheck all actions");
        panel.add(layerScope, BorderLayout.SOUTH);
        return panel;
    }

    private void showLayerColumnMenu(MouseEvent event, int layer) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem checkAll = new JMenuItem(
                "Check all chunks for L" + (layer + 1));
        checkAll.addActionListener(
                ignored -> setChunkLayerColumn(layer, true));
        menu.add(checkAll);
        JMenuItem uncheckAll = new JMenuItem(
                "Uncheck all chunks for L" + (layer + 1));
        uncheckAll.addActionListener(
                ignored -> setChunkLayerColumn(layer, false));
        menu.add(uncheckAll);
        menu.show(chunkScopeTable.getTableHeader(),
                event.getX(), event.getY());
    }

    private void toggleChunkLayerColumn(int layer) {
        boolean everyChunkChecked = chunkScopeModel.getRowCount() > 0;
        for (int row = 0; row < chunkScopeModel.getRowCount(); row++) {
            Point point = chunkScopeModel.getPoint(row);
            if (point == null || !chunkLayerScopes.isSelected(point, layer)) {
                everyChunkChecked = false;
                break;
            }
        }
        setChunkLayerColumn(layer, !everyChunkChecked);
    }

    private void setChunkLayerColumn(int layer, boolean selected) {
        int changed = 0;
        for (int row = 0; row < chunkScopeModel.getRowCount(); row++) {
            Point point = chunkScopeModel.getPoint(row);
            if (point == null
                    || chunkLayerScopes.isSelected(point, layer) == selected) {
                continue;
            }
            chunkLayerScopes.setSelected(point, layer, selected);
            changed++;
        }
        chunkScopeModel.fireLayerColumnChanged(layer);
        loadLayerChecksForPreviewMap();
        refreshSourceFilter();
        refreshPreview();
        status.setText((selected ? "Checked " : "Unchecked ")
                + "Layer " + (layer + 1) + " for "
                + chunkScopeModel.getRowCount() + " selected chunk(s)"
                + (changed == 0 ? "; no cells needed changing." : "."));
    }

    private String chunkScopeTooltip(JTable table, MouseEvent event) {
        int viewRow = table.rowAtPoint(event.getPoint());
        int viewColumn = table.columnAtPoint(event.getPoint());
        if (viewRow < 0 || viewColumn < 0) {
            return "Each row stores an independent L1-L9 edit scope.";
        }
        int row = table.convertRowIndexToModel(viewRow);
        int column = table.convertColumnIndexToModel(viewColumn);
        Point point = chunkScopeModel.getPoint(row);
        if (point == null) {
            return null;
        }
        if (column == 0) {
            return handler.getMapMatrix().getMapName(point)
                    + " (" + point.x + ", " + point.y + ") \u2014 click to preview";
        }
        if (column <= MapGrid.numLayers) {
            return "Layer " + column + " is "
                    + (chunkLayerScopes.isSelected(point, column - 1)
                    ? "included" : "excluded")
                    + " for chunk (" + point.x + ", " + point.y + ")";
        }
        return column == MapGrid.numLayers + 1
                ? "Apply the current operation only to this chunk"
                : "Remove this chunk from the editing scope";
    }

    private void removeSelectedChunk(Point point) {
        if (point == null) {
            return;
        }
        Set<Point> selected = matrixSelection.getSelectedMaps();
        if (selected.remove(point)) {
            matrixSelection.setSelectedMaps(selected);
            status.setText("Removed chunk (" + point.x + ", " + point.y
                    + ") from the editing scope.");
        }
    }

    private void removeSelectedChunkRows() {
        int[] rows = chunkScopeTable.getSelectedRows();
        if (rows.length == 0) {
            showValidation("Select one or more chunk rows to remove.");
            return;
        }
        Set<Point> selected = matrixSelection.getSelectedMaps();
        for (int viewRow : rows) {
            Point point = chunkScopeModel.getPoint(
                    chunkScopeTable.convertRowIndexToModel(viewRow));
            if (point != null) {
                selected.remove(point);
            }
        }
        matrixSelection.setSelectedMaps(selected);
        status.setText("Removed " + rows.length
                + " chunk(s) from the selected scope.");
    }

    private JPanel createOperationPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Operation"));
        panel.setPreferredSize(new Dimension(620, 330));
        panel.setMinimumSize(new Dimension(560, 260));

        operations.addTab("Global Tile Editor", createReplacePanel());
        operations.setToolTipTextAt(0,
                "Replace, swap, or delete included tile A occurrences without changing heights.");
        operations.addTab("Global Layer Editor", createCopyPanel());
        operations.setToolTipTextAt(1,
                "Copy, cut and paste, or swap layer data while preserving cell positions.");
        operations.addTab("Global Height Editor", createHeightPanel());
        operations.setToolTipTextAt(2,
                "Raise, lower, or reset checked layers while keeping values in the supported range.");
        panel.add(operations, BorderLayout.CENTER);
        panel.add(createOperationSummaryBar(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createReplacePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(new EmptyBorder(10, 12, 8, 12));
        panel.add(replaceSummary, BorderLayout.NORTH);

        ButtonGroup tileModes = new ButtonGroup();
        tileModes.add(replaceMode);
        tileModes.add(swapTiles);
        tileModes.add(deleteMode);
        replaceMode.setToolTipText(
                "Change included A occurrences to B; existing B occurrences stay B");
        swapTiles.setToolTipText(
                "Exchange both directions: included A becomes B and included B becomes A");
        deleteMode.setToolTipText("Remove included A occurrences, leaving heights unchanged");

        ButtonGroup collisions = new ButtonGroup();
        collisions.add(keepCollisions);
        collisions.add(refreshCollisionDefaults);
        keepCollisions.setToolTipText("Tile IDs change; existing collision cells are untouched");
        refreshCollisionDefaults.setToolTipText(
                "Recalculate tileset collision defaults for each changed map after the tile edit");

        resetCellSelection.setText("Include All Matches");
        resetCellSelection.setFocusable(false);
        resetCellSelection.setToolTipText(
                "Remove per-cell exclusions so every matching occurrence is changed");
        resetCellSelection.addActionListener(e -> {
            excludedMatches.clear();
            refreshPreview();
        });
        showMatchHighlights.setFocusable(false);
        showMatchHighlights.setToolTipText(
                "Outline included matches on Current Map");
        showMatchHighlights.addActionListener(e -> beforePreview.repaint());
        JPanel groups = createOperationGroupGrid(
                createOperationGroup("Action",
                        replaceMode, swapTiles, deleteMode),
                createOperationGroup("Collisions",
                        keepCollisions, refreshCollisionDefaults),
                createOperationGroup("Matches",
                        showMatchHighlights,
                        resetCellSelection,
                        createMutedLabel("Right-click a match to exclude it.")));
        panel.add(groups, BorderLayout.CENTER);
        panel.add(createOperationDiagram(0), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCopyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(10, 12, 8, 12));
        ButtonGroup transferModes = new ButtonGroup();
        transferModes.add(copyAndPaste);
        transferModes.add(cutAndPaste);
        transferModes.add(swapLayerContents);

        JPanel layerRoute = new JPanel();
        layerRoute.setLayout(new BoxLayout(layerRoute, BoxLayout.Y_AXIS));
        JLabel layerRouteTitle = createOperationGroupTitle("Layer route");
        layerRouteTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        layerRoute.add(layerRouteTitle);
        layerRoute.add(Box.createVerticalStrut(6));
        layerRoute.add(createLayerChoiceRow("From", true));
        layerRoute.add(Box.createVerticalStrut(4));
        layerRoute.add(createLayerChoiceRow("To", false));
        panel.add(layerRoute, BorderLayout.NORTH);

        ButtonGroup scopeModes = new ButtonGroup();
        scopeModes.add(copySelectedTileOnly);
        scopeModes.add(copyEntireLayer);
        ButtonGroup dataModes = new ButtonGroup();
        dataModes.add(copyTilesOnly);
        dataModes.add(copyHeightsOnly);
        dataModes.add(copyTilesAndHeights);
        copySelectedTileOnly.setToolTipText(
                "Transfer only cells containing the selected Tile A");
        copyEntireLayer.setToolTipText(
                "Copy every cell from the source layer into the target layer");
        copyTilesOnly.setToolTipText(
                "Copy tile values while leaving target heights unchanged");
        copyHeightsOnly.setToolTipText(
                "Copy height values while leaving both tile layers unchanged");
        copyTilesAndHeights.setToolTipText(
                "Copy both tile and height values into the target layer");
        copyAndPaste.setToolTipText("Keep matching Tile A cells in the source layer");
        cutAndPaste.setToolTipText(
                "Clear matching source tiles after pasting; source heights remain unchanged");
        swapLayerContents.setToolTipText(
                "Exchange the chosen tile and/or height data between both complete layers");
        copyAllSelectedChunks.setToolTipText(
                "When checked, Apply runs this layer operation across every Matrix-selected chunk");
        panel.add(createOperationGroupGrid(
                createOperationGroup("Transfer",
                        copyAndPaste, cutAndPaste, swapLayerContents),
                createOperationGroup("Cells",
                        copySelectedTileOnly, copyEntireLayer),
                createOperationGroup("Include",
                        copyTilesOnly, copyHeightsOnly, copyTilesAndHeights),
                createOperationGroup("Apply scope",
                        copyAllSelectedChunks,
                        createMutedLabel("Unchecked: current preview chunk"))),
                BorderLayout.CENTER);
        panel.add(createOperationDiagram(1), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createHeightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(10, 12, 8, 12));
        ButtonGroup modes = new ButtonGroup();
        modes.add(adjustHeightsMode);
        modes.add(resetHeightsMode);
        JPanel amount = createOperationRow();
        heightAmount.setPreferredSize(new Dimension(80, 24));
        amount.add(heightAmount);

        occupiedOnly.setToolTipText("Ignore empty cells while adjusting heights");
        showHeightNumbers.setFocusable(false);
        showHeightNumbers.setToolTipText(
                "Show height values for the yellow-selected layer");
        showHeightNumbers.addActionListener(e -> refreshPreview());
        updateHeightToggleLabel();
        panel.add(createOperationGroupGrid(
                createOperationGroup("Mode",
                        adjustHeightsMode, resetHeightsMode),
                createOperationGroup("Height adjustment",
                        new JLabel("Change checked layers by"), amount),
                createOperationGroup("Cells",
                        occupiedOnly),
                createOperationGroup("Preview",
                        showHeightNumbers,
                        createMutedLabel(
                                "Click a Layer card to choose the numbered layer."))),
                BorderLayout.CENTER);
        panel.add(createOperationDiagram(2), BorderLayout.SOUTH);
        return panel;
    }

    private OperationDiagram createOperationDiagram(int operation) {
        OperationDiagram diagram = new OperationDiagram(operation);
        operationDiagrams[operation] = diagram;
        return diagram;
    }

    private JPanel createOperationGroupGrid(JPanel... groups) {
        JPanel panel = new JPanel(new GridLayout(1, groups.length, 32, 0));
        for (JPanel group : groups) {
            panel.add(group);
        }
        return panel;
    }

    private JPanel createOperationGroup(String title, JComponent... controls) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel heading = createOperationGroupTitle(title);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(8));
        for (JComponent control : controls) {
            control.setAlignmentX(Component.LEFT_ALIGNMENT);
            control.setMaximumSize(new Dimension(
                    control.getPreferredSize().width,
                    control.getPreferredSize().height));
            panel.add(control);
            panel.add(Box.createVerticalStrut(6));
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JLabel createOperationGroupTitle(String text) {
        JLabel label = new JLabel(text);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            label.setFont(font.deriveFont(Font.BOLD));
        }
        return label;
    }

    private JPanel createOperationRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return row;
    }

    private JPanel createLayerChoiceRow(String label, boolean source) {
        JPanel row = createOperationRow();
        JLabel name = new JLabel(label + ":");
        name.setPreferredSize(new Dimension(52, 24));
        row.add(name);
        ButtonGroup group = new ButtonGroup();
        JToggleButton[] buttons = source ? copySourceButtons : copyTargetButtons;
        for (int layer = 0; layer < MapGrid.numLayers; layer++) {
            int selectedLayer = layer;
            JToggleButton button = new JToggleButton("L" + (layer + 1));
            button.setFocusable(false);
            button.setMargin(new Insets(2, 6, 2, 6));
            Dimension buttonSize = new Dimension(34, 24);
            button.setPreferredSize(buttonSize);
            button.setMinimumSize(buttonSize);
            button.setMaximumSize(buttonSize);
            button.putClientProperty("defaultBorder", button.getBorder());
            button.setToolTipText((source ? "Move or copy from Layer " : "Place into Layer ")
                    + (layer + 1));
            button.addActionListener(e -> {
                if (!updatingCopyLayerControls) {
                    if (source) {
                        setCopySourceLayer(selectedLayer);
                    } else {
                        setCopyTargetLayer(selectedLayer);
                    }
                }
            });
            group.add(button);
            buttons[layer] = button;
            row.add(button);
        }
        return row;
    }

    private JLabel createMutedLabel(String text) {
        JLabel label = new JLabel("<html>" + text + "</html>");
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JPanel createButtonBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        status.setBorder(new EmptyBorder(0, 2, 0, 4));
        panel.add(status, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createOperationSummaryBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new EmptyBorder(4, 8, 2, 4));
        previewStats.setEditable(false);
        previewStats.setOpaque(false);
        previewStats.setLineWrap(false);
        previewStats.setFont(UIManager.getFont("Label.font"));
        previewStats.setBorder(new EmptyBorder(3, 0, 0, 0));
        previewStats.setPreferredSize(new Dimension(360, 28));
        bar.add(previewStats, BorderLayout.CENTER);
        return bar;
    }

    private JPanel createActionButtons() {
        JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
        buttons.setBorder(new EmptyBorder(4, 0, 0, 0));
        JButton apply = new JButton("Apply");
        apply.setFocusable(false);
        apply.setMargin(new Insets(2, 2, 2, 2));
        apply.setToolTipText(
                "Apply the current operation to the shown selected chunk");
        apply.addActionListener(e -> applySelectedOperation(false));
        JButton applyAll = new JButton("Apply All");
        applyAll.setFocusable(false);
        applyAll.setMargin(new Insets(2, 2, 2, 2));
        applyAll.setToolTipText(
                "Apply the current operation to every Matrix-selected chunk");
        applyAll.addActionListener(e -> applySelectedOperation(true));
        JButton undo = new JButton("Undo");
        undo.setFocusable(false);
        undo.setMargin(new Insets(2, 2, 2, 2));
        undo.setToolTipText("Undo the most recent map edit");
        undo.addActionListener(e -> undoLastEdit());
        buttons.add(apply);
        buttons.add(applyAll);
        buttons.add(undo);
        buttons.setPreferredSize(new Dimension(310, 32));
        return buttons;
    }

    private void undoLastEdit() {
        if (handler.getMapStateHandler().canGetPreviousState()) {
            owner.undoMapState();
            excludedMatches.clear();
            refreshWorkspaceVisuals();
            status.setText("Undid the most recent map edit.");
        } else {
            showValidation("There is no map edit to undo.");
        }
    }

    private void installReactiveControls() {
        replaceMode.addActionListener(e -> tileModeChanged());
        swapTiles.addActionListener(e -> tileModeChanged());
        deleteMode.addActionListener(e -> tileModeChanged());
        copySourceLayer.addActionListener(e -> {
            refreshCopyLayerControls();
            refreshSourceFilter();
            refreshPreview();
        });
        copyTargetLayer.addActionListener(e -> {
            refreshCopyLayerControls();
            refreshPreview();
        });
        copyTilesOnly.addActionListener(e -> copyModeChanged());
        copyHeightsOnly.addActionListener(e -> copyModeChanged());
        copyTilesAndHeights.addActionListener(e -> copyModeChanged());
        copySelectedTileOnly.addActionListener(e -> copyModeChanged());
        copyEntireLayer.addActionListener(e -> copyModeChanged());
        copyAndPaste.addActionListener(e -> copyModeChanged());
        cutAndPaste.addActionListener(e -> copyModeChanged());
        swapLayerContents.addActionListener(e -> copyModeChanged());
        copyAllSelectedChunks.addActionListener(e -> refreshPreview());
        heightAmount.addChangeListener(e -> refreshPreview());
        occupiedOnly.addActionListener(e -> refreshPreview());
        adjustHeightsMode.addActionListener(e -> heightModeChanged());
        resetHeightsMode.addActionListener(e -> heightModeChanged());
        keepCollisions.addActionListener(e -> refreshPreview());
        refreshCollisionDefaults.addActionListener(e -> refreshPreview());
        operations.addChangeListener(e -> operationChanged());
        copyModeChanged();
        heightModeChanged();
        operationChanged();
    }

    private void installPreviewMapChoiceListener() {
        previewMapChoice.addActionListener(e -> {
            if (updatingPreviewMapChoice) {
                return;
            }
            MapChoice choice = (MapChoice) previewMapChoice.getSelectedItem();
            previewMap = choice == null ? null : new Point(choice.point);
            previewOnlyMap = choice != null && choice.previewOnly
                    ? new Point(choice.point) : null;
            matrixSelection.setPreviewMap(previewMap);
            loadLayerChecksForPreviewMap();
            refreshLayerPreviews();
            refreshPreview();
        });
    }

    private void copyModeChanged() {
        boolean swapping = swapLayerContents.isSelected();
        if (swapping) {
            copyEntireLayer.setSelected(true);
        }
        copySelectedTileOnly.setEnabled(!swapping);
        copyEntireLayer.setEnabled(!swapping);
        boolean selectedOnly = copySelectedTileOnly.isSelected() && !swapping;
        copyAndPaste.setEnabled(true);
        cutAndPaste.setEnabled(selectedOnly && copiesTiles());
        if (!cutAndPaste.isEnabled() && cutAndPaste.isSelected()) {
            copyAndPaste.setSelected(true);
        }
        refreshSourceFilter();
        refreshPreview();
    }

    private boolean copiesTiles() {
        return copyTilesOnly.isSelected() || copyTilesAndHeights.isSelected();
    }

    private boolean copiesHeights() {
        return copyHeightsOnly.isSelected() || copyTilesAndHeights.isSelected();
    }

    private void heightModeChanged() {
        boolean adjusting = adjustHeightsMode.isSelected();
        heightAmount.setEnabled(adjusting);
        occupiedOnly.setEnabled(adjusting);
        refreshPreview();
    }

    private void setCopySourceLayer(int layer) {
        int oldSource = copySourceLayer.getSelectedIndex();
        if (layer == copyTargetLayer.getSelectedIndex()) {
            copyTargetLayer.setSelectedIndex(oldSource);
        }
        copySourceLayer.setSelectedIndex(layer);
        status.setText("Global Layer Editor source set to Layer " + (layer + 1)
                + ". Shift-click a Layer card to choose the target.");
    }

    private void setCopyTargetLayer(int layer) {
        int oldTarget = copyTargetLayer.getSelectedIndex();
        if (layer == copySourceLayer.getSelectedIndex()) {
            copySourceLayer.setSelectedIndex(oldTarget);
        }
        copyTargetLayer.setSelectedIndex(layer);
        status.setText("Global Layer Editor target set to Layer " + (layer + 1) + ".");
    }

    private void refreshCopyLayerControls() {
        updatingCopyLayerControls = true;
        try {
            int source = copySourceLayer.getSelectedIndex();
            int target = copyTargetLayer.getSelectedIndex();
            for (int layer = 0; layer < MapGrid.numLayers; layer++) {
                if (copySourceButtons[layer] != null) {
                    JToggleButton button = copySourceButtons[layer];
                    boolean selected = layer == source;
                    button.setSelected(selected);
                    button.setForeground(selected ? COPY_SOURCE_COLOR
                            : UIManager.getColor("Button.foreground"));
                    button.setBorder(selected
                            ? BorderFactory.createLineBorder(COPY_SOURCE_COLOR, 2)
                            : (javax.swing.border.Border) button.getClientProperty(
                            "defaultBorder"));
                }
                if (copyTargetButtons[layer] != null) {
                    JToggleButton button = copyTargetButtons[layer];
                    boolean selected = layer == target;
                    button.setSelected(selected);
                    button.setForeground(selected ? COPY_TARGET_COLOR
                            : UIManager.getColor("Button.foreground"));
                    button.setBorder(selected
                            ? BorderFactory.createLineBorder(COPY_TARGET_COLOR, 2)
                            : (javax.swing.border.Border) button.getClientProperty(
                            "defaultBorder"));
                }
            }
        } finally {
            updatingCopyLayerControls = false;
        }
        refreshLayerPreviews();
    }

    private void operationChanged() {
        int operation = operations.getSelectedIndex();
        showHeightNumbers.setSelected(operation == 2);

        loadLayerChecksForPreviewMap();
        chunkScopeModel.fireTableDataChanged();
        refreshSourceFilter();
        refreshCopyLayerControls();
        refreshLayerPreviews();
        refreshPreview();
    }

    private void tileModeChanged() {
        boolean deleting = deleteMode.isSelected();
        if (deleting) {
            replacementTileIndex = EMPTY_TILE;
        } else if (replacementTileIndex == EMPTY_TILE) {
            replacementTileIndex = NO_TILE_SELECTED;
        }
        replacementBrowser.setReadOnlySelectedIndex(
                browserSelectionIndex(replacementTileIndex));
        replacementBrowser.setEnabled(true);
        updateTileModeAvailability();
        refreshCollisionDefaults.setEnabled(!deleting);
        if (deleting) {
            keepCollisions.setSelected(true);
        }
        excludedMatches.clear();
        updateTileSummaries();
        refreshSourceFilter();
        refreshPreview();
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
                sourceTileIndex = normalizeTileChoice(sourceTileIndex);
                replacementTileIndex = normalizeTileChoice(replacementTileIndex);
                sourceBrowser.setReadOnlySelectedIndex(
                        browserSelectionIndex(sourceTileIndex));
                replacementBrowser.setReadOnlySelectedIndex(
                        browserSelectionIndex(replacementTileIndex));
                updateTileModeAvailability();
                sourceBrowser.updateLayout();
                replacementBrowser.updateLayout();
                updateTileSummaries();
                refreshMapChoices(null);
                refreshSourceFilter();
                refreshWorkspaceVisuals();
            }
        });
    }

    private void fitToDesktop() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(1760, Math.max(1080, usable.width - 40));
        int height = Math.min(980, Math.max(720, usable.height - 50));
        width = Math.min(width, usable.width);
        height = Math.min(height, usable.height);
        setMinimumSize(new Dimension(Math.min(1150, usable.width),
                Math.min(700, usable.height)));
        setSize(width, height);
        setLocation(usable.x + (usable.width - width) / 2,
                usable.y + (usable.height - height) / 2);
    }

    private void showFindScopeMenu(Component invoker, boolean source) {
        String tileName = source ? "A" : "B";
        JPopupMenu menu = new JPopupMenu();
        JMenuItem checked = new JMenuItem("Select chunks \u2014 current chunk layers");
        checked.setToolTipText(
                "Find tile " + tileName
                        + " using the checked layers of the currently selected chunk");
        checked.addActionListener(e -> selectChunksContainingTile(source, false));
        menu.add(checked);

        JMenuItem allLayers = new JMenuItem("Select chunks + matching layers \u2014 all layers");
        allLayers.setToolTipText(
                "Search every layer, highlight matching chunks, and check layers containing tile "
                        + tileName);
        allLayers.addActionListener(e -> selectChunksContainingTile(source, true));
        menu.add(allLayers);
        menu.show(invoker, 0, invoker.getHeight());
    }

    private void selectChunksContainingTile(boolean source, boolean scanAllLayers) {
        int tileIndex = source ? sourceTileIndex : replacementTileIndex;
        String tileName = source ? "A" : "B";
        if (!isTileChoiceConfigured(tileIndex)) {
            showValidation("Choose tile " + tileName
                    + " or set it to Empty before searching.");
            return;
        }
        if (tileIndex >= 0 && handler.getTileset().size() == 0) {
            showValidation("Load a tileset before searching for tile usage.");
            return;
        }
        int[] layers = scanAllLayers ? allLayers() : selectedLayers();
        if (layers.length == 0) {
            showValidation("Check at least one layer, or choose the all-layers search.");
            return;
        }

        LinkedHashSet<Point> matchingMaps = new LinkedHashSet<>();
        boolean[] matchingLayers = new boolean[MapGrid.numLayers];
        for (Map.Entry<Point, MapData> entry
                : handler.getMapMatrix().getMatrix().entrySet()) {
            boolean mapMatched = false;
            ArrayList<Integer> mapLayers = new ArrayList<>();
            for (int layer : layers) {
                if (layerContains(entry.getValue().getGrid(), layer, tileIndex)) {
                    matchingLayers[layer] = true;
                    mapLayers.add(layer);
                    mapMatched = true;
                }
            }
            if (mapMatched) {
                Point point = new Point(entry.getKey());
                matchingMaps.add(point);
                int[] scope = scanAllLayers
                        ? mapLayers.stream().mapToInt(Integer::intValue).toArray()
                        : Arrays.copyOf(layers, layers.length);
                chunkLayerScopes.setLayers(point, scope);
            }
        }

        if (matchingMaps.isEmpty()) {
            showValidation("Tile " + tileName + " was not found in the "
                    + (scanAllLayers ? "matrix."
                    : "current chunk's checked layers."));
            return;
        }
        matrixSelection.setSelectedMaps(matchingMaps);
        status.setText("Found tile " + tileName + " in "
                + matchingMaps.size() + " chunk(s)"
                + (scanAllLayers ? " across " + countTrue(matchingLayers)
                + " matching layer(s); each row keeps only its own matches."
                : " within the selected chunk's checked layers."));
    }

    private static boolean layerContains(MapGrid grid, int layer, int tileIndex) {
        for (int x = 0; x < MapGrid.cols; x++) {
            for (int y = 0; y < MapGrid.rows; y++) {
                if (grid.tileLayers[layer][x][y] == tileIndex) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private void setSourceTileIndex(int index) {
        sourceTileIndex = normalizeTileChoice(index);
        sourceBrowser.setReadOnlySelectedIndex(
                browserSelectionIndex(sourceTileIndex));
        updateTileModeAvailability();
        excludedMatches.clear();
        updateTileSummaries();
        refreshSourceFilter();
        refreshPreview();
    }

    private void setReplacementTileIndex(int index) {
        replacementTileIndex = normalizeTileChoice(index);
        if (replacementTileIndex == EMPTY_TILE) {
            deleteMode.setSelected(true);
            keepCollisions.setSelected(true);
        } else if (deleteMode.isSelected()) {
            replaceMode.setSelected(true);
        }
        replacementBrowser.setReadOnlySelectedIndex(
                browserSelectionIndex(replacementTileIndex));
        updateTileModeAvailability();
        refreshCollisionDefaults.setEnabled(
                replacementTileIndex != EMPTY_TILE);
        excludedMatches.clear();
        updateTileSummaries();
        refreshSourceFilter();
        refreshPreview();
    }

    private int clampTileIndex(int index) {
        return Math.max(0, Math.min(index, Math.max(0, handler.getTileset().size() - 1)));
    }

    private int normalizeTileChoice(int index) {
        return index == EMPTY_TILE || index == NO_TILE_SELECTED
                ? index : clampTileIndex(index);
    }

    private static int browserSelectionIndex(int tileChoice) {
        return tileChoice >= 0 ? tileChoice : -1;
    }

    private static boolean isTileChoiceConfigured(int tileChoice) {
        return tileChoice != NO_TILE_SELECTED;
    }

    private void updateTileModeAvailability() {
        boolean canSwap = sourceTileIndex >= 0 && replacementTileIndex >= 0;
        swapTiles.setEnabled(canSwap);
        if (!canSwap && swapTiles.isSelected()) {
            replaceMode.setSelected(true);
        }
    }

    private void updateTileSummaries() {
        replaceSummary.setText("<html><b>A: " + tileShortName(sourceTileIndex)
                + "</b> &nbsp;\u2192&nbsp; <b>B: " + tileShortName(replacementTileIndex)
                + "</b></html>");
        sourceHeaderSummary.setText("<html><b>A: "
                + tileHeaderShortName(sourceTileIndex) + "</b></html>");
        sourceHeaderIcon.setIcon(tileHeaderIcon(sourceTileIndex));
        sourceHeaderIcon.setToolTipText(tilePickerDescription(sourceTileIndex));
        replacementHeaderSummary.setText("<html><b>B: "
                + tileHeaderShortName(replacementTileIndex) + "</b></html>");
        replacementHeaderIcon.setIcon(tileHeaderIcon(replacementTileIndex));
        replacementHeaderIcon.setToolTipText(
                tilePickerDescription(replacementTileIndex));
    }

    private ImageIcon tileHeaderIcon(int index) {
        if (index < 0 || index >= handler.getTileset().size()) {
            return null;
        }
        BufferedImage source = handler.getTileset().get(index).getThumbnail();
        if (source == null) {
            return null;
        }
        int maximum = 42;
        if (source.getWidth() <= maximum && source.getHeight() <= maximum) {
            return new ImageIcon(source);
        }
        double scale = Math.min((double) maximum / source.getWidth(),
                (double) maximum / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(scaled);
    }

    private String tileShortName(int index) {
        if (index == NO_TILE_SELECTED) {
            return "No Tile Selected";
        }
        if (index == EMPTY_TILE) {
            return "Empty Tile";
        }
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

    private String tileHeaderShortName(int index) {
        if (index == NO_TILE_SELECTED) {
            return "No Tile Selected";
        }
        if (index == EMPTY_TILE) {
            return "Empty Tile";
        }
        if (index < 0 || index >= handler.getTileset().size()) {
            return "No tile";
        }
        Tile tile = handler.getTileset().get(index);
        String name = tile.getObjFilename();
        if (name == null || name.isEmpty()) {
            name = tile.getPaletteName();
        }
        if (name == null || name.isEmpty()) {
            name = "Tile";
        }
        if (name.length() > 13) {
            name = name.substring(0, 10) + "...";
        }
        return "[" + index + "] " + escape(name);
    }

    private String tilePickerDescription(int index) {
        if (index == NO_TILE_SELECTED) {
            return "No Tile Selected";
        }
        if (index == EMPTY_TILE) {
            return "Empty Tile";
        }
        if (index < 0 || index >= handler.getTileset().size()) {
            return "Tile " + index;
        }
        Tile tile = handler.getTileset().get(index);
        String name = tile.getPaletteName();
        if (name == null || name.isEmpty()) {
            name = tile.getObjFilename();
        }
        return (name == null || name.isEmpty() ? "Tile" : name) + " [" + index + "]";
    }

    private void scrollTileBrowserToSelection(TileSelector browser) {
        SwingUtilities.invokeLater(() -> {
            int selectedY = browser.getTileSelectedY();
            browser.scrollRectToVisible(new Rectangle(0, Math.max(0, selectedY - 24),
                    Math.max(1, browser.getWidth()), 80));
        });
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

    private void refreshSourceFilter() {
        refreshTileFilter(sourceBrowser, filterSourceToSelection,
                sourceFilterStatus, sourceTileIndex);
        refreshTileFilter(replacementBrowser, filterReplacementToSelection,
                replacementFilterStatus, replacementTileIndex);
    }

    private void refreshTileFilter(TileSelector browser,
                                   JToggleButton filterToggle,
                                   JLabel filterStatus,
                                   int selectedTile) {
        if (!filterToggle.isSelected()) {
            browser.setReadOnlyVisibleIndices(null);
            filterStatus.setText(handler.getTileset().size() + " tiles");
            return;
        }
        Set<Point> maps = matrixSelection.getSelectedMaps();
        LinkedHashSet<Integer> visible = new LinkedHashSet<>();
        for (Point point : maps) {
            MapData mapData = handler.getMapMatrix().getMap(point);
            if (mapData == null) {
                continue;
            }
            int[] filterLayers = operations.getSelectedIndex() == 1
                    ? new int[]{copySourceLayer.getSelectedIndex()}
                    : layersForMap(point);
            for (int layer : filterLayers) {
                int[][] tiles = mapData.getGrid().tileLayers[layer];
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        if (tiles[x][y] >= 0) {
                            visible.add(tiles[x][y]);
                        }
                    }
                }
            }
        }
        //Keep a real selection visible so filtering never strands it.
        if (selectedTile >= 0) {
            visible.add(selectedTile);
        }
        browser.setReadOnlyVisibleIndices(visible);
        filterStatus.setText(visible.size() + " / "
                + handler.getTileset().size() + " tiles");
    }

    private void matrixSelectionChanged(Point lastTouched) {
        Set<Point> selectedMaps = matrixSelection.getSelectedMaps();
        chunkLayerScopes.synchronize(selectedMaps);
        excludedMatches.removeIf(cell -> !selectedMaps.contains(cell.getMap()));
        refreshMapChoices(lastTouched);
        refreshChunkScopeTable();
        refreshSourceFilter();
        refreshLayerPreviews();
        refreshPreview();
    }

    private void previewMatrixChunk(Point point) {
        if (point == null || !handler.getMapMatrix().getMatrix().containsKey(point)) {
            return;
        }
        boolean selected = matrixSelection.getSelectedMaps().contains(point);
        previewMap = new Point(point);
        previewOnlyMap = selected ? null : new Point(point);
        refreshMapChoices(null);
        matrixSelection.setPreviewMap(previewMap);
        loadLayerChecksForPreviewMap();
        refreshLayerPreviews();
        refreshPreview();
        status.setText((selected ? "Editing selected chunk " : "Previewing ")
                + handler.getMapMatrix().getMapName(point)
                + " (" + point.x + ", " + point.y + ")"
                + (selected ? "." : " without changing chunk selection."));
    }

    private void refreshMapChoices(Point preferred) {
        Set<Point> selected = matrixSelection.getSelectedMaps();
        if (previewOnlyMap != null
                && (!handler.getMapMatrix().getMatrix().containsKey(previewOnlyMap)
                || selected.contains(previewOnlyMap))) {
            previewOnlyMap = null;
        }
        ArrayList<Point> points = new ArrayList<>(selected);
        if (previewOnlyMap != null) {
            points.add(new Point(previewOnlyMap));
        }
        points.sort(Comparator.comparingInt((Point p) -> p.y).thenComparingInt(p -> p.x));

        Point desired = preferred != null && selected.contains(preferred)
                ? preferred
                : previewOnlyMap != null ? previewOnlyMap
                : previewMap != null && selected.contains(previewMap) ? previewMap
                : null;
        if (preferred != null && selected.contains(preferred)) {
            previewOnlyMap = null;
            points = new ArrayList<>(selected);
            points.sort(Comparator.comparingInt((Point p) -> p.y)
                    .thenComparingInt(p -> p.x));
        }
        if (desired == null) {
            desired = points.isEmpty() ? null : points.get(0);
        }

        updatingPreviewMapChoice = true;
        try {
            previewMapChoice.removeAllItems();
            MapChoice selectedChoice = null;
            for (Point point : points) {
                MapChoice choice = new MapChoice(point,
                        handler.getMapMatrix().getMapName(point),
                        previewOnlyMap != null && point.equals(previewOnlyMap));
                previewMapChoice.addItem(choice);
                if (point.equals(desired)) {
                    selectedChoice = choice;
                }
            }
            if (selectedChoice != null) {
                previewMapChoice.setSelectedItem(selectedChoice);
            }
            previewMap = desired == null ? null : new Point(desired);
        } finally {
            updatingPreviewMapChoice = false;
        }
        matrixSelection.setPreviewMap(previewMap);
        loadLayerChecksForPreviewMap();
        refreshLayerPreviews();
    }

    private void refreshPreview() {
        beforePreview.clearHover();
        afterPreview.clearHover();
        beforePreview.repaint();
        afterPreview.repaint();
        updatePreviewStats();
        resetCellSelection.setEnabled(!excludedMatches.isEmpty());
        for (OperationDiagram diagram : operationDiagrams) {
            if (diagram != null) {
                diagram.repaint();
            }
        }
    }

    private void updatePreviewStats() {
        int tab = operations.getSelectedIndex();
        Set<Point> selectedMaps = matrixSelection.getSelectedMaps();
        int chunks = selectedMaps.size();
        int scopedLayers = chunkLayerScopes.countSelectedLayers(selectedMaps);
        String scopeSummary = "  \u00b7  Chunks " + chunks
                + "  \u00b7  Checked layers " + scopedLayers;
        switch (tab) {
            case 0:
                if (!isTileChoiceConfigured(sourceTileIndex)
                        || !isTileChoiceConfigured(replacementTileIndex)) {
                    previewStats.setText("Choose A and B" + scopeSummary);
                    break;
                }
                int[] counts = countReplaceMatches(selectedMaps);
                previewStats.setText("Matches " + counts[0] + "  \u00b7  Included "
                        + counts[1] + "  \u00b7  Excluded " + (counts[0] - counts[1])
                        + scopeSummary);
                break;
            case 1:
                Set<Point> layerMaps = layerOperationScopeMaps(selectedMaps);
                String transfer = swapLayerContents.isSelected()
                        ? "Swap" : cutAndPaste.isSelected()
                        ? "Cut and paste" : "Copy";
                String matches = copySelectedTileOnly.isSelected()
                        ? "  \u00b7  Tile A matches " + countCopyMatches(layerMaps) : "";
                int targetChanges = countLayerTargetChanges(layerMaps);
                previewStats.setText(transfer + " Layer "
                        + (copySourceLayer.getSelectedIndex() + 1)
                        + (swapLayerContents.isSelected() ? " \u2194 Layer "
                        : " \u2192 Layer ")
                        + (copyTargetLayer.getSelectedIndex() + 1)
                        + matches + "  \u00b7  Target changes " + targetChanges
                        + "  \u00b7  Scope "
                        + (copyAllSelectedChunks.isSelected()
                        ? "all " + layerMaps.size() + " chunks" : "current chunk"));
                break;
            case 2:
                previewStats.setText((resetHeightsMode.isSelected()
                        ? "Reset heights to 0" : "Height adjustment")
                        + "  \u00b7  Layer "
                        + (selectedPreviewLayer + 1) + " numbers "
                        + (showHeightNumbers.isSelected() ? "shown" : "hidden")
                        + "  \u00b7  Chunks " + chunks);
                break;
            default:
                previewStats.setText(" ");
        }
    }

    private int[] countReplaceMatches(Set<Point> maps) {
        int matches = 0;
        int included = 0;
        if (!isTileChoiceConfigured(sourceTileIndex)) {
            return new int[]{0, 0};
        }
        for (Point point : maps) {
            MapData data = handler.getMapMatrix().getMap(point);
            if (data == null) {
                continue;
            }
            for (int layer : layersForMap(point)) {
                int[][] tiles = data.getGrid().tileLayers[layer];
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        int tile = tiles[x][y];
                        if (tile != sourceTileIndex
                                && !(swapTiles.isSelected() && tile == replacementTileIndex)) {
                            continue;
                        }
                        matches++;
                        if (!isExcluded(point, layer, x, y)) {
                            included++;
                        }
                    }
                }
            }
        }
        return new int[]{matches, included};
    }

    private int countCopyMatches(Set<Point> maps) {
        if (sourceTileIndex < 0) {
            return 0;
        }
        int matches = 0;
        int sourceLayer = copySourceLayer.getSelectedIndex();
        for (Point point : maps) {
            MapData data = handler.getMapMatrix().getMap(point);
            if (data == null) {
                continue;
            }
            int[][] tiles = data.getGrid().tileLayers[sourceLayer];
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    if (tiles[x][y] == sourceTileIndex) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }

    private Set<Point> layerOperationScopeMaps(Set<Point> selectedMaps) {
        if (copyAllSelectedChunks.isSelected()) {
            return selectedMaps;
        }
        LinkedHashSet<Point> current = new LinkedHashSet<>();
        if (previewMap != null && selectedMaps.contains(previewMap)) {
            current.add(new Point(previewMap));
        }
        return current;
    }

    private int countLayerTargetChanges(Set<Point> maps) {
        if (!copiesTiles() && !copiesHeights()) {
            return 0;
        }
        int changed = 0;
        int sourceLayer = copySourceLayer.getSelectedIndex();
        int targetLayer = copyTargetLayer.getSelectedIndex();
        for (Point point : maps) {
            MapData data = handler.getMapMatrix().getMap(point);
            if (data == null) {
                continue;
            }
            int[][] source = data.getGrid().tileLayers[sourceLayer];
            int[][] target = data.getGrid().tileLayers[targetLayer];
            int[][] sourceHeights = data.getGrid().heightLayers[sourceLayer];
            int[][] targetHeights = data.getGrid().heightLayers[targetLayer];
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    if ((!copySelectedTileOnly.isSelected()
                            || swapLayerContents.isSelected()
                            || source[x][y] == sourceTileIndex)
                            && ((copiesTiles() && source[x][y] != target[x][y])
                            || (copiesHeights()
                            && sourceHeights[x][y] != targetHeights[x][y]))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private int countLayerSourceCells(Set<Point> maps) {
        if (copySelectedTileOnly.isSelected()
                && !swapLayerContents.isSelected()) {
            return countCopyMatches(maps);
        }
        return maps.size() * MapGrid.cols * MapGrid.rows;
    }

    private boolean isExcluded(Point point, int layer, int x, int y) {
        return excludedMatches.contains(
                new GlobalMapOperations.TileCell(point, layer, x, y));
    }

    private ArrayList<GlobalMapOperations.TileCell> matchingCellsAt(
            Point point, int x, int y) {
        ArrayList<GlobalMapOperations.TileCell> matches = new ArrayList<>();
        if (!isTileChoiceConfigured(sourceTileIndex)) {
            return matches;
        }
        MapData data = handler.getMapMatrix().getMap(point);
        if (data == null) {
            return matches;
        }
        for (int layer : layersForMap(point)) {
            if (!previewLayerVisible[layer]) {
                continue;
            }
            int tile = data.getGrid().tileLayers[layer][x][y];
            if (tile == sourceTileIndex
                    || (swapTiles.isSelected() && tile == replacementTileIndex)) {
                matches.add(new GlobalMapOperations.TileCell(point, layer, x, y));
            }
        }
        return matches;
    }

    private void togglePreviewCell(int x, int y) {
        if (operations.getSelectedIndex() != 0 || previewMap == null) {
            showValidation("Individual cell selection is available for tile operations.");
            return;
        }
        ArrayList<GlobalMapOperations.TileCell> matches =
                matchingCellsAt(previewMap, x, y);
        if (matches.isEmpty()) {
            showValidation("That cell does not contain tile A"
                    + (swapTiles.isSelected() ? " or tile B." : "."));
            return;
        }
        boolean allExcluded = excludedMatches.containsAll(matches);
        if (allExcluded) {
            excludedMatches.removeAll(matches);
        } else {
            excludedMatches.addAll(matches);
        }
        refreshPreview();
    }

    private int previewTileIndex(MapData data, Point map, int layer,
                                 int x, int y, boolean after) {
        int current = data.getGrid().tileLayers[layer][x][y];
        if (!after) {
            return current;
        }
        int tab = operations.getSelectedIndex();
        switch (tab) {
            case 0:
                if (!isTileChoiceConfigured(sourceTileIndex)
                        || !isTileChoiceConfigured(replacementTileIndex)) {
                    return current;
                }
                if (!isLayerSelected(map, layer) || isExcluded(map, layer, x, y)) {
                    return current;
                }
                if (current == sourceTileIndex) {
                    return deleteMode.isSelected() ? -1 : replacementTileIndex;
                }
                if (swapTiles.isSelected() && current == replacementTileIndex) {
                    return sourceTileIndex;
                }
                return current;
            case 1:
                int sourceLayer = copySourceLayer.getSelectedIndex();
                int targetLayer = copyTargetLayer.getSelectedIndex();
                if (sourceLayer == targetLayer) {
                    return current;
                }
                int sourceTile = data.getGrid().tileLayers[sourceLayer][x][y];
                if (swapLayerContents.isSelected() && copiesTiles()) {
                    if (layer == sourceLayer) {
                        return data.getGrid().tileLayers[targetLayer][x][y];
                    }
                    if (layer == targetLayer) {
                        return sourceTile;
                    }
                    return current;
                }
                if (copySelectedTileOnly.isSelected()) {
                    if (sourceTile != sourceTileIndex) {
                        return current;
                    }
                    if (copiesTiles() && layer == targetLayer) {
                        return sourceTile;
                    }
                    if (cutAndPaste.isSelected() && layer == sourceLayer) {
                        return EMPTY_TILE;
                    }
                } else if (copiesTiles() && layer == targetLayer) {
                    return sourceTile;
                }
                return current;
            default:
                return current;
        }
    }

    private int previewHeightIndex(MapData data, Point map, int layer,
                                   int x, int y, boolean after) {
        int current = data.getGrid().heightLayers[layer][x][y];
        if (!after) {
            return current;
        }
        switch (operations.getSelectedIndex()) {
            case 1:
                int sourceLayer = copySourceLayer.getSelectedIndex();
                int targetLayer = copyTargetLayer.getSelectedIndex();
                if (swapLayerContents.isSelected() && copiesHeights()
                        && sourceLayer != targetLayer) {
                    if (layer == sourceLayer) {
                        return data.getGrid().heightLayers[targetLayer][x][y];
                    }
                    if (layer == targetLayer) {
                        return data.getGrid().heightLayers[sourceLayer][x][y];
                    }
                }
                if (copiesHeights() && sourceLayer != targetLayer
                        && layer == targetLayer
                        && (!copySelectedTileOnly.isSelected()
                        || data.getGrid().tileLayers[sourceLayer][x][y]
                        == sourceTileIndex)) {
                    return data.getGrid().heightLayers[sourceLayer][x][y];
                }
                return current;
            case 2:
                if (!isLayerSelected(map, layer)
                        || (adjustHeightsMode.isSelected()
                        && occupiedOnly.isSelected()
                        && data.getGrid().tileLayers[layer][x][y] < 0)) {
                    return current;
                }
                if (resetHeightsMode.isSelected()) {
                    return 0;
                }
                int amount = (Integer) heightAmount.getValue();
                return Math.max(MapEditorHandler.minHeight,
                        Math.min(MapEditorHandler.maxHeight, current + amount));
            default:
                return current;
        }
    }

    private ArrayList<PreviewTile> previewTiles(MapData data, Point map,
                                                boolean after, int layerFilter) {
        ArrayList<PreviewTile> draws = new ArrayList<>();
        int firstLayer = layerFilter >= 0 ? layerFilter : 0;
        int lastLayer = layerFilter >= 0 ? layerFilter : MapGrid.numLayers - 1;
        for (int layer = firstLayer; layer <= lastLayer; layer++) {
            if (layerFilter < 0 && !previewLayerVisible[layer]) {
                continue;
            }
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    int tileIndex = previewTileIndex(data, map, layer, x, y, after);
                    if (tileIndex < 0 || tileIndex >= handler.getTileset().size()) {
                        continue;
                    }
                    Tile tile = handler.getTileset().get(tileIndex);
                    float depth = previewHeightIndex(
                            data, map, layer, x, y, after);
                    if (handler.useRealTimePostProcessing()) {
                        depth += tile.getZOffset();
                    }
                    draws.add(new PreviewTile(layer, x, y,
                            depth, tileIndex));
                }
            }
        }
        //The Main UI resolves effective Z depth first. With GL_LESS, an equal
        //depth pixel from a later layer does not replace a lower-numbered
        //layer that was drawn first. Java2D therefore paints higher layers
        //first and lower layers last before using row order as a final tie.
        draws.sort((left, right) -> comparePreviewDrawOrder(
                left.depth, left.y, left.layer, left.x,
                right.depth, right.y, right.layer, right.x));
        return draws;
    }

    private int previewTileDrawX(PreviewTile draw, Tile tile, int tileSize) {
        return tileDrawX(draw.x, tile, tileSize);
    }

    private int previewTileDrawY(PreviewTile draw, Tile tile, int tileSize) {
        return tileDrawY(draw.y, tile, tileSize);
    }

    private int tileDrawX(int x, Tile tile, int tileSize) {
        int offset = handler.useRealTimePostProcessing()
                ? Math.round(tile.getXOffset() * tileSize) : 0;
        return x * tileSize + offset;
    }

    private int tileDrawY(int y, Tile tile, int tileSize) {
        int offset = handler.useRealTimePostProcessing()
                ? Math.round(tile.getYOffset() * tileSize) : 0;
        return (MapGrid.rows - y - tile.getHeight()) * tileSize - offset;
    }

    private boolean isHeightAffected(MapData data, int layer, int x, int y) {
        if (!isLayerSelected(previewMap, layer)) {
            return false;
        }
        int tab = operations.getSelectedIndex();
        if (tab == 2) {
            if (resetHeightsMode.isSelected()) {
                return data.getGrid().heightLayers[layer][x][y] != 0;
            }
            if (occupiedOnly.isSelected()
                    && data.getGrid().tileLayers[layer][x][y] < 0) {
                return false;
            }
            int oldHeight = data.getGrid().heightLayers[layer][x][y];
            int amount = (Integer) heightAmount.getValue();
            int newHeight = Math.max(MapEditorHandler.minHeight,
                    Math.min(MapEditorHandler.maxHeight, oldHeight + amount));
            return oldHeight != newHeight;
        }
        return false;
    }

    private void applySelectedOperation(boolean allSelectedMaps) {
        if (operations.getSelectedIndex() == 1
                && copyAllSelectedChunks.isSelected()) {
            allSelectedMaps = true;
        }
        Set<Point> selectedMaps = matrixSelection.getSelectedMaps();
        if (selectedMaps.isEmpty()) {
            showValidation("Select at least one matrix chunk.");
            return;
        }
        Set<Point> maps = selectedMaps;
        if (!allSelectedMaps) {
            if (previewMap == null || !selectedMaps.contains(previewMap)) {
                showValidation("Choose a selected map to use Apply.");
                return;
            }
            maps = new LinkedHashSet<>();
            maps.add(new Point(previewMap));
        }
        applySelectedOperationToMaps(maps);
    }

    private void applySelectedOperationToChunk(Point point) {
        if (point == null || !matrixSelection.getSelectedMaps().contains(point)) {
            showValidation("That chunk is no longer selected.");
            return;
        }
        LinkedHashSet<Point> maps = new LinkedHashSet<>();
        maps.add(new Point(point));
        applySelectedOperationToMaps(maps);
    }

    private void applySelectedOperationToMaps(Set<Point> requestedMaps) {
        int tab = operations.getSelectedIndex();
        LinkedHashSet<Point> maps = new LinkedHashSet<>();
        int skippedChunks = 0;
        for (Point point : requestedMaps) {
            if (!matrixSelection.getSelectedMaps().contains(point)) {
                continue;
            }
            if (tab != 1 && layersForMap(point).length == 0) {
                skippedChunks++;
                continue;
            }
            maps.add(new Point(point));
        }
        if (maps.isEmpty()) {
            showValidation(tab == 1
                    ? "Select at least one matrix chunk."
                    : "Check at least one layer for the chunk(s) being applied.");
            return;
        }
        if (tab == 0 && handler.getTileset().size() == 0) {
            showValidation("Load a tileset before replacing tiles.");
            return;
        }

        switch (tab) {
            case 0:
                if (!isTileChoiceConfigured(sourceTileIndex)) {
                    showValidation("Choose find tile A or set A to Empty Tile.");
                    return;
                }
                if (!isTileChoiceConfigured(replacementTileIndex)) {
                    showValidation("Choose replacement tile B or set B to Empty Tile.");
                    return;
                }
                if (sourceTileIndex == EMPTY_TILE
                        && replacementTileIndex == EMPTY_TILE) {
                    showValidation("Empty A and Empty B would not change the map.");
                    return;
                }
                if (swapTiles.isSelected()
                        && (sourceTileIndex == EMPTY_TILE
                        || replacementTileIndex == EMPTY_TILE)) {
                    showValidation("Use Replace when either A or B is Empty Tile.");
                    return;
                }
                if (!deleteMode.isSelected()
                        && sourceTileIndex == replacementTileIndex) {
                    showValidation("Choose two different tiles.");
                    return;
                }
                if (countReplaceMatches(maps)[1] == 0) {
                    showValidation("No included tile matches remain in the chosen scope.");
                    return;
                }
                break;
            case 1:
                if (!copiesTiles() && !copiesHeights()) {
                    showValidation("Choose tile layout, height layout, or both.");
                    return;
                }
                if (copySourceLayer.getSelectedIndex() == copyTargetLayer.getSelectedIndex()) {
                    showValidation("Choose a different target layer.");
                    return;
                }
                if (!swapLayerContents.isSelected()
                        && copySelectedTileOnly.isSelected()
                        && sourceTileIndex < 0) {
                    showValidation("Choose a real Tile A to move or copy.");
                    return;
                }
                if (cutAndPaste.isSelected() && !copiesTiles()) {
                    showValidation("Cut and paste requires Tiles only or Tiles + heights.");
                    return;
                }
                if (!swapLayerContents.isSelected()
                        && copySelectedTileOnly.isSelected()
                        && countCopyMatches(maps) == 0) {
                    showValidation("Selected Tile A was not found in the chosen source layer.");
                    return;
                }
                break;
            case 2:
                int amount = (Integer) heightAmount.getValue();
                if (adjustHeightsMode.isSelected() && amount == 0) {
                    showValidation("Choose a non-zero height adjustment.");
                    return;
                }
                break;
            default:
                return;
        }

        int[] snapshotLayers = tab == 1
                ? copySnapshotLayers()
                : chunkLayerScopes.getLayerUnion(maps).stream()
                .mapToInt(Integer::intValue).toArray();
        String stateName = operationName(tab);
        GlobalMapEditState before =
                new GlobalMapEditState(stateName, handler, maps, snapshotLayers);
        int changed = 0;
        int collisionChanges = 0;
        for (Point point : maps) {
            LinkedHashSet<Point> oneMap = new LinkedHashSet<>();
            oneMap.add(new Point(point));
            int mapChanged;
            switch (tab) {
                case 0:
                    mapChanged = deleteMode.isSelected()
                            ? GlobalMapOperations.deleteTiles(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            layersForMap(point), sourceTileIndex, excludedMatches)
                            : GlobalMapOperations.replaceTiles(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            layersForMap(point), sourceTileIndex, replacementTileIndex,
                            swapTiles.isSelected(), excludedMatches);
                    if (mapChanged > 0 && !deleteMode.isSelected()
                            && refreshCollisionDefaults.isSelected()) {
                        MapData mapData = handler.getMapMatrix().getMap(point);
                        if (mapData != null) {
                            collisionChanges += CollisionDefaultsApplier.apply(
                                    handler.getTileset(), mapData.getGrid(),
                                    mapData.getCollisions());
                        }
                    }
                    break;
                case 1:
                    mapChanged = swapLayerContents.isSelected()
                            ? GlobalMapOperations.swapLayers(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            copySourceLayer.getSelectedIndex(),
                            copyTargetLayer.getSelectedIndex(),
                            copiesTiles(), copiesHeights())
                            : copySelectedTileOnly.isSelected()
                            ? GlobalMapOperations.transferSelectedTile(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            copySourceLayer.getSelectedIndex(),
                            copyTargetLayer.getSelectedIndex(), sourceTileIndex,
                            copiesTiles(), copiesHeights(),
                            cutAndPaste.isSelected())
                            : GlobalMapOperations.copyLayer(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            copySourceLayer.getSelectedIndex(),
                            copyTargetLayer.getSelectedIndex(),
                            copiesTiles(), copiesHeights());
                    break;
                case 2:
                    mapChanged = resetHeightsMode.isSelected()
                            ? GlobalMapOperations.clearLayers(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            layersForMap(point), false, true)
                            : GlobalMapOperations.adjustHeights(
                            handler.getMapMatrix().getMatrix(), oneMap,
                            layersForMap(point), (Integer) heightAmount.getValue(),
                            MapEditorHandler.minHeight, MapEditorHandler.maxHeight,
                            occupiedOnly.isSelected());
                    break;
                default:
                    mapChanged = 0;
            }
            changed += mapChanged;
        }

        if (changed == 0 && collisionChanges == 0) {
            status.setText("No matching cells needed changes.");
            return;
        }

        handler.addMapState(before);
        excludedMatches.clear();
        refreshMaps(maps, snapshotLayers);
        status.setText(stateName + ": changed " + changed + " cell(s) in "
                + maps.size() + " chunk(s)"
                + (collisionChanges > 0
                ? " and refreshed " + collisionChanges + " collision cell(s)." : ".")
                + (skippedChunks > 0 ? " Skipped " + skippedChunks
                + " chunk(s) with no checked layers." : ""));
    }

    private int[] copySnapshotLayers() {
        int source = copySourceLayer.getSelectedIndex();
        int target = copyTargetLayer.getSelectedIndex();
        if (!swapLayerContents.isSelected()
                && (!copySelectedTileOnly.isSelected()
                || !cutAndPaste.isSelected())) {
            return new int[]{target};
        }
        return source < target
                ? new int[]{source, target}
                : new int[]{target, source};
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
        refreshLayerPreviews();
        matrixSelection.updateBounds();
        chunkLayerScopes.synchronize(matrixSelection.getSelectedMaps());
        matrixSelection.revalidate();
        matrixSelection.repaint();
        refreshMapChoices(null);
        refreshChunkScopeTable();
        refreshSourceFilter();
        refreshPreview();
    }

    private void refreshChunkScopeTable() {
        updatingChunkScopeTable = true;
        try {
            chunkScopeModel.setMaps(matrixSelection.getSelectedMaps());
            chunkScopeTable.clearSelection();
            if (previewMap != null) {
                int row = chunkScopeModel.indexOf(previewMap);
                if (row >= 0) {
                    int viewRow = chunkScopeTable.convertRowIndexToView(row);
                    chunkScopeTable.getSelectionModel()
                            .setSelectionInterval(viewRow, viewRow);
                    chunkScopeTable.scrollRectToVisible(
                            chunkScopeTable.getCellRect(viewRow, 0, true));
                }
            }
        } finally {
            updatingChunkScopeTable = false;
        }
    }

    private void refreshLayerPreviews() {
        for (LayerScopePreview preview : layerPreviews) {
            if (preview != null) {
                preview.updateInteraction();
                preview.repaint();
            }
        }
    }

    private int[] selectedLayers() {
        return layersForMap(previewMap);
    }

    private int[] layersForMap(Point point) {
        return chunkLayerScopes.getLayers(point);
    }

    private boolean isLayerSelected(Point point, int layer) {
        return chunkLayerScopes.isSelected(point, layer);
    }

    private void loadLayerChecksForPreviewMap() {
        boolean selectedChunk = previewMap != null
                && chunkLayerScopes.contains(previewMap);
        boolean usesCheckedLayers = operations.getSelectedIndex() != 1;
        updatingLayerChecks = true;
        try {
            for (int layer = 0; layer < layerChecks.length; layer++) {
                layerChecks[layer].setSelected(selectedChunk
                        && isLayerSelected(previewMap, layer));
                layerChecks[layer].setEnabled(selectedChunk && usesCheckedLayers);
                layerChecks[layer].setVisible(usesCheckedLayers);
                layerChecks[layer].setToolTipText(!selectedChunk
                        ? "Select this previewed chunk before assigning edit layers"
                        : usesCheckedLayers
                        ? "Include Layer " + (layer + 1) + " for chunk ("
                        + previewMap.x + ", " + previewMap.y + ")"
                        : "Global Layer Editor uses its From and To selectors; row layer checks are not used");
            }
        } finally {
            updatingLayerChecks = false;
        }
        if (checkAllLayersButton != null) {
            checkAllLayersButton.setEnabled(selectedChunk && usesCheckedLayers);
            uncheckAllLayersButton.setEnabled(selectedChunk && usesCheckedLayers);
        }
    }

    private static int[] allLayers() {
        int[] layers = new int[MapGrid.numLayers];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = i;
        }
        return layers;
    }

    private void setAllLayers(boolean selected) {
        if (previewMap == null || !chunkLayerScopes.contains(previewMap)) {
            showValidation("Select a chunk before changing its layer scope.");
            return;
        }
        chunkLayerScopes.setAll(previewMap, selected);
        loadLayerChecksForPreviewMap();
        chunkScopeModel.fireLayerRowChanged(previewMap);
        refreshSourceFilter();
        refreshPreview();
    }

    private void selectPreviewLayer(int layer) {
        if (layer < 0 || layer >= MapGrid.numLayers) {
            return;
        }
        selectedPreviewLayer = layer;
        updateHeightToggleLabel();
        refreshLayerPreviews();
        refreshPreview();
        status.setText("Selected Layer " + (selectedPreviewLayer + 1)
                + " for height indicators. Eyes control preview visibility; "
                + "checkboxes control edit scope.");
    }

    private void setAllPreviewLayersVisible(boolean visible) {
        Arrays.fill(previewLayerVisible, visible);
        for (LayerScopePreview preview : layerPreviews) {
            if (preview != null) {
                preview.updateVisibilityButton();
            }
        }
        refreshLayerPreviews();
        refreshPreview();
        status.setText(visible ? "Showing every layer in map height order."
                : "All preview layers hidden. Edit scope is unchanged.");
    }

    private void togglePreviewLayerVisibility(int layer, boolean visible) {
        previewLayerVisible[layer] = visible;
        layerPreviews[layer].repaint();
        refreshPreview();
        status.setText("Layer " + (layer + 1)
                + (visible ? " shown" : " hidden")
                + " in both map previews. Edit scope is unchanged.");
    }

    private void updateHeightToggleLabel() {
        showHeightNumbers.setText("Height numbers (L"
                + (selectedPreviewLayer + 1) + ")");
    }

    private void showValidation(String message) {
        status.setText(message);
        Toolkit.getDefaultToolkit().beep();
    }

    private String operationName(int tab) {
        switch (tab) {
            case 0:
                return deleteMode.isSelected() ? "Global tile delete"
                        : swapTiles.isSelected() ? "Global tile swap"
                        : "Global tile replace";
            case 1:
                return swapLayerContents.isSelected()
                        ? "Global layer swap"
                        : copySelectedTileOnly.isSelected()
                        ? (cutAndPaste.isSelected()
                        ? "Global selected-tile cut and paste"
                        : "Global selected-tile copy")
                        : "Global layer copy";
            case 2:
                return resetHeightsMode.isSelected()
                        ? "Global height reset"
                        : "Global height adjustment";
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
        button.setMargin(new Insets(2, 5, 2, 5));
        button.setToolTipText(tooltip);
        return button;
    }

    private static Icon createVisibilityIcon(boolean visible) {
        return new VisibilityIcon(visible);
    }

    /** Compact vector visibility icon that remains crisp at display scaling. */
    private static final class VisibilityIcon implements Icon {

        private static final int WIDTH = 20;
        private static final int HEIGHT = 18;
        private final boolean visible;

        private VisibilityIcon(boolean visible) {
            this.visible = visible;
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color foreground = component.isEnabled()
                        ? UIManager.getColor("Label.foreground")
                        : UIManager.getColor("Label.disabledForeground");
                if (foreground == null) {
                    foreground = Color.WHITE;
                }
                g2.setColor(foreground);
                g2.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));

                Path2D eye = new Path2D.Float();
                eye.moveTo(1.5, 9);
                eye.curveTo(5.1, 3.4, 14.9, 3.4, 18.5, 9);
                eye.curveTo(14.9, 14.6, 5.1, 14.6, 1.5, 9);
                g2.draw(eye);
                g2.drawOval(7, 6, 6, 6);
                g2.fillOval(9, 8, 2, 2);

                if (!visible) {
                    Color background = component.getParent() == null
                            ? component.getBackground()
                            : component.getParent().getBackground();
                    g2.setColor(background == null ? new Color(55, 58, 60) : background);
                    g2.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.drawLine(2, 2, 18, 16);
                    g2.setColor(foreground);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.drawLine(2, 2, 18, 16);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class PreviewTile {
        private final int layer;
        private final int x;
        private final int y;
        private final float depth;
        private final int tileIndex;

        private PreviewTile(int layer, int x, int y, float depth, int tileIndex) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.tileIndex = tileIndex;
        }
    }

    private final class LayerScopePreview extends JLayeredPane {

        private final int layer;
        private final JToggleButton visibilityButton = new JToggleButton();

        private LayerScopePreview(int layer) {
            this.layer = layer;
            setPreferredSize(new Dimension(74, 66));
            setMinimumSize(new Dimension(66, 58));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            setOpaque(true);
            updateInteraction();
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            layerChecks[layer].setOpaque(false);
            layerChecks[layer].setToolTipText(
                    "Include Layer " + (layer + 1) + " in the operation");
            add(layerChecks[layer], JLayeredPane.PALETTE_LAYER);

            visibilityButton.setFocusable(false);
            visibilityButton.setBorderPainted(false);
            visibilityButton.setContentAreaFilled(false);
            visibilityButton.setOpaque(false);
            visibilityButton.setIcon(createVisibilityIcon(false));
            visibilityButton.setSelectedIcon(createVisibilityIcon(true));
            visibilityButton.setToolTipText("Show or hide Layer " + (layer + 1)
                    + " in Current Map and Preview Map");
            visibilityButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            updateVisibilityButton();
            visibilityButton.addActionListener(e -> togglePreviewLayerVisibility(
                    LayerScopePreview.this.layer, visibilityButton.isSelected()));
            add(visibilityButton, JLayeredPane.MODAL_LAYER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        int operation = operations.getSelectedIndex();
                        if (operation == 1) {
                            if (event.isShiftDown()) {
                                setCopyTargetLayer(LayerScopePreview.this.layer);
                            } else {
                                setCopySourceLayer(LayerScopePreview.this.layer);
                            }
                        } else if (operation == 2) {
                            selectPreviewLayer(LayerScopePreview.this.layer);
                        } else if (layerChecks[LayerScopePreview.this.layer].isEnabled()) {
                            layerChecks[LayerScopePreview.this.layer].doClick();
                        }
                    }
                }
            });
        }

        private void updateInteraction() {
            int operation = operations.getSelectedIndex();
            if (operation == 1) {
                setToolTipText("Layer " + (layer + 1)
                        + " \u2014 click to set FROM; Shift-click to set TO; "
                        + "eye controls preview visibility");
            } else if (operation == 2) {
                setToolTipText("Layer " + (layer + 1)
                        + " \u2014 click card to show its height numbers; "
                        + "checkbox includes it; eye controls visibility");
            } else {
                setToolTipText("Layer " + (layer + 1)
                        + " \u2014 click card or checkbox to include it; "
                        + "eye controls preview visibility");
            }
        }

        private void updateVisibilityButton() {
            visibilityButton.setSelected(previewLayerVisible[layer]);
        }

        @Override
        public void doLayout() {
            layerChecks[layer].setBounds(3, 3, 22, 22);
            visibilityButton.setBounds(getWidth() - 27, 3, 24, 22);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                BufferedImage image = renderLayerImage(layer);
                int inset = 3;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, inset, inset,
                        getWidth() - inset * 2, getHeight() - inset * 2, null);
                boolean selected = layerChecks[layer].isSelected();
                boolean copyOperation = operations.getSelectedIndex() == 1;
                boolean copySource = copyOperation
                        && copySourceLayer.getSelectedIndex() == layer;
                boolean copyTarget = copyOperation
                        && copyTargetLayer.getSelectedIndex() == layer;
                if (copyOperation && !copySource && !copyTarget) {
                    g2.setColor(new Color(0, 0, 0, 100));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                } else if (copySource || copyTarget) {
                    Color roleColor = copySource ? COPY_SOURCE_COLOR : COPY_TARGET_COLOR;
                    g2.setColor(new Color(roleColor.getRed(), roleColor.getGreen(),
                            roleColor.getBlue(), 58));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                } else if (!selected) {
                    g2.setColor(new Color(0, 0, 0, 115));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                } else {
                    g2.setColor(new Color(0, 220, 255, 42));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                }
                if (!previewLayerVisible[layer]) {
                    g2.setColor(new Color(0, 0, 0, 145));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                }
                boolean emphasized = copySource || copyTarget || (!copyOperation && selected);
                g2.setStroke(new BasicStroke(emphasized ? 3 : 1));
                g2.setColor(copySource ? COPY_SOURCE_COLOR
                        : copyTarget ? COPY_TARGET_COLOR
                        : selected ? new Color(0, 225, 255) : Color.GRAY);
                g2.drawRect(inset, inset,
                        getWidth() - inset * 2 - 1, getHeight() - inset * 2 - 1);
                if (operations.getSelectedIndex() == 2
                        && selectedPreviewLayer == layer) {
                    g2.setStroke(new BasicStroke(3));
                    g2.setColor(new Color(255, 205, 55));
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                }

                if (copySource || copyTarget) {
                    String role = copySource ? "FROM" : "TO";
                    Font oldFont = g2.getFont();
                    g2.setFont(oldFont.deriveFont(Font.BOLD, 9f));
                    FontMetrics roleMetrics = g2.getFontMetrics();
                    int roleWidth = roleMetrics.stringWidth(role) + 6;
                    g2.setColor(new Color(0, 0, 0, 180));
                    g2.fillRect(4, 4, roleWidth, roleMetrics.getHeight());
                    g2.setColor(copySource ? COPY_SOURCE_COLOR : COPY_TARGET_COLOR);
                    g2.drawString(role, 7, 4 + roleMetrics.getAscent());
                    g2.setFont(oldFont);
                }

                String label = "L" + (layer + 1);
                FontMetrics metrics = g2.getFontMetrics();
                int labelWidth = metrics.stringWidth(label) + 6;
                int labelX = getWidth() - labelWidth - 5;
                int labelY = getHeight() - metrics.getHeight() - 5;
                g2.setColor(new Color(0, 0, 0, 165));
                g2.fillRect(labelX, labelY, labelWidth, metrics.getHeight());
                g2.setColor(Color.WHITE);
                g2.drawString(label, labelX + 3, labelY + metrics.getAscent());
            } finally {
                g2.dispose();
            }
        }

        private BufferedImage renderLayerImage(int layerIndex) {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = image.getGraphics();
            try {
                graphics.setColor(PREVIEW_BACKGROUND);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                MapData data = previewMap == null
                        ? handler.getCurrentMap()
                        : handler.getMapMatrix().getMap(previewMap);
                if (data == null) {
                    return image;
                }
                for (PreviewTile draw : previewTiles(
                        data, previewMap, false, layerIndex)) {
                    BufferedImage tile =
                            handler.getTileset().get(draw.tileIndex).getSmallThumbnail();
                    if (tile != null) {
                        Tile tileDefinition =
                                handler.getTileset().get(draw.tileIndex);
                        graphics.drawImage(tile,
                                previewTileDrawX(draw, tileDefinition, 2),
                                previewTileDrawY(draw, tileDefinition, 2), null);
                    }
                }
            } finally {
                graphics.dispose();
            }
            return image;
        }
    }

    /**
     * Keeps a visual workspace square while centering it inside any extra room.
     * The same inset is used on every side so the map and matrix canvases line
     * up consistently with their surrounding borders.
     */
    private static final class SquareViewport extends JPanel {

        private final JComponent view;

        private SquareViewport(JComponent view, int padding) {
            this.view = view;
            setLayout(null);
            setBorder(new EmptyBorder(padding, padding, padding, padding));
            add(view);
        }

        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int availableWidth = Math.max(0,
                    getWidth() - insets.left - insets.right);
            int availableHeight = Math.max(0,
                    getHeight() - insets.top - insets.bottom);
            int size = Math.min(availableWidth, availableHeight);
            int x = insets.left + (availableWidth - size) / 2;
            int y = insets.top + (availableHeight - size) / 2;
            view.setBounds(x, y, size, size);
        }

        @Override
        public Dimension getPreferredSize() {
            return squareSize(view.getPreferredSize());
        }

        @Override
        public Dimension getMinimumSize() {
            return squareSize(view.getMinimumSize());
        }

        private Dimension squareSize(Dimension viewSize) {
            Insets insets = getInsets();
            int size = Math.max(viewSize.width, viewSize.height);
            return new Dimension(size + insets.left + insets.right,
                    size + insets.top + insets.bottom);
        }
    }

    private final class MapPreviewCanvas extends JComponent {

        private final boolean after;
        private Rectangle mapBounds = new Rectangle();
        private PreviewTile hoveredTile;
        private Point hoveredEmptyCell;

        private MapPreviewCanvas(boolean after) {
            this.after = after;
            setPreferredSize(new Dimension(440, 440));
            setMinimumSize(new Dimension(260, 260));
            setOpaque(true);
            setToolTipText("");
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isRightMouseButton(event)
                            || (SwingUtilities.isLeftMouseButton(event)
                            && event.isControlDown())) {
                        if (!after) {
                            Point cell = cellAt(event.getPoint());
                            if (cell != null) {
                                togglePreviewCell(cell.x, cell.y);
                            }
                        } else {
                            status.setText("Use Current Map to include or exclude matches.");
                        }
                        return;
                    }
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        return;
                    }
                    Point cell = cellAt(event.getPoint());
                    if (cell == null) {
                        status.setText("Click inside the map preview.");
                        return;
                    }
                    int tileIndex = tileAt(event.getPoint());
                    int tileChoice = tileIndex < 0 ? EMPTY_TILE : tileIndex;
                    boolean pickSource =
                            picksSourceTile(after, event.isShiftDown());
                    if (pickSource) {
                        setSourceTileIndex(tileChoice);
                        if (tileChoice >= 0) {
                            scrollTileBrowserToSelection(sourceBrowser);
                        }
                        status.setText("Picked find tile A: "
                                + tilePickerDescription(tileChoice));
                    } else {
                        setReplacementTileIndex(tileChoice);
                        if (tileChoice >= 0) {
                            scrollTileBrowserToSelection(replacementBrowser);
                        }
                        status.setText("Picked replacement tile B: "
                                + tilePickerDescription(tileChoice));
                    }
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    PreviewTile next = tileOccurrenceAt(event.getPoint());
                    Point nextEmptyCell = next == null
                            ? cellAt(event.getPoint()) : null;
                    if (!sameOccurrence(hoveredTile, next)
                            || !java.util.Objects.equals(
                            hoveredEmptyCell, nextEmptyCell)) {
                        hoveredTile = next;
                        hoveredEmptyCell = nextEmptyCell;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    clearHover();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(new Color(48, 51, 52));
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (previewMap == null) {
                    drawCenteredMessage(g2, "Choose at least one matrix chunk.");
                    return;
                }
                MapData data = handler.getMapMatrix().getMap(previewMap);
                if (data == null) {
                    drawCenteredMessage(g2, "The preview chunk is no longer available.");
                    return;
                }

                int nativeSize = MapGrid.cols * 16;
                int size = Math.max(1,
                        Math.min(nativeSize, Math.min(getWidth() - 12, getHeight() - 12)));
                mapBounds = new Rectangle((getWidth() - size) / 2,
                        (getHeight() - size) / 2, size, size);
                BufferedImage image = renderPreviewImage(data, previewMap, after);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, mapBounds.x, mapBounds.y,
                        mapBounds.width, mapBounds.height, null);
                if (operations.getSelectedIndex() == 2
                        && showHeightNumbers.isSelected()) {
                    drawHeightNumbers(g2, data);
                }
                drawAffectedCells(g2, data);
                drawHoveredTile(g2);
                g2.setStroke(new BasicStroke(2));
                g2.setColor(after ? new Color(0, 220, 255) : Color.RED);
                g2.drawRect(mapBounds.x, mapBounds.y,
                        mapBounds.width - 1, mapBounds.height - 1);
            } finally {
                g2.dispose();
            }
        }

        private BufferedImage renderPreviewImage(MapData data, Point map, boolean afterState) {
            int tileSize = 16;
            BufferedImage image = new BufferedImage(
                    MapGrid.cols * tileSize, MapGrid.rows * tileSize,
                    BufferedImage.TYPE_INT_RGB);
            Graphics graphics = image.getGraphics();
            try {
                graphics.setColor(PREVIEW_BACKGROUND);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                for (PreviewTile draw : previewTiles(
                        data, map, afterState, -1)) {
                    Tile tile = handler.getTileset().get(draw.tileIndex);
                    BufferedImage thumbnail = tile.getThumbnail();
                    if (thumbnail != null) {
                        graphics.drawImage(thumbnail,
                                previewTileDrawX(draw, tile, tileSize),
                                previewTileDrawY(draw, tile, tileSize), null);
                    }
                }
            } finally {
                graphics.dispose();
            }
            return image;
        }

        private void drawHeightNumbers(Graphics2D graphics, MapData data) {
            int layer = selectedPreviewLayer;
            if (operations.getSelectedIndex() != 2
                    || layer < 0 || layer >= MapGrid.numLayers
                    || !previewLayerVisible[layer]) {
                return;
            }
            Composite originalComposite = graphics.getComposite();
            Object originalInterpolation = graphics.getRenderingHint(
                    RenderingHints.KEY_INTERPOLATION);
            try {
                graphics.setComposite(AlphaComposite.SrcOver.derive(0.72f));
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        int height = previewHeightIndex(
                                data, previewMap, layer, x, y, after);
                        BufferedImage indicator = handler.getHeightImageByValue(height);
                        Rectangle cell = cellBounds(x, y);
                        graphics.drawImage(indicator, cell.x, cell.y,
                                cell.width, cell.height, null);
                    }
                }
            } finally {
                graphics.setComposite(originalComposite);
                if (originalInterpolation != null) {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            originalInterpolation);
                }
            }
        }

        private void drawAffectedCells(Graphics2D g2, MapData data) {
            int operation = operations.getSelectedIndex();
            if (operation == 0) {
                Set<Rectangle> includedBounds = new LinkedHashSet<>();
                Set<Rectangle> excludedBounds = new LinkedHashSet<>();
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        ArrayList<GlobalMapOperations.TileCell> matches =
                                matchingCellsAt(previewMap, x, y);
                        if (matches.isEmpty()) {
                            continue;
                        }
                        for (GlobalMapOperations.TileCell match : matches) {
                            boolean excluded = excludedMatches.contains(match);
                            if (!excluded && !showMatchHighlights.isSelected()) {
                                continue;
                            }
                            if (after && excluded) {
                                continue;
                            }
                            int tileIndex = previewTileIndex(
                                    data, previewMap, match.layer,
                                    match.x, match.y, after);
                            Rectangle bounds = operationTileBounds(
                                    tileIndex, match.x, match.y);
                            (excluded ? excludedBounds : includedBounds)
                                    .add(bounds);
                        }
                    }
                }
                drawIncludedTileStates(g2, includedBounds);
                if (!after) {
                    for (Rectangle bounds : excludedBounds) {
                        drawExcludedTileState(g2, bounds);
                    }
                }
            } else if (operation == 1) {
                drawLayerTransferStates(g2, data);
            } else if (after && operation == 2) {
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        boolean affected = false;
                        for (int layer = 0;
                             layer < MapGrid.numLayers && !affected; layer++) {
                            if (!previewLayerVisible[layer]) {
                                continue;
                            }
                            affected = isHeightAffected(data, layer, x, y);
                        }
                        if (affected) {
                            drawCellState(g2, x, y);
                        }
                    }
                }
            }
        }

        private void drawLayerTransferStates(Graphics2D g2, MapData data) {
            int sourceLayer = copySourceLayer.getSelectedIndex();
            int targetLayer = copyTargetLayer.getSelectedIndex();
            if (sourceLayer == targetLayer) {
                return;
            }
            Set<Rectangle> affected = new LinkedHashSet<>();
            int[][] sourceTiles = data.getGrid().tileLayers[sourceLayer];
            int[][] targetTiles = data.getGrid().tileLayers[targetLayer];
            int[][] sourceHeights = data.getGrid().heightLayers[sourceLayer];
            int[][] targetHeights = data.getGrid().heightLayers[targetLayer];
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    boolean selectedCell = swapLayerContents.isSelected()
                            || !copySelectedTileOnly.isSelected()
                            || sourceTiles[x][y] == sourceTileIndex;
                    if (!selectedCell) {
                        continue;
                    }
                    boolean tileChange = copiesTiles()
                            && sourceTiles[x][y] != targetTiles[x][y];
                    boolean heightChange = copiesHeights()
                            && sourceHeights[x][y] != targetHeights[x][y];
                    if (!tileChange && !heightChange) {
                        continue;
                    }

                    if (swapLayerContents.isSelected()) {
                        if (previewLayerVisible[sourceLayer]) {
                            int tile = previewTileIndex(data, previewMap,
                                    sourceLayer, x, y, after);
                            affected.add(operationTileBounds(tile, x, y));
                        }
                        if (previewLayerVisible[targetLayer]) {
                            int tile = previewTileIndex(data, previewMap,
                                    targetLayer, x, y, after);
                            affected.add(operationTileBounds(tile, x, y));
                        }
                    } else if (!after && previewLayerVisible[sourceLayer]) {
                        affected.add(operationTileBounds(
                                sourceTiles[x][y], x, y));
                    } else if (after) {
                        if (previewLayerVisible[targetLayer]) {
                            affected.add(operationTileBounds(
                                    previewTileIndex(data, previewMap,
                                            targetLayer, x, y, true), x, y));
                        }
                        if (cutAndPaste.isSelected()
                                && previewLayerVisible[sourceLayer]) {
                            affected.add(operationTileBounds(
                                    previewTileIndex(data, previewMap,
                                            sourceLayer, x, y, true), x, y));
                        }
                    }
                }
            }
            drawIncludedTileStates(g2, affected);
        }

        private Rectangle operationTileBounds(int tileIndex, int x, int y) {
            if (tileIndex >= 0 && tileIndex < handler.getTileset().size()) {
                return tileBounds(x, y, handler.getTileset().get(tileIndex));
            }
            return cellBounds(x, y);
        }

        private void drawIncludedTileStates(Graphics2D g2,
                                            Set<Rectangle> bounds) {
            if (bounds.isEmpty()) {
                return;
            }
            Area combined = new Area();
            for (Rectangle rectangle : bounds) {
                combined.add(new Area(rectangle));
            }
            Color accent = after ? new Color(40, 225, 255)
                    : new Color(255, 181, 55);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(),
                    accent.getBlue(), 46));
            g2.fill(combined);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(),
                    accent.getBlue(), 215));
            g2.setStroke(new BasicStroke(
                    Math.max(1.5f, mapBounds.width / 300f)));
            g2.draw(combined);
        }

        private void drawExcludedTileState(Graphics2D g2,
                                           Rectangle bounds) {
            g2.setColor(new Color(220, 60, 60, 72));
            g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            g2.setColor(new Color(255, 90, 90));
            g2.setStroke(new BasicStroke(
                    Math.max(1.5f, mapBounds.width / 320f)));
            g2.drawRect(bounds.x, bounds.y,
                    Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
            g2.drawLine(bounds.x + 2, bounds.y + 2,
                    bounds.x + bounds.width - 3,
                    bounds.y + bounds.height - 3);
            g2.drawLine(bounds.x + bounds.width - 3, bounds.y + 2,
                    bounds.x + 2, bounds.y + bounds.height - 3);
        }

        private void drawCellState(Graphics2D g2, int x, int y) {
            Rectangle cell = cellBounds(x, y);
            g2.setColor(new Color(105, 225, 240, 72));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(cell.x, cell.y,
                    Math.max(0, cell.width - 1), Math.max(0, cell.height - 1));
        }

        private void drawHoveredTile(Graphics2D g2) {
            Rectangle bounds;
            if (hoveredTile != null) {
                Tile tile = handler.getTileset().get(hoveredTile.tileIndex);
                bounds = tileBounds(hoveredTile.x, hoveredTile.y, tile);
            } else if (hoveredEmptyCell != null) {
                bounds = cellBounds(hoveredEmptyCell.x, hoveredEmptyCell.y);
            } else {
                return;
            }
            g2.setColor(new Color(255, 255, 255, 20));
            g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            g2.setColor(new Color(255, 255, 255, 105));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(bounds.x, bounds.y,
                    Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
        }

        private Rectangle tileBounds(int x, int y, Tile tile) {
            int nativeSize = MapGrid.cols * 16;
            int nativeX = tileDrawX(x, tile, 16);
            int nativeY = tileDrawY(y, tile, 16);
            int nativeRight = nativeX + tile.getWidth() * 16;
            int nativeBottom = nativeY + tile.getHeight() * 16;
            int x1 = mapBounds.x + nativeX * mapBounds.width / nativeSize;
            int x2 = mapBounds.x + nativeRight * mapBounds.width / nativeSize;
            int y1 = mapBounds.y + nativeY * mapBounds.height / nativeSize;
            int y2 = mapBounds.y + nativeBottom * mapBounds.height / nativeSize;
            return new Rectangle(x1, y1, Math.max(1, x2 - x1),
                    Math.max(1, y2 - y1)).intersection(mapBounds);
        }

        private Rectangle cellBounds(int x, int y) {
            int screenY = MapGrid.rows - y - 1;
            int x1 = mapBounds.x + x * mapBounds.width / MapGrid.cols;
            int x2 = mapBounds.x + (x + 1) * mapBounds.width / MapGrid.cols;
            int y1 = mapBounds.y + screenY * mapBounds.height / MapGrid.rows;
            int y2 = mapBounds.y + (screenY + 1) * mapBounds.height / MapGrid.rows;
            return new Rectangle(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
        }

        private Point cellAt(Point pixel) {
            if (!mapBounds.contains(pixel)) {
                return null;
            }
            int x = (pixel.x - mapBounds.x) * MapGrid.cols / mapBounds.width;
            int screenY = (pixel.y - mapBounds.y) * MapGrid.rows / mapBounds.height;
            int y = MapGrid.rows - screenY - 1;
            if (x < 0 || x >= MapGrid.cols || y < 0 || y >= MapGrid.rows) {
                return null;
            }
            return new Point(x, y);
        }

        private int tileAt(Point pixel) {
            PreviewTile tile = tileOccurrenceAt(pixel);
            return tile == null ? -1 : tile.tileIndex;
        }

        private PreviewTile tileOccurrenceAt(Point pixel) {
            if (!mapBounds.contains(pixel) || previewMap == null) {
                return null;
            }
            MapData data = handler.getMapMatrix().getMap(previewMap);
            if (data == null) {
                return null;
            }
            int nativeSize = MapGrid.cols * 16;
            int nativeX = (pixel.x - mapBounds.x) * nativeSize / mapBounds.width;
            int nativeY = (pixel.y - mapBounds.y) * nativeSize / mapBounds.height;
            ArrayList<PreviewTile> draws =
                    previewTiles(data, previewMap, after, -1);
            for (int i = draws.size() - 1; i >= 0; i--) {
                PreviewTile draw = draws.get(i);
                Tile tile = handler.getTileset().get(draw.tileIndex);
                BufferedImage image = tile.getThumbnail();
                if (image == null) {
                    continue;
                }
                int drawX = previewTileDrawX(draw, tile, 16);
                int drawY = previewTileDrawY(draw, tile, 16);
                int localX = nativeX - drawX;
                int localY = nativeY - drawY;
                if (localX >= 0 && localY >= 0
                        && localX < image.getWidth() && localY < image.getHeight()
                        && (image.getRGB(localX, localY) >>> 24) > 16) {
                    return draw;
                }
            }
            return null;
        }

        private boolean sameOccurrence(PreviewTile left, PreviewTile right) {
            return left == right || (left != null && right != null
                    && left.layer == right.layer && left.x == right.x
                    && left.y == right.y && left.tileIndex == right.tileIndex);
        }

        private void clearHover() {
            if (hoveredTile != null || hoveredEmptyCell != null) {
                hoveredTile = null;
                hoveredEmptyCell = null;
                repaint();
            }
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Point cell = cellAt(event.getPoint());
            if (cell == null || previewMap == null) {
                return null;
            }
            int tileIndex = tileAt(event.getPoint());
            String picking = after
                    ? "click for B, Shift-click for A"
                    : "click for A, Shift-click for B";
            if (after) {
                return (tileIndex < 0 ? "Empty Tile" : tilePickerDescription(tileIndex))
                        + " \u2014 " + picking;
            }
            ArrayList<GlobalMapOperations.TileCell> matches =
                    matchingCellsAt(previewMap, cell.x, cell.y);
            if (matches.isEmpty()) {
                return (tileIndex < 0 ? "Empty Tile" : tilePickerDescription(tileIndex))
                        + " \u2014 " + picking;
            }
            StringBuilder layers = new StringBuilder();
            for (GlobalMapOperations.TileCell match : matches) {
                if (layers.length() > 0) {
                    layers.append(", ");
                }
                layers.append(match.layer + 1);
            }
            return (tileIndex < 0 ? "Match" : tilePickerDescription(tileIndex))
                    + " at (" + cell.x + ", " + cell.y + "), layer(s) "
                    + layers + (excludedMatches.containsAll(matches)
                    ? " \u2014 excluded" : " \u2014 included")
                    + " (Ctrl/right-click to toggle)";
        }

        private void drawCenteredMessage(Graphics2D g2, String message) {
            FontMetrics metrics = g2.getFontMetrics();
            Color foreground = UIManager.getColor("Label.foreground");
            g2.setColor(foreground == null ? Color.LIGHT_GRAY : foreground);
            g2.drawString(message, Math.max(5, (getWidth() - metrics.stringWidth(message)) / 2),
                    Math.max(metrics.getAscent() + 5, getHeight() / 2));
        }
    }

    /**
     * A compact, live visual recipe for the active operation. It uses the
     * otherwise empty lower half of each tab without repeating the longer
     * status text shown below the tabs.
     */
    private final class OperationDiagram extends JComponent {

        private final int operation;

        private OperationDiagram(int operation) {
            this.operation = operation;
            setPreferredSize(new Dimension(560, 126));
            setMinimumSize(new Dimension(360, 104));
            setToolTipText(
                    "Live operation flow. It updates as the controls above change.");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color foreground = uiColor("Label.foreground", Color.LIGHT_GRAY);
                Color muted = uiColor("Label.disabledForeground",
                        new Color(155, 155, 155));
                Color border = uiColor("Separator.foreground",
                        new Color(105, 105, 105));
                Color surface = uiColor("Button.background",
                        new Color(72, 75, 76));

                int width = getWidth();
                int height = getHeight();
                if (width < 260 || height < 80) {
                    return;
                }

                g2.setColor(new Color(surface.getRed(), surface.getGreen(),
                        surface.getBlue(), 75));
                g2.fillRoundRect(1, 1, width - 3, height - 3, 12, 12);
                g2.setColor(border);
                g2.drawRoundRect(1, 1, width - 3, height - 3, 12, 12);

                Font base = uiFont("Label.font", getFont());
                g2.setFont(base.deriveFont(Font.BOLD,
                        Math.max(10f, base.getSize2D() - 1f)));
                g2.setColor(muted);
                g2.drawString("LIVE OPERATION", 14, 20);

                String statusText = diagramStatus();
                Color statusColor = "READY".equals(statusText)
                        ? new Color(90, 195, 125) : new Color(232, 178, 72);
                int statusWidth = g2.getFontMetrics().stringWidth(statusText);
                g2.setColor(statusColor);
                g2.drawString(statusText, width - statusWidth - 14, 20);

                String[][] cards = diagramCards();
                Color[] accents = diagramAccents();
                int gap = Math.max(34, Math.min(64, width / 12));
                int cardWidth = Math.min(190,
                        Math.max(92, (width - 28 - gap * 2) / 3));
                int cardHeight = Math.max(48, Math.min(62, height - 50));
                int totalWidth = cardWidth * 3 + gap * 2;
                int x = Math.max(14, (width - totalWidth) / 2);
                int y = 34 + Math.max(0, (height - 38 - cardHeight) / 2);

                for (int index = 0; index < 3; index++) {
                    drawDiagramCard(g2, x, y, cardWidth, cardHeight,
                            cards[index][0], cards[index][1],
                            diagramIcon(index), accents[index],
                            foreground, muted, surface);
                    if (index < 2) {
                        drawDiagramArrow(g2, x + cardWidth + 8,
                                x + cardWidth + gap - 8,
                                y + cardHeight / 2, muted);
                    }
                    x += cardWidth + gap;
                }
            } finally {
                g2.dispose();
            }
        }

        private String diagramStatus() {
            if (matrixSelection.getSelectedMaps().isEmpty()) {
                return "SELECT CHUNKS";
            }
            switch (operation) {
                case 0:
                    return isTileChoiceConfigured(sourceTileIndex)
                            && isTileChoiceConfigured(replacementTileIndex)
                            ? "READY" : "CHOOSE A + B";
                case 1:
                    if (!swapLayerContents.isSelected()
                            && copySelectedTileOnly.isSelected()
                            && !isTileChoiceConfigured(sourceTileIndex)) {
                        return "CHOOSE TILE A";
                    }
                    return copiesTiles() || copiesHeights()
                            ? "READY" : "CHOOSE DATA";
                case 2:
                    return adjustHeightsMode.isSelected()
                            && ((Number) heightAmount.getValue()).intValue() == 0
                            ? "NO CHANGE" : "READY";
                default:
                    return "";
            }
        }

        private String[][] diagramCards() {
            int chunks = matrixSelection.getSelectedMaps().size();
            int layers = chunkLayerScopes.countSelectedLayers(
                    matrixSelection.getSelectedMaps());
            String scope = chunks + (chunks == 1 ? " chunk" : " chunks")
                    + " \u00b7 " + layers + " layer scopes";
            switch (operation) {
                case 0:
                    String action = deleteMode.isSelected() ? "DELETE"
                            : swapTiles.isSelected() ? "SWAP" : "REPLACE";
                    String collision = refreshCollisionDefaults.isSelected()
                            ? "Rebuild collisions" : "Keep collisions";
                    String resultTitle = deleteMode.isSelected()
                            ? "RESULT \u00b7 EMPTY" : "RESULT B";
                    String result = deleteMode.isSelected()
                            ? "Matched tiles removed"
                            : tilePickerDescription(replacementTileIndex);
                    return new String[][]{
                            {"FIND A", tilePickerDescription(sourceTileIndex)},
                            {action, collision},
                            {resultTitle, result}
                    };
                case 1:
                    Set<Point> layerMaps = layerOperationScopeMaps(
                            matrixSelection.getSelectedMaps());
                    int sourceCells = countLayerSourceCells(layerMaps);
                    int targetChanges = countLayerTargetChanges(layerMaps);
                    String data = copiesTiles() && copiesHeights()
                            ? "Tiles + heights"
                            : copiesTiles() ? "Tiles only"
                            : copiesHeights() ? "Heights only"
                            : "No data selected";
                    String cells = sourceCells + (sourceCells == 1
                            ? " source cell" : " source cells");
                    String transfer = swapLayerContents.isSelected()
                            ? "SWAP LAYERS" : cutAndPaste.isSelected()
                            ? "CUT AND PASTE" : "COPY";
                    return new String[][]{
                            {"FROM \u00b7 Layer "
                                    + (copySourceLayer.getSelectedIndex() + 1),
                                    cells},
                            {transfer, data},
                            {"TO \u00b7 Layer "
                                    + (copyTargetLayer.getSelectedIndex() + 1),
                                    targetChanges + " target cell changes"}
                    };
                case 2:
                    int amount = ((Number) heightAmount.getValue()).intValue();
                    String adjustment = resetHeightsMode.isSelected()
                            ? "SET TO 0" : amount > 0 ? "RAISE +" + amount
                            : amount < 0 ? "LOWER " + amount : "NO CHANGE";
                    return new String[][]{
                            {"CHECKED LAYERS", scope},
                            {adjustment, adjustHeightsMode.isSelected()
                                    && occupiedOnly.isSelected()
                                    ? "Occupied cells only" : "Every cell"},
                            {"HEIGHT RESULT", resetHeightsMode.isSelected()
                                    ? "Checked heights become 0"
                                    : "Safely clamped to -15\u2026+15"}
                    };
                default:
                    return new String[][]{{"", ""}, {"", ""}, {"", ""}};
            }
        }

        private Color[] diagramAccents() {
            switch (operation) {
                case 0:
                case 1:
                    return new Color[]{COPY_SOURCE_COLOR,
                            uiColor("Label.disabledForeground", Color.GRAY),
                            COPY_TARGET_COLOR};
                case 2:
                    return new Color[]{new Color(230, 190, 65),
                            new Color(235, 150, 65), new Color(120, 190, 245)};
                default:
                    return new Color[]{Color.GRAY, Color.GRAY, Color.GRAY};
            }
        }

        private void drawDiagramCard(Graphics2D g2, int x, int y,
                                     int width, int height,
                                     String title, String subtitle,
                                     BufferedImage icon, Color accent, Color foreground,
                                     Color muted, Color surface) {
            g2.setColor(surface);
            g2.fillRoundRect(x, y, width, height, 10, 10);
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(x, y, width, height, 10, 10);

            int textX = x;
            int textWidth = width;
            if (icon != null) {
                int iconSize = Math.min(34, height - 14);
                double scale = Math.min((double) iconSize / icon.getWidth(),
                        (double) iconSize / icon.getHeight());
                int iconWidth = Math.max(1,
                        (int) Math.round(icon.getWidth() * scale));
                int iconHeight = Math.max(1,
                        (int) Math.round(icon.getHeight() * scale));
                Object interpolation = g2.getRenderingHint(
                        RenderingHints.KEY_INTERPOLATION);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(icon, x + 9,
                        y + (height - iconHeight) / 2,
                        iconWidth, iconHeight, null);
                if (interpolation != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            interpolation);
                }
                textX += iconSize + 10;
                textWidth -= iconSize + 12;
            }

            Font base = uiFont("Label.font", getFont());
            g2.setFont(base.deriveFont(Font.BOLD,
                    Math.max(10f, base.getSize2D() - 1f)));
            String fittedTitle = fitDiagramText(g2, title, textWidth - 12);
            FontMetrics titleMetrics = g2.getFontMetrics();
            g2.setColor(foreground);
            g2.drawString(fittedTitle,
                    textX + (textWidth
                            - titleMetrics.stringWidth(fittedTitle)) / 2,
                    y + 21);

            g2.setFont(base.deriveFont(Math.max(9f, base.getSize2D() - 2f)));
            String fittedSubtitle = fitDiagramText(g2, subtitle, textWidth - 10);
            FontMetrics subtitleMetrics = g2.getFontMetrics();
            g2.setColor(muted);
            g2.drawString(fittedSubtitle,
                    textX + (textWidth
                            - subtitleMetrics.stringWidth(fittedSubtitle)) / 2,
                    y + height - 13);
        }

        private BufferedImage diagramIcon(int cardIndex) {
            int tileIndex = NO_TILE_SELECTED;
            if (operation == 0) {
                tileIndex = cardIndex == 0 ? sourceTileIndex
                        : cardIndex == 2 ? replacementTileIndex
                        : NO_TILE_SELECTED;
            } else if (operation == 1
                    && copySelectedTileOnly.isSelected()
                    && !swapLayerContents.isSelected()
                    && (cardIndex == 0 || cardIndex == 2)) {
                tileIndex = sourceTileIndex;
            }
            if (tileIndex < 0 || tileIndex >= handler.getTileset().size()) {
                return null;
            }
            return handler.getTileset().get(tileIndex).getThumbnail();
        }

        private void drawDiagramArrow(Graphics2D g2, int startX, int endX,
                                      int y, Color color) {
            if (endX <= startX) {
                return;
            }
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(startX, y, endX, y);
            Polygon arrow = new Polygon();
            arrow.addPoint(endX, y);
            arrow.addPoint(endX - 7, y - 4);
            arrow.addPoint(endX - 7, y + 4);
            g2.fillPolygon(arrow);
        }

        private String fitDiagramText(Graphics2D g2, String text, int width) {
            String value = text == null ? "" : text;
            if (g2.getFontMetrics().stringWidth(value) <= width) {
                return value;
            }
            String suffix = "...";
            int length = value.length();
            while (length > 1 && g2.getFontMetrics().stringWidth(
                    value.substring(0, length) + suffix) > width) {
                length--;
            }
            return value.substring(0, Math.max(1, length)) + suffix;
        }

        private Color uiColor(String key, Color fallback) {
            Color color = UIManager.getColor(key);
            return color == null ? fallback : color;
        }

        private Font uiFont(String key, Font fallback) {
            Font font = UIManager.getFont(key);
            if (font != null) {
                return font;
            }
            return fallback == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12)
                    : fallback;
        }
    }

    private final class ChunkScopeTableModel extends AbstractTableModel {

        private final ArrayList<Point> points = new ArrayList<>();

        private void setMaps(Set<Point> maps) {
            points.clear();
            for (Point point : maps) {
                points.add(new Point(point));
            }
            points.sort(Comparator.comparingInt((Point point) -> point.y)
                    .thenComparingInt(point -> point.x));
            fireTableDataChanged();
        }

        private Point getPoint(int row) {
            return row < 0 || row >= points.size()
                    ? null : new Point(points.get(row));
        }

        private int indexOf(Point point) {
            return points.indexOf(point);
        }

        private void fireLayerChanged(Point point, int layer) {
            int row = indexOf(point);
            if (row >= 0) {
                fireTableCellUpdated(row, layer + 1);
            }
        }

        private void fireLayerRowChanged(Point point) {
            int row = indexOf(point);
            if (row >= 0) {
                fireTableRowsUpdated(row, row);
            }
        }

        private void fireLayerColumnChanged(int layer) {
            int column = layer + 1;
            for (int row = 0; row < points.size(); row++) {
                fireTableCellUpdated(row, column);
            }
        }

        @Override
        public int getRowCount() {
            return points.size();
        }

        @Override
        public int getColumnCount() {
            return MapGrid.numLayers + 3;
        }

        @Override
        public String getColumnName(int column) {
            if (column == 0) {
                return "Chunk";
            }
            if (column <= MapGrid.numLayers) {
                return "L" + column;
            }
            return column == MapGrid.numLayers + 1 ? "Apply" : "";
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column >= 1 && column <= MapGrid.numLayers
                    ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            if (column >= 1 && column <= MapGrid.numLayers) {
                return true;
            }
            return column > MapGrid.numLayers;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Point point = points.get(row);
            if (column == 0) {
                return "(" + point.x + ", " + point.y + ")";
            }
            if (column <= MapGrid.numLayers) {
                return chunkLayerScopes.isSelected(point, column - 1);
            }
            return column == MapGrid.numLayers + 1 ? "Apply" : "";
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column < 1 || column > MapGrid.numLayers
                    || !(value instanceof Boolean)) {
                return;
            }
            Point point = points.get(row);
            chunkLayerScopes.setSelected(point, column - 1, (Boolean) value);
            fireTableCellUpdated(row, column);
            if (point.equals(previewMap)) {
                loadLayerChecksForPreviewMap();
                refreshSourceFilter();
                refreshPreview();
            } else {
                updatePreviewStats();
            }
        }
    }

    private static final class ChunkActionButtonRenderer extends JButton
            implements TableCellRenderer {

        private ChunkActionButtonRenderer(String text, Icon icon, String tooltip) {
            super(text, icon);
            setFocusable(false);
            setMargin(new Insets(1, 1, 1, 1));
            setToolTipText(tooltip);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused,
                int row, int column) {
            setEnabled(table.isEnabled());
            return this;
        }
    }

    private final class ChunkActionButtonEditor extends AbstractCellEditor
            implements TableCellEditor {

        private final JButton button;
        private final Consumer<Point> action;
        private Point point;

        private ChunkActionButtonEditor(String text, Icon icon, String tooltip,
                                        Consumer<Point> action) {
            this.action = action;
            button = new JButton(text, icon);
            button.setFocusable(false);
            button.setMargin(new Insets(1, 1, 1, 1));
            button.setToolTipText(tooltip);
            button.addActionListener(event -> {
                Point target = point == null ? null : new Point(point);
                fireEditingStopped();
                if (target != null) {
                    action.accept(target);
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            point = chunkScopeModel.getPoint(table.convertRowIndexToModel(row));
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return null;
        }
    }

    private static final class TrashIcon implements Icon {

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(component.isEnabled()
                        ? new Color(0xDF6A6A)
                        : new Color(0x8E7777));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 3, y + 5, x + 15, y + 5);
                g2.drawLine(x + 6, y + 2, x + 12, y + 2);
                g2.drawLine(x + 8, y + 1, x + 10, y + 1);
                g2.drawRoundRect(x + 5, y + 6, 8, 10, 2, 2);
                g2.drawLine(x + 8, y + 9, x + 8, y + 13);
                g2.drawLine(x + 10, y + 9, x + 10, y + 13);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static final class MapChoice {
        private final Point point;
        private final String name;
        private final boolean previewOnly;

        private MapChoice(Point point, String name, boolean previewOnly) {
            this.point = new Point(point);
            this.name = name;
            this.previewOnly = previewOnly;
        }

        @Override
        public String toString() {
            return name + "  (" + point.x + ", " + point.y + ")"
                    + (previewOnly ? "  [Preview]" : "");
        }
    }

    private static final class MatrixSelectionPanel extends JPanel {

        private final MapEditorHandler handler;
        private final Consumer<Point> selectionListener;
        private final Consumer<Point> previewListener;
        private final Set<Point> selectedMaps = new LinkedHashSet<>();
        private final JLabel selectionLabel = new JLabel();
        private Point minimum = new Point();
        private Dimension matrixSize = new Dimension(1, 1);
        private Point previewMap;
        private Boolean dragSelectionState;
        private int cellSize = Math.max(16, MapData.mapThumbnailSize / 2);

        private MatrixSelectionPanel(MapEditorHandler handler,
                                     Consumer<Point> selectionListener,
                                     Consumer<Point> previewListener) {
            this.handler = handler;
            this.selectionListener = selectionListener;
            this.previewListener = previewListener;
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
                    if (SwingUtilities.isRightMouseButton(event)) {
                        dragSelectionState = null;
                        previewListener.accept(new Point(map));
                        return;
                    }
                    if (!SwingUtilities.isLeftMouseButton(event)) {
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

        private void setSelectedMaps(Set<Point> maps) {
            selectedMaps.clear();
            for (Point point : maps) {
                if (handler.getMapMatrix().getMatrix().containsKey(point)) {
                    selectedMaps.add(new Point(point));
                }
            }
            selectionChanged(null);
        }

        private void selectAll() {
            selectedMaps.clear();
            for (Point point : handler.getMapMatrix().getMatrix().keySet()) {
                selectedMaps.add(new Point(point));
            }
            selectionChanged(null);
        }

        private void clearSelection() {
            selectedMaps.clear();
            selectionChanged(null);
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
            selectionChanged(null);
        }

        private void setSelected(Point point, boolean selected) {
            if (selected) {
                selectedMaps.add(new Point(point));
            } else {
                selectedMaps.remove(point);
            }
            selectionChanged(point);
        }

        private void setCellSize(int cellSize) {
            this.cellSize = Math.max(16,
                    Math.min(MapData.mapThumbnailSize * 2, cellSize));
            updateBounds();
            revalidate();
            repaint();
        }

        private void setPreviewMap(Point point) {
            previewMap = point == null ? null : new Point(point);
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

        private void selectionChanged(Point lastTouched) {
            updateSelectionLabel();
            repaint();
            selectionListener.accept(lastTouched == null ? null : new Point(lastTouched));
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
                    + "  (" + point.x + ", " + point.y + ")"
                    + " \u2014 right-click to preview";
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
                    if (point.equals(previewMap)) {
                        g2.setColor(new Color(255, 205, 55));
                        g2.setStroke(new BasicStroke(Math.max(2, cellSize / 18f)));
                        g2.drawRect(x + 1, y + 1,
                                Math.max(0, cellSize - 3), Math.max(0, cellSize - 3));
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
