package editor.tileseteditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import tileset.PaletteFolder;
import tileset.PaletteFolderBundleIO;
import tileset.Tile;
import tileset.Tileset;
import tileset.TilesetRenderer;

/** Folder-aware two-pane tile selection for portable palette-folder imports. */
public final class ImportPaletteFolderDialog extends JDialog {

    private final Tileset preview;
    private final Tileset target;
    private final Map<Integer, Integer> duplicateIndices;
    private final LinkedHashSet<Integer> included = new LinkedHashSet<>();
    private final DefaultListModel<TileEntry> availableModel = new DefaultListModel<>();
    private final DefaultListModel<TileEntry> includedModel = new DefaultListModel<>();
    private final JList<TileEntry> availableList = new JList<>(availableModel);
    private final JList<TileEntry> includedList = new JList<>(includedModel);
    private final JTree folderTree;
    private final JLabel folderStatus = new JLabel(" ");
    private final JLabel tally = new JLabel("0 selected");
    private String currentFolder;
    private boolean approved;

    public ImportPaletteFolderDialog(Window owner, Tileset preview, Tileset target) {
        super(owner, "Choose tiles from imported folders", ModalityType.APPLICATION_MODAL);
        this.preview = preview;
        this.target = target;
        this.duplicateIndices = PaletteFolderBundleIO.findDuplicateIndices(
                preview, target, null);
        renderPreviewTiles();
        folderTree = createFolderTree();
        buildUi();
        selectFirstFolder();
        setMinimumSize(new Dimension(860, 560));
        setSize(new Dimension(980, 660));
        setLocationRelativeTo(owner);
    }

    public boolean isApproved() {
        return approved;
    }

    public Set<Integer> getSelectedIndices() {
        return new LinkedHashSet<>(included);
    }

    /** Returns null when the user cancels the conflict step. */
    public static Map<Integer, PaletteFolderBundleIO.DuplicateChoice> resolveDuplicates(
            Window owner, Tileset incoming, Tileset existing,
            Map<Integer, Integer> matches) {
        if (matches.isEmpty()) return Collections.emptyMap();
        DuplicateTileResolutionDialog dialog = new DuplicateTileResolutionDialog(
                owner, incoming, existing, matches);
        dialog.setVisible(true);
        return dialog.isApproved() ? dialog.getChoices() : null;
    }

    private void renderPreviewTiles() {
        TilesetRenderer renderer = new TilesetRenderer(preview);
        try {
            renderer.renderTiles();
        } catch (NullPointerException ex) {
            //Some very old bundles have no renderable geometry.  The list
            //still remains usable through its names and tile IDs.
        } finally {
            renderer.destroy();
        }
    }

    private void buildUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(root);

        JLabel instructions = new JLabel(
                "Open each subfolder, then use Ctrl/Shift selection or double-click to include tiles.");
        root.add(instructions, BorderLayout.NORTH);

