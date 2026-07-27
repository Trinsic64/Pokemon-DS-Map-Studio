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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
    private static final int VIEW_PADDING = 6;

    private final MainFrame owner;
    private final MapEditorHandler handler;
    private final MatrixSelectionPanel matrixSelection;
    private final JCheckBox[] layerChecks = new JCheckBox[MapGrid.numLayers];
    private final LayerScopePreview[] layerPreviews = new LayerScopePreview[MapGrid.numLayers];
    private final JTabbedPane operations = new JTabbedPane();
    private final JLabel status = new JLabel("Choose chunks, layers, and an operation.");
    private final JLabel operationHint = new JLabel(" ");

    private final TileSelector sourceBrowser = new TileSelector();
    private final TileSelector replacementBrowser = new TileSelector();
    private final JLabel sourceSummary = new JLabel(" ");
    private final JLabel replacementSummary = new JLabel(" ");
    private final JLabel replaceSummary = new JLabel(" ");
    private final JLabel headerReplaceSummary = new JLabel(" ");
    private final JCheckBox filterSourceToSelection =
            new JCheckBox("Filter to Chunks");
    private final JLabel sourceFilterStatus = new JLabel("Showing all tiles");
    private int sourceTileIndex;
    private int replacementTileIndex;
    private int visiblePreviewLayer = -1;

    private final JRadioButton replaceMode = new JRadioButton("Replace", true);
    private final JRadioButton swapTiles = new JRadioButton("Swap");
    private final JRadioButton deleteMode = new JRadioButton("Delete");
    private final JRadioButton keepCollisions =
            new JRadioButton("Keep collisions", true);
    private final JRadioButton refreshCollisionDefaults =
            new JRadioButton("Smart collisions");

    private final JComboBox<String> copySourceLayer = createLayerCombo();
    private final JComboBox<String> copyTargetLayer = createLayerCombo();
    private final JCheckBox copyTiles = new JCheckBox("Tiles", true);
    private final JCheckBox copyHeights = new JCheckBox("Heights", true);

    private final JSpinner heightAmount = new JSpinner(new SpinnerNumberModel(1, -64, 64, 1));
    private final JCheckBox occupiedOnly = new JCheckBox("Occupied only", true);

    private final JCheckBox clearTiles = new JCheckBox("Tiles", true);
    private final JCheckBox clearHeights = new JCheckBox("Heights \u2192 0", true);

    private final JComboBox<MapChoice> previewMapChoice = new JComboBox<>();
    private final JTextArea previewStats = new JTextArea(2, 20);
    private final JButton resetCellSelection = new JButton("Include all matches");
    private final JCheckBox showMatchHighlights = new JCheckBox("Show matches");
    private final MapPreviewCanvas beforePreview;
    private final MapPreviewCanvas afterPreview;
    private final Set<GlobalMapOperations.TileCell> excludedMatches = new LinkedHashSet<>();
    private Point previewMap;
    private Point previewOnlyMap;
    private boolean updatingPreviewMapChoice;

    public GlobalMapEditDialog(MainFrame owner, MapEditorHandler handler) {
        super(owner, "Global Map Editor", ModalityType.MODELESS);
        this.owner = owner;
        this.handler = handler;
        this.matrixSelection =
                new MatrixSelectionPanel(handler, this::matrixSelectionChanged,
                        this::previewMatrixChunk);
        this.beforePreview = new MapPreviewCanvas(false, true);
        this.afterPreview = new MapPreviewCanvas(true, false);

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
        add(createBody(), BorderLayout.CENTER);
        add(createButtonBar(), BorderLayout.SOUTH);

        selectCurrentLayer();
        copySourceLayer.setSelectedIndex(handler.getActiveLayerIndex());
        copyTargetLayer.setSelectedIndex((handler.getActiveLayerIndex() + 1) % MapGrid.numLayers);
        installReactiveControls();
        updateTileSummaries();
        refreshMapChoices(null);
        refreshPreview();
        installKeyboardActions();
        installWorkspaceRefresh();
        fitToDesktop();

        SwingUtilities.invokeLater(matrixSelection::showCurrentMap);
    }

    private JComponent createHeading() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        JLabel heading = new JLabel("<html><b>Global Map Editor</b><br>"
                + "<span style='font-size:9px'>Find the tile, choose its scope, preview the "
                + "result, then apply one undoable edit.</span></html>");
        panel.add(heading, BorderLayout.WEST);

        JLabel undoNote = new JLabel("Each Apply = 1 Undo");
        undoNote.setForeground(UIManager.getColor("Label.disabledForeground"));
        undoNote.setToolTipText(
                "Each Apply or Apply All action is stored as one complete undo step");
        panel.add(undoNote, BorderLayout.EAST);
        return panel;
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
                    + " in operations that use the checked-layer scope");
            layerChecks[i].addItemListener(e -> {
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
        JButton viewAll = compactButton("View All", "Composite every layer in map height order");
        viewAll.addActionListener(e -> setVisiblePreviewLayer(-1));
        JButton all = compactButton("Check All", "Include every layer in the operation");
        all.addActionListener(e -> setAllLayers(true));
        JButton none = compactButton("Uncheck", "Remove every layer from the operation scope");
        none.addActionListener(e -> setAllLayers(false));
        buttons.add(viewAll);
        buttons.add(all);
        buttons.add(none);
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
        panel.add(createTileBrowserPanel(
                "A. Find", sourceBrowser, sourceSummary, true), gbc);

        gbc.gridx = 2;
        gbc.weightx = 1;
        panel.add(createCenterWorkspace(), gbc);

        gbc.gridx = 3;
        gbc.weightx = 0;
        panel.add(createTileBrowserPanel(
                "B. Replace", replacementBrowser, replacementSummary, false), gbc);

        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(createRightRail(), gbc);
        return panel;
    }

    private JComponent createTileBrowserPanel(String title, TileSelector browser,
                                               JLabel summary, boolean source) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setPreferredSize(new Dimension(165, 720));
        panel.setMinimumSize(new Dimension(140, 420));

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JButton useSelected = new JButton("Main");
        useSelected.setFocusable(false);
        useSelected.setMargin(new Insets(2, 5, 2, 5));
        useSelected.setAlignmentX(Component.CENTER_ALIGNMENT);
        useSelected.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                useSelected.getPreferredSize().height));
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
        controls.add(useSelected);

        if (source) {
            controls.add(Box.createVerticalStrut(3));
            JButton findInMatrix = new JButton("Find");
            findInMatrix.setFocusable(false);
            findInMatrix.setAlignmentX(Component.CENTER_ALIGNMENT);
            findInMatrix.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    findInMatrix.getPreferredSize().height));
            findInMatrix.setToolTipText(
                    "Automatically highlight every matrix chunk containing the Find tile");
            findInMatrix.addActionListener(e -> showFindScopeMenu(findInMatrix));
            controls.add(findInMatrix);

            controls.add(Box.createVerticalStrut(3));
            filterSourceToSelection.setFocusable(false);
            filterSourceToSelection.setAlignmentX(Component.CENTER_ALIGNMENT);
            filterSourceToSelection.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    filterSourceToSelection.getPreferredSize().height));
            filterSourceToSelection.setText("Chunks");
            filterSourceToSelection.setToolTipText(
                    "Hide Find tiles not used by the chosen chunks and checked layers");
            filterSourceToSelection.addActionListener(e -> refreshSourceFilter());
            controls.add(filterSourceToSelection);

            sourceFilterStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
            sourceFilterStatus.setHorizontalAlignment(SwingConstants.CENTER);
            sourceFilterStatus.setForeground(
                    UIManager.getColor("Label.disabledForeground"));
            controls.add(sourceFilterStatus);
        }

        JPanel browserSurface = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        browserSurface.add(browser);
        JScrollPane scroll = new JScrollPane(browserSurface);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                int columns = Math.max(4,
                        scroll.getViewport().getExtentSize().width / 16);
                browser.setReadOnlyColumnCount(columns);
            }
        });
        panel.add(scroll, BorderLayout.CENTER);

        summary.setBorder(new EmptyBorder(3, 2, 1, 2));
        summary.setHorizontalAlignment(SwingConstants.CENTER);
        summary.setVerticalAlignment(SwingConstants.TOP);
        summary.setPreferredSize(new Dimension(135, 48));

        JPanel footer = new JPanel(new BorderLayout(3, 3));
        footer.add(summary, BorderLayout.NORTH);
        footer.add(controls, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createCenterWorkspace() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setMinimumSize(new Dimension(620, 480));
        panel.add(createMapHeaderPanel(), BorderLayout.NORTH);
        panel.add(createMapPreviewPanel(), BorderLayout.CENTER);
        panel.add(createOperationPanel(), BorderLayout.SOUTH);
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

    private JPanel createMapHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Map"));
        panel.setPreferredSize(new Dimension(540, 74));

        JPanel chooser = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chooser.add(new JLabel("Map:"));
        previewMapChoice.setPreferredSize(new Dimension(260, 24));
        previewMapChoice.addActionListener(e -> {
            if (updatingPreviewMapChoice) {
                return;
            }
            MapChoice choice = (MapChoice) previewMapChoice.getSelectedItem();
            previewMap = choice == null ? null : new Point(choice.point);
            previewOnlyMap = choice != null && choice.previewOnly
                    ? new Point(choice.point) : null;
            matrixSelection.setPreviewMap(previewMap);
            refreshLayerPreviews();
            refreshPreview();
        });
        chooser.add(previewMapChoice);
        JButton previous = compactButton("\u25c0", "Previous chosen chunk");
        previous.addActionListener(e -> movePreviewChoice(-1));
        JButton next = compactButton("\u25b6", "Next chosen chunk");
        next.addActionListener(e -> movePreviewChoice(1));
        chooser.add(previous);
        chooser.add(next);
        panel.add(chooser, BorderLayout.WEST);

        JPanel mapping = new JPanel(new BorderLayout());
        mapping.add(headerReplaceSummary, BorderLayout.NORTH);
        JLabel instruction = new JLabel("Map clicks: A \u00b7 Shift B \u00b7 Ctrl/right toggle");
        instruction.setToolTipText("Click a visible tile for A; Shift-click for B; "
                + "Ctrl-click or right-click a highlighted match to include or exclude it");
        instruction.setForeground(UIManager.getColor("Label.disabledForeground"));
        mapping.add(instruction, BorderLayout.SOUTH);
        panel.add(mapping, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMapPreviewPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));

        JPanel currentPanel = new JPanel(new BorderLayout());
        currentPanel.setBorder(BorderFactory.createTitledBorder("Current Map"));
        currentPanel.setToolTipText(
                "Click to pick A, Shift-click to pick B, or Ctrl/right-click a match");
        currentPanel.add(new SquareViewport(beforePreview, VIEW_PADDING),
                BorderLayout.CENTER);
        panel.add(currentPanel);

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("Preview Map"));
        previewPanel.setToolTipText(
                "The selected operation rendered beside the unmodified map");
        previewPanel.add(new SquareViewport(afterPreview, VIEW_PADDING),
                BorderLayout.CENTER);
        panel.add(previewPanel);
        return panel;
    }

    private JPanel createRightRail() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setPreferredSize(new Dimension(430, 720));
        panel.setMinimumSize(new Dimension(380, 520));
        panel.add(createMatrixPanel(), BorderLayout.NORTH);
        panel.add(createScopeSettingsPanel(), BorderLayout.CENTER);
        panel.add(createActionButtons(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createScopeSettingsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Scope"));

        JPanel controls = new JPanel();
        controls.setBorder(new EmptyBorder(4, 5, 4, 5));
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        previewStats.setEditable(false);
        previewStats.setOpaque(false);
        previewStats.setLineWrap(true);
        previewStats.setWrapStyleWord(true);
        previewStats.setFont(UIManager.getFont("Label.font"));
        previewStats.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewStats.setBorder(new EmptyBorder(2, 0, 5, 0));
        previewStats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        controls.add(previewStats);

        resetCellSelection.setText("Reset");
        resetCellSelection.setFocusable(false);
        resetCellSelection.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetCellSelection.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                resetCellSelection.getPreferredSize().height));
        resetCellSelection.setToolTipText(
                "Remove all per-cell exclusions so every matching occurrence is changed");
        resetCellSelection.addActionListener(e -> {
            excludedMatches.clear();
            refreshPreview();
        });
        controls.add(resetCellSelection);
        controls.add(Box.createVerticalStrut(4));

        showMatchHighlights.setFocusable(false);
        showMatchHighlights.setAlignmentX(Component.LEFT_ALIGNMENT);
        showMatchHighlights.setToolTipText(
                "Outline included matches on Current Map; Preview Map always stays clean");
        showMatchHighlights.addActionListener(e -> beforePreview.repaint());
        controls.add(showMatchHighlights);
        controls.add(Box.createVerticalStrut(4));

        JLabel clickHelp = new JLabel("A: click \u00b7 B: Shift \u00b7 Toggle: Ctrl/right");
        clickHelp.setToolTipText("Use Current Map to pick visible tiles or toggle "
                + "individual matches; enable Show matches when refining the cell scope");
        clickHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(clickHelp);
        panel.add(controls, BorderLayout.NORTH);
        return panel;
    }

    private JPanel createOperationPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Operation"));
        panel.setPreferredSize(new Dimension(520, 320));

        JPanel heading = new JPanel(new BorderLayout(6, 0));
        operationHint.setBorder(new EmptyBorder(0, 5, 0, 5));
        heading.add(operationHint, BorderLayout.CENTER);
        JButton help = compactButton("?", "What is this operation useful for?");
        help.setPreferredSize(new Dimension(34, 24));
        help.addActionListener(e -> showOperationHelp());
        heading.add(help, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        operations.addTab("Tiles", createReplacePanel());
        operations.setToolTipTextAt(0,
                "Replace, swap, or delete included tile A occurrences without changing heights.");
        operations.addTab("Copy", createCopyPanel());
        operations.setToolTipTextAt(1,
                "Copy tile and/or height layouts between layers in every chosen chunk.");
        operations.addTab("Height", createHeightPanel());
        operations.setToolTipTextAt(2,
                "Raise or lower checked layers while keeping values in the supported range.");
        operations.addTab("Clear", createClearPanel());
        operations.setToolTipTextAt(3,
                "Remove tile data and/or reset heights in checked layers.");
        panel.add(operations, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createReplacePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(7, 10, 7, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        panel.add(replaceSummary, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(4, 0, 0, 0);
        ButtonGroup tileModes = new ButtonGroup();
        tileModes.add(replaceMode);
        tileModes.add(swapTiles);
        tileModes.add(deleteMode);
        replaceMode.setToolTipText("Replace every included A occurrence with B");
        swapTiles.setToolTipText("Exchange A and B in both directions");
        deleteMode.setToolTipText("Remove included A occurrences, leaving heights unchanged");
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modes.add(replaceMode);
        modes.add(swapTiles);
        modes.add(deleteMode);
        panel.add(modes, gbc);

        ButtonGroup collisions = new ButtonGroup();
        collisions.add(keepCollisions);
        collisions.add(refreshCollisionDefaults);
        keepCollisions.setToolTipText("Tile IDs change; existing collision cells are untouched");
        refreshCollisionDefaults.setToolTipText(
                "Reapply tileset collision defaults after the tile change");
        gbc.gridy++;
        panel.add(keepCollisions, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(refreshCollisionDefaults, gbc);
        gbc.gridy++;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel unchanged = new JLabel("Heights unchanged");
        unchanged.setToolTipText(
                "Tile operations never alter height values; toggle individual matches on Current Map");
        panel.add(unchanged, gbc);
        return panel;
    }

    private JPanel createCopyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(7, 10, 7, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 3, 2, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("From:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(copySourceLayer, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("To:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(copyTargetLayer, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        copyTiles.setToolTipText("Copy the complete tile layout into the target layer");
        copyHeights.setToolTipText("Copy the complete height layout into the target layer");
        panel.add(copyTiles, gbc);
        gbc.gridy = 3;
        panel.add(copyHeights, gbc);
        gbc.gridy = 4;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel copyNote = new JLabel("Checked layers ignored");
        copyNote.setToolTipText("From and To choose the layer scope for every selected chunk");
        panel.add(copyNote, gbc);
        return panel;
    }

    private JPanel createHeightPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(7, 10, 7, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Amount:"), gbc);
        gbc.gridx = 1;
        panel.add(heightAmount, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        occupiedOnly.setToolTipText("Ignore empty cells while adjusting heights");
        panel.add(occupiedOnly, gbc);
        gbc.gridy = 2;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel heightNote = new JLabel("Valid range enforced");
        heightNote.setToolTipText(
                "Positive values raise tiles; negative values lower them; results are clamped");
        panel.add(heightNote, gbc);
        return panel;
    }

    private JPanel createClearPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(8, 10, 7, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        clearTiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        clearHeights.setAlignmentX(Component.LEFT_ALIGNMENT);
        clearTiles.setToolTipText("Remove all tiles from checked layers in the chosen chunks");
        clearHeights.setToolTipText("Reset height values to zero in the same scope");
        panel.add(clearTiles);
        panel.add(clearHeights);
        panel.add(Box.createVerticalStrut(8));
        JLabel note = new JLabel("Confirmation required");
        note.setToolTipText(
                "Clears only selected chunks and checked layers after confirmation");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(note);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createButtonBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        status.setBorder(new EmptyBorder(0, 2, 0, 4));
        panel.add(status, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createActionButtons() {
        JPanel buttons = new JPanel(new GridLayout(1, 5, 4, 0));
        JButton apply = new JButton("Apply");
        apply.setMargin(new Insets(2, 2, 2, 2));
        apply.setToolTipText("Apply only to the map currently shown in the map viewport");
        apply.addActionListener(e -> applySelectedOperation(false));
        JButton applyAll = new JButton("Apply All");
        applyAll.setMargin(new Insets(2, 2, 2, 2));
        applyAll.setToolTipText("Apply to every selected matrix chunk");
        applyAll.addActionListener(e -> applySelectedOperation(true));
        JButton undo = new JButton("Undo");
        undo.setMargin(new Insets(2, 2, 2, 2));
        undo.setToolTipText("Undo the most recent map edit");
        undo.addActionListener(e -> undoLastEdit());
        JButton save = new JButton("Save");
        save.setMargin(new Insets(2, 2, 2, 2));
        save.setToolTipText("Save the complete PDSMS map project");
        save.addActionListener(e -> owner.saveMapProjectFromGlobalEditor());
        JButton close = new JButton("Close");
        close.setMargin(new Insets(2, 2, 2, 2));
        close.setToolTipText("Close Global Map Editor; already applied edits remain undoable");
        close.addActionListener(e -> dispose());
        buttons.add(apply);
        buttons.add(applyAll);
        buttons.add(undo);
        buttons.add(save);
        buttons.add(close);
        getRootPane().setDefaultButton(apply);
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
        copySourceLayer.addActionListener(e -> refreshPreview());
        copyTargetLayer.addActionListener(e -> refreshPreview());
        copyTiles.addActionListener(e -> refreshPreview());
        copyHeights.addActionListener(e -> refreshPreview());
        heightAmount.addChangeListener(e -> refreshPreview());
        occupiedOnly.addActionListener(e -> refreshPreview());
        clearTiles.addActionListener(e -> refreshPreview());
        clearHeights.addActionListener(e -> refreshPreview());
        operations.addChangeListener(e -> {
            updateOperationHint();
            refreshPreview();
        });
        updateOperationHint();
    }

    private void tileModeChanged() {
        boolean deleting = deleteMode.isSelected();
        replacementBrowser.setEnabled(!deleting);
        replacementSummary.setEnabled(!deleting);
        refreshCollisionDefaults.setEnabled(!deleting);
        if (deleting) {
            keepCollisions.setSelected(true);
        }
        excludedMatches.clear();
        updateTileSummaries();
        updateOperationHint();
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
                sourceTileIndex = clampTileIndex(sourceTileIndex);
                replacementTileIndex = clampTileIndex(replacementTileIndex);
                sourceBrowser.setReadOnlySelectedIndex(sourceTileIndex);
                replacementBrowser.setReadOnlySelectedIndex(replacementTileIndex);
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

    private void showFindScopeMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem checked = new JMenuItem("Select chunks \u2014 checked layers");
        checked.setToolTipText("Find tile A only in the currently checked layers");
        checked.addActionListener(e -> selectChunksContainingSource(false));
        menu.add(checked);

        JMenuItem allLayers = new JMenuItem("Select chunks + matching layers \u2014 all layers");
        allLayers.setToolTipText(
                "Search every layer, highlight matching chunks, and check layers containing tile A");
        allLayers.addActionListener(e -> selectChunksContainingSource(true));
        menu.add(allLayers);
        menu.show(invoker, 0, invoker.getHeight());
    }

    private void selectChunksContainingSource(boolean scanAllLayers) {
        if (handler.getTileset().size() == 0) {
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
            for (int layer : layers) {
                if (layerContains(entry.getValue().getGrid(), layer, sourceTileIndex)) {
                    matchingLayers[layer] = true;
                    mapMatched = true;
                }
            }
            if (mapMatched) {
                matchingMaps.add(new Point(entry.getKey()));
            }
        }

        if (matchingMaps.isEmpty()) {
            showValidation("Tile A was not found in the "
                    + (scanAllLayers ? "matrix." : "currently checked layers."));
            return;
        }
        if (scanAllLayers) {
            for (int i = 0; i < layerChecks.length; i++) {
                layerChecks[i].setSelected(matchingLayers[i]);
            }
        }
        matrixSelection.setSelectedMaps(matchingMaps);
        status.setText("Found tile A in " + matchingMaps.size() + " chunk(s)"
                + (scanAllLayers ? " across " + countTrue(matchingLayers)
                + " matching layer(s)." : " within the checked layers."));
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
        sourceTileIndex = clampTileIndex(index);
        excludedMatches.clear();
        updateTileSummaries();
        refreshSourceFilter();
        refreshPreview();
    }

    private void setReplacementTileIndex(int index) {
        replacementTileIndex = clampTileIndex(index);
        excludedMatches.clear();
        updateTileSummaries();
        refreshPreview();
    }

    private int clampTileIndex(int index) {
        return Math.max(0, Math.min(index, Math.max(0, handler.getTileset().size() - 1)));
    }

    private void updateTileSummaries() {
        sourceSummary.setText(tileSummary(sourceTileIndex));
        replacementSummary.setText(deleteMode.isSelected()
                ? "<html><center><b>Not used</b><br>Delete removes tile A</center></html>"
                : tileSummary(replacementTileIndex));
        replaceSummary.setText(deleteMode.isSelected()
                ? "<html><b>A: " + tileShortName(sourceTileIndex)
                + "</b> &nbsp;\u2192&nbsp; <b>Empty</b></html>"
                : "<html><b>A: " + tileShortName(sourceTileIndex)
                + "</b> &nbsp;\u2192&nbsp; <b>B: " + tileShortName(replacementTileIndex)
                + "</b></html>");
        headerReplaceSummary.setText(replaceSummary.getText());
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

    private String tilePickerDescription(int index) {
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
        if (!filterSourceToSelection.isSelected()) {
            sourceBrowser.setReadOnlyVisibleIndices(null);
            sourceFilterStatus.setText(handler.getTileset().size() + " tiles");
            return;
        }
        Set<Point> maps = matrixSelection.getSelectedMaps();
        int[] layers = selectedLayers();
        if (layers.length == 0) {
            layers = allLayers();
        }
        LinkedHashSet<Integer> visible = new LinkedHashSet<>();
        for (Point point : maps) {
            MapData mapData = handler.getMapMatrix().getMap(point);
            if (mapData == null) {
                continue;
            }
            for (int layer : layers) {
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
        //Keep the current source visible so filtering never strands the user.
        visible.add(sourceTileIndex);
        sourceBrowser.setReadOnlyVisibleIndices(visible);
        sourceFilterStatus.setText(visible.size() + " / "
                + handler.getTileset().size() + " tiles");
    }

    private void matrixSelectionChanged(Point lastTouched) {
        refreshMapChoices(lastTouched);
        refreshSourceFilter();
        refreshLayerPreviews();
        refreshPreview();
    }

    private void previewMatrixChunk(Point point) {
        if (point == null || !handler.getMapMatrix().getMatrix().containsKey(point)) {
            return;
        }
        previewMap = new Point(point);
        previewOnlyMap = matrixSelection.getSelectedMaps().contains(point)
                ? null : new Point(point);
        refreshMapChoices(null);
        matrixSelection.setPreviewMap(previewMap);
        refreshLayerPreviews();
        refreshPreview();
        status.setText("Previewing " + handler.getMapMatrix().getMapName(point)
                + " without changing chunk selection.");
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
        refreshLayerPreviews();
    }

    private void movePreviewChoice(int delta) {
        int count = previewMapChoice.getItemCount();
        if (count == 0) {
            return;
        }
        int index = previewMapChoice.getSelectedIndex();
        previewMapChoice.setSelectedIndex(Math.floorMod(index + delta, count));
    }

    private void refreshPreview() {
        beforePreview.repaint();
        afterPreview.repaint();
        updatePreviewStats();
        resetCellSelection.setEnabled(!excludedMatches.isEmpty());
    }

    private void updatePreviewStats() {
        int tab = operations.getSelectedIndex();
        int chunks = matrixSelection.getSelectedMaps().size();
        switch (tab) {
            case 0:
                int[] counts = countReplaceMatches(matrixSelection.getSelectedMaps());
                previewStats.setText("Matches " + counts[0] + "  \u00b7  Included "
                        + counts[1] + "  \u00b7  Excluded " + (counts[0] - counts[1])
                        + "  \u00b7  Chunks " + chunks);
                break;
            case 1:
                previewStats.setText("Copy L" + (copySourceLayer.getSelectedIndex() + 1)
                        + " \u2192 L" + (copyTargetLayer.getSelectedIndex() + 1)
                        + "  \u00b7  Chunks " + chunks);
                break;
            case 2:
                previewStats.setText("Height cells highlighted in Preview");
                break;
            case 3:
                previewStats.setText("Clear preview  \u00b7  Chunks " + chunks);
                break;
            default:
                previewStats.setText(" ");
        }
    }

    private int[] countReplaceMatches(Set<Point> maps) {
        int matches = 0;
        int included = 0;
        int[] layers = selectedLayers();
        for (Point point : maps) {
            MapData data = handler.getMapMatrix().getMap(point);
            if (data == null) {
                continue;
            }
            for (int layer : layers) {
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

    private boolean isExcluded(Point point, int layer, int x, int y) {
        return excludedMatches.contains(
                new GlobalMapOperations.TileCell(point, layer, x, y));
    }

    private ArrayList<GlobalMapOperations.TileCell> matchingCellsAt(
            Point point, int x, int y) {
        ArrayList<GlobalMapOperations.TileCell> matches = new ArrayList<>();
        MapData data = handler.getMapMatrix().getMap(point);
        if (data == null) {
            return matches;
        }
        for (int layer : selectedLayers()) {
            if (visiblePreviewLayer >= 0 && layer != visiblePreviewLayer) {
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
                if (!layerChecks[layer].isSelected() || isExcluded(map, layer, x, y)) {
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
                if (copyTiles.isSelected() && layer == copyTargetLayer.getSelectedIndex()) {
                    return data.getGrid().tileLayers[copySourceLayer.getSelectedIndex()][x][y];
                }
                return current;
            case 3:
                if (clearTiles.isSelected() && layerChecks[layer].isSelected()) {
                    return -1;
                }
                return current;
            default:
                return current;
        }
    }

    private int previewHeightIndex(MapData data, int layer,
                                   int x, int y, boolean after) {
        int current = data.getGrid().heightLayers[layer][x][y];
        if (!after) {
            return current;
        }
        switch (operations.getSelectedIndex()) {
            case 1:
                if (copyHeights.isSelected()
                        && layer == copyTargetLayer.getSelectedIndex()) {
                    return data.getGrid().heightLayers[
                            copySourceLayer.getSelectedIndex()][x][y];
                }
                return current;
            case 2:
                if (!layerChecks[layer].isSelected()
                        || (occupiedOnly.isSelected()
                        && data.getGrid().tileLayers[layer][x][y] < 0)) {
                    return current;
                }
                int amount = (Integer) heightAmount.getValue();
                return Math.max(MapEditorHandler.minHeight,
                        Math.min(MapEditorHandler.maxHeight, current + amount));
            case 3:
                return clearHeights.isSelected() && layerChecks[layer].isSelected()
                        ? 0 : current;
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
            for (int x = 0; x < MapGrid.cols; x++) {
                for (int y = 0; y < MapGrid.rows; y++) {
                    int tileIndex = previewTileIndex(data, map, layer, x, y, after);
                    if (tileIndex < 0 || tileIndex >= handler.getTileset().size()) {
                        continue;
                    }
                    draws.add(new PreviewTile(layer, x, y,
                            previewHeightIndex(data, layer, x, y, after), tileIndex));
                }
            }
        }
        draws.sort(Comparator.comparingInt((PreviewTile draw) -> draw.height)
                .thenComparing((left, right) -> Integer.compare(right.y, left.y))
                .thenComparingInt(draw -> draw.layer)
                .thenComparingInt(draw -> draw.x));
        return draws;
    }

    private boolean isHeightAffected(MapData data, int layer, int x, int y) {
        if (!layerChecks[layer].isSelected()) {
            return false;
        }
        int tab = operations.getSelectedIndex();
        if (tab == 2) {
            if (occupiedOnly.isSelected() && data.getGrid().tileLayers[layer][x][y] < 0) {
                return false;
            }
            int oldHeight = data.getGrid().heightLayers[layer][x][y];
            int amount = (Integer) heightAmount.getValue();
            int newHeight = Math.max(MapEditorHandler.minHeight,
                    Math.min(MapEditorHandler.maxHeight, oldHeight + amount));
            return oldHeight != newHeight;
        }
        return tab == 3 && clearHeights.isSelected()
                && data.getGrid().heightLayers[layer][x][y] != 0;
    }

    private void updateOperationHint() {
        switch (operations.getSelectedIndex()) {
            case 0:
                operationHint.setText(deleteMode.isSelected() ? "Delete A"
                        : swapTiles.isSelected() ? "Swap A \u2194 B" : "Replace A \u2192 B");
                operationHint.setToolTipText("Apply changes the shown selected chunk; "
                        + "Apply All changes every selected chunk. Heights stay unchanged.");
                break;
            case 1:
                operationHint.setText("Copy layer");
                operationHint.setToolTipText("Copy tile and/or height layouts from one layer "
                        + "to another in each selected chunk");
                break;
            case 2:
                operationHint.setText("Adjust height");
                operationHint.setToolTipText("Raise or lower checked layers while keeping "
                        + "values inside the supported range");
                break;
            case 3:
                operationHint.setText("Clear layers");
                operationHint.setToolTipText("Clear tiles and/or reset heights in checked layers");
                break;
            default:
                operationHint.setText(" ");
                operationHint.setToolTipText(null);
        }
    }

    private void showOperationHelp() {
        String title;
        String body;
        switch (operations.getSelectedIndex()) {
            case 0:
                title = "Tile Operation";
                body = "<b>Useful for:</b> fixing a shared bad tile, changing a path or terrain "
                        + "variant, exchanging two variants, or deleting one tile from the scope."
                        + "<br><br><b>Scope:</b> chosen chunks, checked layers, and any individual "
                        + "matches left included in Map Preview."
                        + "<br><br><b>Safeguards:</b> heights never change. Existing collisions "
                        + "can be kept, or smart defaults can be recalculated for Replace/Swap. "
                        + "Delete leaves collision cells unchanged.";
                break;
            case 1:
                title = "Copy Layer";
                body = "<b>Useful for:</b> moving a repeated decoration, ground, or height layout "
                        + "to a consistent layer across many chunks."
                        + "<br><br><b>Scope:</b> the selected source and target layers inside every "
                        + "chosen chunk. Tile and height layouts can be copied independently."
                        + "<br><br><b>Safeguard:</b> only the target layer is overwritten and the "
                        + "whole operation is one undo step.";
                break;
            case 2:
                title = "Adjust Heights";
                body = "<b>Useful for:</b> raising or lowering terrain after importing or copying "
                        + "sections, or correcting a consistent vertical offset."
                        + "<br><br><b>Scope:</b> chosen chunks and checked layers. The occupied-only "
                        + "option prevents empty cells from being altered."
                        + "<br><br><b>Safeguard:</b> results are clamped to PDSMS's supported range.";
                break;
            case 3:
                title = "Clear";
                body = "<b>Useful for:</b> removing accidental content from a layer, preparing "
                        + "layers for a new layout, or resetting unwanted height data."
                        + "<br><br><b>Scope:</b> chosen chunks and checked layers; tiles and heights "
                        + "are controlled separately."
                        + "<br><br><b>Safeguards:</b> confirmation is required and Undo restores "
                        + "the complete multi-chunk change.";
                break;
            default:
                return;
        }
        JOptionPane.showMessageDialog(this, "<html><div style='width:430px'>"
                        + body + "</div></html>",
                title + " \u2014 help", JOptionPane.INFORMATION_MESSAGE);
    }

    private void applySelectedOperation(boolean allSelectedMaps) {
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
                if (!deleteMode.isSelected()
                        && sourceTileIndex == replacementTileIndex) {
                    showValidation("Choose two different tiles.");
                    return;
                }
                if (countReplaceMatches(maps)[1] == 0) {
                    showValidation("No included tile matches remain in the chosen scope.");
                    return;
                }
                changed = deleteMode.isSelected()
                        ? GlobalMapOperations.deleteTiles(
                        handler.getMapMatrix().getMatrix(), maps, layers,
                        sourceTileIndex, excludedMatches)
                        : GlobalMapOperations.replaceTiles(
                        handler.getMapMatrix().getMatrix(), maps, layers,
                        sourceTileIndex, replacementTileIndex, swapTiles.isSelected(),
                        excludedMatches);
                if (changed > 0 && !deleteMode.isSelected()
                        && refreshCollisionDefaults.isSelected()) {
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
        excludedMatches.clear();
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
        refreshLayerPreviews();
        matrixSelection.updateBounds();
        matrixSelection.revalidate();
        matrixSelection.repaint();
        refreshMapChoices(null);
        refreshSourceFilter();
        refreshPreview();
    }

    private void refreshLayerPreviews() {
        for (LayerScopePreview preview : layerPreviews) {
            if (preview != null) {
                preview.repaint();
            }
        }
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

    private static int[] allLayers() {
        int[] layers = new int[MapGrid.numLayers];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = i;
        }
        return layers;
    }

    private void setAllLayers(boolean selected) {
        for (JCheckBox check : layerChecks) {
            check.setSelected(selected);
        }
        refreshPreview();
    }

    private void setVisiblePreviewLayer(int layer) {
        visiblePreviewLayer = layer >= 0 && layer < MapGrid.numLayers ? layer : -1;
        refreshLayerPreviews();
        refreshPreview();
        status.setText(visiblePreviewLayer < 0
                ? "Viewing all layers in map height order."
                : "Viewing Layer " + (visiblePreviewLayer + 1)
                + " only. Checked layers still define the edit scope.");
    }

    private void selectCurrentLayer() {
        setAllLayers(false);
        layerChecks[handler.getActiveLayerIndex()].setSelected(true);
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
        button.setMargin(new Insets(2, 5, 2, 5));
        button.setToolTipText(tooltip);
        return button;
    }

    private static final class PreviewTile {
        private final int layer;
        private final int x;
        private final int y;
        private final int height;
        private final int tileIndex;

        private PreviewTile(int layer, int x, int y, int height, int tileIndex) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.height = height;
            this.tileIndex = tileIndex;
        }
    }

    private final class LayerScopePreview extends JLayeredPane {

        private final int layer;

        private LayerScopePreview(int layer) {
            this.layer = layer;
            setPreferredSize(new Dimension(74, 66));
            setMinimumSize(new Dimension(66, 58));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            setOpaque(true);
            setToolTipText("Layer " + (layer + 1)
                    + " \u2014 click card to view; use corner checkbox for edit scope");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            layerChecks[layer].setOpaque(false);
            layerChecks[layer].setToolTipText(
                    "Include Layer " + (layer + 1) + " in the operation");
            add(layerChecks[layer], JLayeredPane.PALETTE_LAYER);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        setVisiblePreviewLayer(LayerScopePreview.this.layer);
                    }
                }
            });
        }

        @Override
        public void doLayout() {
            layerChecks[layer].setBounds(3, 3, 22, 22);
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
                if (!selected) {
                    g2.setColor(new Color(0, 0, 0, 115));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                } else {
                    g2.setColor(new Color(0, 220, 255, 42));
                    g2.fillRect(inset, inset,
                            getWidth() - inset * 2, getHeight() - inset * 2);
                }
                g2.setStroke(new BasicStroke(selected ? 3 : 1));
                g2.setColor(selected ? new Color(0, 225, 255) : Color.GRAY);
                g2.drawRect(inset, inset,
                        getWidth() - inset * 2 - 1, getHeight() - inset * 2 - 1);
                if (visiblePreviewLayer == layer) {
                    g2.setStroke(new BasicStroke(3));
                    g2.setColor(new Color(255, 205, 55));
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
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
                        graphics.drawImage(tile, draw.x * 2,
                                (MapGrid.rows - draw.y - 1) * 2
                                        - (tile.getHeight() - 2), null);
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
        private final boolean interactive;
        private Rectangle mapBounds = new Rectangle();

        private MapPreviewCanvas(boolean after, boolean interactive) {
            this.after = after;
            this.interactive = interactive;
            setPreferredSize(new Dimension(440, 440));
            setMinimumSize(new Dimension(260, 260));
            setOpaque(true);
            setToolTipText("");
            if (interactive) {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        if (SwingUtilities.isRightMouseButton(event)
                                || (SwingUtilities.isLeftMouseButton(event)
                                && event.isControlDown())) {
                            Point cell = cellAt(event.getPoint());
                            if (cell != null) {
                                togglePreviewCell(cell.x, cell.y);
                            }
                            return;
                        }
                        if (!SwingUtilities.isLeftMouseButton(event)) {
                            return;
                        }
                        int tileIndex = tileAt(event.getPoint());
                        if (tileIndex < 0) {
                            status.setText("No visible tile was found at that point.");
                            return;
                        }
                        if (event.isShiftDown()) {
                            setReplacementTileIndex(tileIndex);
                            replacementBrowser.setReadOnlySelectedIndex(tileIndex);
                            scrollTileBrowserToSelection(replacementBrowser);
                            status.setText("Picked replacement tile B: "
                                    + tilePickerDescription(tileIndex));
                        } else {
                            setSourceTileIndex(tileIndex);
                            sourceBrowser.setReadOnlySelectedIndex(tileIndex);
                            scrollTileBrowserToSelection(sourceBrowser);
                            status.setText("Picked find tile A: "
                                    + tilePickerDescription(tileIndex));
                        }
                    }
                });
            }
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
                drawAffectedCells(g2, data);
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
                        data, map, afterState, visiblePreviewLayer)) {
                    Tile tile = handler.getTileset().get(draw.tileIndex);
                    BufferedImage thumbnail = tile.getThumbnail();
                    if (thumbnail != null) {
                        graphics.drawImage(thumbnail, draw.x * tileSize,
                                (MapGrid.rows - draw.y - 1
                                        - (tile.getHeight() - 1)) * tileSize, null);
                    }
                }
            } finally {
                graphics.dispose();
            }
            return image;
        }

        private void drawAffectedCells(Graphics2D g2, MapData data) {
            if (operations.getSelectedIndex() == 0) {
                if (after) {
                    return;
                }
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        ArrayList<GlobalMapOperations.TileCell> matches =
                                matchingCellsAt(previewMap, x, y);
                        if (matches.isEmpty()) {
                            continue;
                        }
                        boolean allExcluded = excludedMatches.containsAll(matches);
                        if (allExcluded || showMatchHighlights.isSelected()) {
                            drawCellState(g2, x, y, allExcluded);
                        }
                    }
                }
            } else if (after && (operations.getSelectedIndex() == 2
                    || (operations.getSelectedIndex() == 3 && clearHeights.isSelected()))) {
                for (int x = 0; x < MapGrid.cols; x++) {
                    for (int y = 0; y < MapGrid.rows; y++) {
                        boolean affected = false;
                        int firstLayer =
                                visiblePreviewLayer >= 0 ? visiblePreviewLayer : 0;
                        int lastLayer = visiblePreviewLayer >= 0
                                ? visiblePreviewLayer : MapGrid.numLayers - 1;
                        for (int layer = firstLayer;
                             layer <= lastLayer && !affected; layer++) {
                            affected = isHeightAffected(data, layer, x, y);
                        }
                        if (affected) {
                            drawCellState(g2, x, y, false);
                        }
                    }
                }
            }
        }

        private void drawCellState(Graphics2D g2, int x, int y, boolean excluded) {
            Rectangle cell = cellBounds(x, y);
            if (excluded) {
                g2.setColor(new Color(220, 60, 60, 105));
                g2.fillRect(cell.x, cell.y, cell.width, cell.height);
            }
            g2.setColor(excluded ? new Color(255, 90, 90)
                    : new Color(0, 235, 255, 175));
            g2.setStroke(new BasicStroke(excluded
                    ? Math.max(1.5f, mapBounds.width / 320f) : 1f));
            g2.drawRect(cell.x, cell.y,
                    Math.max(0, cell.width - 1), Math.max(0, cell.height - 1));
            if (excluded) {
                g2.drawLine(cell.x + 2, cell.y + 2,
                        cell.x + cell.width - 3, cell.y + cell.height - 3);
                g2.drawLine(cell.x + cell.width - 3, cell.y + 2,
                        cell.x + 2, cell.y + cell.height - 3);
            }
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
            if (!mapBounds.contains(pixel) || previewMap == null) {
                return -1;
            }
            MapData data = handler.getMapMatrix().getMap(previewMap);
            if (data == null) {
                return -1;
            }
            int nativeSize = MapGrid.cols * 16;
            int nativeX = (pixel.x - mapBounds.x) * nativeSize / mapBounds.width;
            int nativeY = (pixel.y - mapBounds.y) * nativeSize / mapBounds.height;
            ArrayList<PreviewTile> draws =
                    previewTiles(data, previewMap, false, visiblePreviewLayer);
            for (int i = draws.size() - 1; i >= 0; i--) {
                PreviewTile draw = draws.get(i);
                Tile tile = handler.getTileset().get(draw.tileIndex);
                BufferedImage image = tile.getThumbnail();
                if (image == null) {
                    continue;
                }
                int drawX = draw.x * 16;
                int drawY = (MapGrid.rows - draw.y - 1
                        - (tile.getHeight() - 1)) * 16;
                int localX = nativeX - drawX;
                int localY = nativeY - drawY;
                if (localX >= 0 && localY >= 0
                        && localX < image.getWidth() && localY < image.getHeight()
                        && (image.getRGB(localX, localY) >>> 24) > 16) {
                    return draw.tileIndex;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Point cell = cellAt(event.getPoint());
            if (cell == null || previewMap == null) {
                return null;
            }
            int tileIndex = tileAt(event.getPoint());
            ArrayList<GlobalMapOperations.TileCell> matches =
                    matchingCellsAt(previewMap, cell.x, cell.y);
            if (matches.isEmpty()) {
                return (tileIndex < 0 ? "Empty pixel" : tilePickerDescription(tileIndex))
                        + " \u2014 click for A, Shift-click for B";
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