        folderTree.setRootVisible(false);
        folderTree.setShowsRootHandles(true);
        JScrollPane treeScroll = new JScrollPane(folderTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Imported folders"));
        treeScroll.setMinimumSize(new Dimension(190, 300));
        treeScroll.setPreferredSize(new Dimension(220, 500));

        configureTileList(availableList);
        configureTileList(includedList);
        availableList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) includeSelected();
            }
        });
        includedList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) excludeSelected();
            }
        });

        JPanel availablePanel = listPanel("Not included (current folder)",
                availableList, folderStatus);
        JPanel includedPanel = listPanel("Confirmed for import", includedList, tally);

        JPanel transfer = new JPanel();
        transfer.setLayout(new BoxLayout(transfer, BoxLayout.Y_AXIS));
        transfer.add(Box.createVerticalGlue());
        JButton includeButton = new JButton("Include  ▶");
        includeButton.setToolTipText("Include the selected tiles (Ctrl/Shift supported)");
        includeButton.addActionListener(event -> includeSelected());
        JButton excludeButton = new JButton("◀  Exclude");
        excludeButton.addActionListener(event -> excludeSelected());
        transfer.add(includeButton);
        transfer.add(Box.createVerticalStrut(8));
        transfer.add(excludeButton);
        transfer.add(Box.createVerticalGlue());

        JPanel rightList = new JPanel(new BorderLayout(8, 0));
        rightList.add(transfer, BorderLayout.WEST);
        rightList.add(includedPanel, BorderLayout.CENTER);
        JSplitPane listSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                availablePanel, rightList);
        listSplit.setResizeWeight(0.5);
        listSplit.setDividerLocation(330);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, listSplit);
        split.setResizeWeight(0.22);
        split.setDividerLocation(220);
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JPanel bulk = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton includeFolder = new JButton("Include current folder");
        includeFolder.addActionListener(event -> includeCurrentFolder());
        JButton includeAll = new JButton("Include all folders");
        includeAll.addActionListener(event -> {
            for (int i = 0; i < preview.size(); i++) included.add(i);
            refreshLists();
        });
        JButton clear = new JButton("Clear included");
        clear.addActionListener(event -> {
            included.clear();
            refreshLists();
        });
        bulk.add(includeFolder);
        bulk.add(includeAll);
        bulk.add(clear);
        footer.add(bulk, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        JButton confirm = new JButton("Confirm tiles");
        confirm.addActionListener(event -> confirm());
        actions.add(cancel);
        actions.add(confirm);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(confirm);
    }

    private JPanel listPanel(String title, JList<TileEntry> list, JLabel status) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        panel.add(scroll, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(3, 4, 2, 4));
        panel.add(status, BorderLayout.SOUTH);
        return panel;
    }

    private void configureTileList(JList<TileEntry> list) {
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new TileEntryRenderer());
        list.setFixedCellHeight(58);
    }

    private JTree createFolderTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Imported folders");
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
        List<PaletteFolder> folders = new ArrayList<>(preview.getPaletteFolders());
        folders.removeIf(folder -> folder.getPath().isEmpty());
        folders.sort(Comparator.comparingInt(folder -> depth(folder.getPath())));
        for (PaletteFolder folder : folders) {
            FolderEntry entry = new FolderEntry(folder.getPath());
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
            nodes.put(folder.getPath(), node);
            String parentPath = Tileset.getParentFolderPath(folder.getPath());
            DefaultMutableTreeNode parent = nodes.get(parentPath);
            (parent == null ? root : parent).add(node);
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.addTreeSelectionListener(event -> {
            DefaultMutableTreeNode selectedNode =
                    (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selectedNode == null) return;
            Object value = selectedNode.getUserObject();
            if (value instanceof FolderEntry) {
                currentFolder = ((FolderEntry) value).path;
                refreshLists();
            }
        });
        return tree;
    }

    private void selectFirstFolder() {
        DefaultMutableTreeNode root =
                (DefaultMutableTreeNode) folderTree.getModel().getRoot();
        if (root.getChildCount() > 0) {
            DefaultMutableTreeNode first = (DefaultMutableTreeNode) root.getChildAt(0);
            folderTree.setSelectionPath(new TreePath(first.getPath()));
            for (int i = 0; i < folderTree.getRowCount(); i++) folderTree.expandRow(i);
        } else {
            currentFolder = PaletteFolder.UNSORTED;
            refreshLists();
        }
    }

    private void includeSelected() {
        for (TileEntry entry : availableList.getSelectedValuesList()) {
            included.add(entry.index);
        }
        refreshLists();
    }

    private void excludeSelected() {
        for (TileEntry entry : includedList.getSelectedValuesList()) {
            included.remove(entry.index);
        }
        refreshLists();
    }

    private void includeCurrentFolder() {
        for (int index : indicesInCurrentFolder()) included.add(index);
        refreshLists();
    }

    private void refreshLists() {
        availableModel.clear();
        List<Integer> inFolder = indicesInCurrentFolder();
        for (int index : inFolder) {
            if (!included.contains(index)) availableModel.addElement(new TileEntry(index));
        }
        includedModel.clear();
        ArrayList<Integer> sorted = new ArrayList<>(included);
        Collections.sort(sorted);
        for (int index : sorted) includedModel.addElement(new TileEntry(index));

        int duplicates = 0;
        for (int index : included) {
            if (duplicateIndices.containsKey(index)) duplicates++;
        }
        int fresh = included.size() - duplicates;
        tally.setText(included.size() + " selected  •  " + fresh
                + " new  •  " + duplicates + " overwrite candidates");
        folderStatus.setText((currentFolder == null ? "" : currentFolder) + "  •  "
                + availableModel.size() + " not included / " + inFolder.size() + " total");
    }

    private List<Integer> indicesInCurrentFolder() {
        ArrayList<Integer> result = new ArrayList<>();
        for (int i = 0; i < preview.size(); i++) {
            Tile tile = preview.get(i);
            if (currentFolder == null || currentFolder.isEmpty()
                    || tile.isInPaletteFolder(currentFolder)) {
                result.add(i);
            }
        }
        return result;
    }

    private void confirm() {
        if (included.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Include at least one tile first.",
                    "No tiles selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        approved = true;
        dispose();
    }

    private static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
    }

    private final class TileEntry {
        final int index;
        TileEntry(int index) { this.index = index; }
        Tile tile() { return preview.get(index); }
    }

    private final class TileEntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focused);
            TileEntry entry = (TileEntry) value;
            Tile tile = entry.tile();
            String name = tile.getPaletteName().isEmpty()
                    ? tile.getObjFilename() : tile.getPaletteName();
            int existing = duplicateIndices.getOrDefault(entry.index, -1);
            label.setText("<html><b>Tile " + entry.index + "</b>  " + escape(name)
                    + "<br><font color='" + (existing >= 0 ? "#e69f35" : "#63b36f") + "'>"
                    + (existing >= 0 ? "Matches existing tile " + existing : "New tile")
                    + "</font></html>");
            label.setIcon(tileIcon(tile, 48));
            label.setIconTextGap(8);
            return label;
        }
    }

    private static final class FolderEntry {
        final String path;
        FolderEntry(String path) { this.path = path; }
        @Override public String toString() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    static ImageIcon tileIcon(Tile tile, int maximumSize) {
        BufferedImage thumbnail = tile.getPaletteThumbnail() != null
                ? tile.getPaletteThumbnail() : tile.getThumbnail();
        if (thumbnail == null) return null;
        double scale = Math.min(1.0, (double) maximumSize
                / Math.max(thumbnail.getWidth(), thumbnail.getHeight()));
        int width = Math.max(1, (int) Math.round(thumbnail.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(thumbnail.getHeight() * scale));
        return new ImageIcon(thumbnail.getScaledInstance(width, height, Image.SCALE_FAST));
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

/** Visual, per-conflict keep/overwrite decision shown after tile confirmation. */
final class DuplicateTileResolutionDialog extends JDialog {

    private final Tileset incoming;
    private final Tileset existing;
    private final Map<Integer, Integer> matches;
    private final LinkedHashMap<Integer, PaletteFolderBundleIO.DuplicateChoice> choices
            = new LinkedHashMap<>();
    private final DefaultListModel<Integer> conflictModel = new DefaultListModel<>();
    private final JList<Integer> conflicts = new JList<>(conflictModel);
    private final JLabel originalImage = imageLabel("Original in this tileset");
    private final JLabel incomingImage = imageLabel("Incoming from folder");
    private final JLabel originalDetails = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel incomingDetails = new JLabel(" ", SwingConstants.CENTER);
    private final javax.swing.JRadioButton keep = new javax.swing.JRadioButton(
            "Keep original (preserve your edits)");
    private final javax.swing.JRadioButton overwrite = new javax.swing.JRadioButton(
            "Overwrite with incoming tile");
    private boolean approved;

    DuplicateTileResolutionDialog(Window owner, Tileset incoming, Tileset existing,
            Map<Integer, Integer> matches) {
        super(owner, "Resolve existing tiles", ModalityType.APPLICATION_MODAL);
        this.incoming = incoming;
        this.existing = existing;
        this.matches = matches;
        for (int source : matches.keySet()) {
            conflictModel.addElement(source);
            choices.put(source, PaletteFolderBundleIO.DuplicateChoice.KEEP_EXISTING);
        }
        buildUi();
        setMinimumSize(new Dimension(760, 500));
        setSize(new Dimension(900, 580));
        setLocationRelativeTo(owner);
        conflicts.setSelectedIndex(0);
    }

    boolean isApproved() { return approved; }

    Map<Integer, PaletteFolderBundleIO.DuplicateChoice> getChoices() {
        return new LinkedHashMap<>(choices);
    }

    private void buildUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(root);
        root.add(new JLabel(matches.size()
                + " selected tile(s) already exist. Compare each one before importing."),
                BorderLayout.NORTH);

        conflicts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conflicts.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                int source = (Integer) value;
                Tile tile = incoming.get(source);
                String name = tile.getPaletteName().isEmpty()
                        ? tile.getObjFilename() : tile.getPaletteName();
                label.setText("Tile " + source + "  " + name + "  •  "
                        + choiceText(choices.get(source)));
                label.setIcon(ImportPaletteFolderDialog.tileIcon(tile, 36));
                return label;
            }
        });
        conflicts.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedConflict();
        });
        JScrollPane conflictScroll = new JScrollPane(conflicts);
        conflictScroll.setBorder(BorderFactory.createTitledBorder("Existing matches"));
        conflictScroll.setPreferredSize(new Dimension(300, 400));

        JPanel comparison = new JPanel(new BorderLayout(8, 8));
        comparison.setBorder(BorderFactory.createTitledBorder("Visual comparison"));
        JPanel images = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
        images.add(comparisonCard(originalImage, originalDetails));
        images.add(comparisonCard(incomingImage, incomingDetails));
        comparison.add(images, BorderLayout.CENTER);

        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        group.add(keep);
        group.add(overwrite);
        keep.addActionListener(event -> setChoice(
                PaletteFolderBundleIO.DuplicateChoice.KEEP_EXISTING));
        overwrite.addActionListener(event -> setChoice(
                PaletteFolderBundleIO.DuplicateChoice.OVERWRITE));
        JPanel choicesPanel = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        choicesPanel.add(keep);
        choicesPanel.add(overwrite);
        comparison.add(choicesPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                conflictScroll, comparison);
        split.setDividerLocation(300);
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JPanel bulk = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton keepAll = new JButton("Keep all originals");
        keepAll.addActionListener(event -> setAll(
                PaletteFolderBundleIO.DuplicateChoice.KEEP_EXISTING));
        JButton overwriteAll = new JButton("Overwrite all");
        overwriteAll.addActionListener(event -> setAll(
                PaletteFolderBundleIO.DuplicateChoice.OVERWRITE));
        bulk.add(keepAll);
        bulk.add(overwriteAll);
        footer.add(bulk, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton cancel = new JButton("Cancel import");
        cancel.addActionListener(event -> dispose());
        JButton finish = new JButton("Import tiles");
        finish.addActionListener(event -> { approved = true; dispose(); });
        actions.add(cancel);
        actions.add(finish);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(finish);
    }

    private static JLabel imageLabel(String tooltip) {
        JLabel label = new JLabel("No preview", SwingConstants.CENTER);
        label.setToolTipText(tooltip);
        label.setOpaque(true);
        Color background = javax.swing.UIManager.getColor("Panel.background");
        label.setBackground(background == null ? Color.darkGray : background.darker());
        label.setPreferredSize(new Dimension(210, 240));
        return label;
    }

    private static JPanel comparisonCard(JLabel image, JLabel details) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.add(image, BorderLayout.CENTER);
        details.setBorder(new EmptyBorder(4, 2, 4, 2));
        card.add(details, BorderLayout.SOUTH);
        return card;
    }

    private void showSelectedConflict() {
        Integer sourceIndex = conflicts.getSelectedValue();
        if (sourceIndex == null) return;
        int existingIndex = matches.get(sourceIndex);
        Tile oldTile = existing.get(existingIndex);
        Tile newTile = incoming.get(sourceIndex);
        originalImage.setText(ImportPaletteFolderDialog.tileIcon(oldTile, 220) == null
                ? "No preview" : "");
        originalImage.setIcon(ImportPaletteFolderDialog.tileIcon(oldTile, 220));
        incomingImage.setText(ImportPaletteFolderDialog.tileIcon(newTile, 220) == null
                ? "No preview" : "");
        incomingImage.setIcon(ImportPaletteFolderDialog.tileIcon(newTile, 220));
        originalDetails.setText(details("Original", existingIndex, oldTile));
        incomingDetails.setText(details("Incoming", sourceIndex, newTile));
        keep.setSelected(choices.get(sourceIndex)
                == PaletteFolderBundleIO.DuplicateChoice.KEEP_EXISTING);
        overwrite.setSelected(!keep.isSelected());
    }

    private void setChoice(PaletteFolderBundleIO.DuplicateChoice choice) {
        Integer source = conflicts.getSelectedValue();
        if (source != null) {
            choices.put(source, choice);
            conflicts.repaint();
        }
    }

    private void setAll(PaletteFolderBundleIO.DuplicateChoice choice) {
        for (int source : choices.keySet()) choices.put(source, choice);
        conflicts.repaint();
        showSelectedConflict();
    }

    private static String choiceText(PaletteFolderBundleIO.DuplicateChoice choice) {
        return choice == PaletteFolderBundleIO.DuplicateChoice.OVERWRITE
                ? "Overwrite" : "Keep original";
    }

    private static String details(String heading, int index, Tile tile) {
        String name = tile.getPaletteName().isEmpty()
                ? tile.getObjFilename() : tile.getPaletteName();
        return "<html><center><b>" + heading + " tile " + index + "</b><br>"
                + ImportPaletteFolderDialog.escape(name) + "<br>"
                + tile.getWidth() + "×" + tile.getHeight() + " map cells"
                + (tile.hasCollisionDefaults() ? " • collision defaults" : "")
                + "</center></html>";
    }
}
