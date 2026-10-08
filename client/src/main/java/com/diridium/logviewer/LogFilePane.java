// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static com.diridium.logviewer.LogTooltips.escapeHtml;

import java.awt.Color;
import java.awt.Component;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import net.miginfocom.swing.MigLayout;

/**
 * The narrow list of the engine's log files on the left of the window, with what could not be
 * listed under it, then Refresh list and Download... and, under those, the download's progress
 * row. The list itself is the dialog's; this pane shows it, and says which file was clicked.
 */
final class LogFilePane extends JPanel {

    // Under the list, as the warnings it came with: on dark amber, as the note about a split line.
    private static final Color WARNING_FOREGROUND = new Color(0xB8, 0x6E, 0x00);

    private static final String DOWNLOAD_TIP = "Saves the file shown in the viewer exactly as it is on disk "
            + "(an archive stays a zip).";

    private final LogTooltips tips;
    private final List<LogFileInfo> files;
    private final JTextArea txtWarnings;
    private final FileTableModel fileModel;
    private final JTable fileTable;
    private final JButton btnRefresh;
    private final JButton btnDownload;
    private boolean suppressSelection;

    /**
     * @param files the dialog's list of files, which this pane only reads
     * @param download the download's progress row, placed under the buttons
     * @param open a file was clicked in the list
     * @param refresh Refresh list was pressed
     * @param startDownload Download... was pressed
     */
    LogFilePane(List<LogFileInfo> files, LogTooltips tips, boolean canDownload, LogDownload download,
            Consumer<LogFileInfo> open, Runnable refresh, Runnable startDownload) {
        super(new MigLayout("insets 5, fill, hidemode 3", "[grow]", "[grow][][][]"));
        this.tips = tips;
        this.files = files;

        fileModel = new FileTableModel();
        fileTable = new JTable(fileModel);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.setShowGrid(false);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(65);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(105);
        FileCellRenderer renderer = new FileCellRenderer();
        for (int i = 0; i < 3; i++) {
            fileTable.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
        tips.tip(fileTable, "The engine's log files, the active one first and then the newest archives. "
                + "Click a file to open it: the active file on its last page, an archive on its first.");
        ToolTipManager.sharedInstance().registerComponent(fileTable);
        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressSelection) {
                return;
            }
            int row = fileTable.getSelectedRow();
            if (row >= 0 && row < files.size()) {
                open.accept(files.get(row));
            }
        });
        add(new JScrollPane(fileTable), "grow, wrap");

        // A text area, so the warnings wrap in the narrow pane; it shows no HTML whatever a name holds.
        txtWarnings = new JTextArea();
        txtWarnings.setEditable(false);
        txtWarnings.setFocusable(false);
        txtWarnings.setOpaque(false);
        txtWarnings.setLineWrap(true);
        txtWarnings.setWrapStyleWord(true);
        txtWarnings.setBorder(null);
        txtWarnings.setFont(new JLabel().getFont());
        txtWarnings.setForeground(WARNING_FOREGROUND);
        txtWarnings.setVisible(false);
        // A wrapped text's height follows its width.
        txtWarnings.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(LogFilePane.this::revalidate);
            }
        });
        add(txtWarnings, "growx, wmin 0, wrap");

        btnRefresh = new JButton("Refresh list");
        tips.tip(btnRefresh, "Re-reads which log files exist and their sizes. Does not change what is open.");
        btnRefresh.addActionListener(e -> refresh.run());
        btnDownload = new JButton("Download...");
        tips.tip(btnDownload, DOWNLOAD_TIP);
        btnDownload.setEnabled(false);
        btnDownload.addActionListener(e -> startDownload.run());
        // Hidden, not just disabled, for a role that may not download: the action is not theirs.
        btnDownload.setVisible(canDownload);
        add(btnRefresh, "split 2");
        add(btnDownload, "wrap");

        add(download, "growx");
    }

    /** What the engine could not list, kept under the list until the next list says otherwise. */
    void setWarnings(List<String> warnings) {
        boolean any = warnings != null && !warnings.isEmpty();
        txtWarnings.setText(any ? "Not every log file could be listed: " + String.join("; ", warnings) : "");
        txtWarnings.setVisible(any);
        revalidate();
    }

    /** The dialog's list has changed: shows it again with nothing selected, and opens nothing. */
    void filesChanged() {
        suppressSelection = true;
        try {
            fileModel.fireTableDataChanged();
            fileTable.clearSelection();
        } finally {
            suppressSelection = false;
        }
    }

    /** Selects a file in the list without opening it; with null, or a file not listed, selects none. */
    void select(LogFileInfo file) {
        int row = files.indexOf(file);
        suppressSelection = true;
        try {
            if (row >= 0) {
                fileTable.getSelectionModel().setSelectionInterval(row, row);
            } else {
                fileTable.clearSelection();
            }
        } finally {
            suppressSelection = false;
        }
    }

    /** The list and Refresh list, which are off while the list or a page is loading. */
    void setListEnabled(boolean enabled) {
        fileTable.setEnabled(enabled);
        btnRefresh.setEnabled(enabled);
    }

    /**
     * Download... on or off; while the file shown is gone, its tooltip says so.
     *
     * @param goneTip the tooltip while the file shown is gone, or null
     */
    void setDownloadEnabled(boolean enabled, String goneTip) {
        btnDownload.setEnabled(enabled);
        tips.tip(btnDownload, goneTip != null ? goneTip : DOWNLOAD_TIP);
    }

    private final class FileTableModel extends AbstractTableModel {

        private final String[] columns = {"Name", "Size", "Modified"};

        @Override
        public int getRowCount() {
            return files.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            LogFileInfo file = files.get(row);
            switch (column) {
                case 0:
                    return file.isActive() ? file.getName() + " (active)" : file.getName();
                case 1:
                    return LogViewerFormat.bytes(file.getSize());
                default:
                    // In the engine's time zone, whatever zone this computer is in.
                    return LogViewerFormat.engineTime(file.getLastModified(), file.getTimeZoneId(), "MM-dd HH:mm:ss");
            }
        }
    }

    /**
     * Greys out a file that can only be downloaded; the tooltip gives the exact size and time. A name
     * is shown as it is, never as HTML.
     */
    private final class FileCellRenderer extends DefaultTableCellRenderer {

        FileCellRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(column == 1 ? RIGHT : LEFT);
            LogFileInfo file = row < files.size() ? files.get(row) : null;
            if (file != null) {
                if (!isSelected) {
                    // Every row, or the grey of a row that cannot be viewed stays on the rows painted after it.
                    Color grey = table.getForeground().equals(Color.GRAY) ? Color.LIGHT_GRAY : Color.GRAY;
                    setForeground(file.isViewable() ? table.getForeground() : grey);
                }
                StringBuilder tooltip = new StringBuilder("<html>").append(escapeHtml(file.getName()))
                        .append("<br>Size: ").append(LogViewerFormat.count(file.getSize())).append(" bytes")
                        .append("<br>Modified: ")
                        .append(LogViewerFormat.engineTime(file.getLastModified(), file.getTimeZoneId(),
                                "yyyy-MM-dd HH:mm:ss"))
                        .append(file.getTimeZoneLabel() != null ? " " + escapeHtml(file.getTimeZoneLabel()) : "")
                        .append(" (engine time)");
                if (file.getNote() != null) {
                    tooltip.append("<br>").append(escapeHtml(file.getNote()));
                }
                setToolTipText(tooltip.append("</html>").toString());
                if (column == 0) {
                    // An archive in a date folder gives way at the start of its folder, not at the end of
                    // its name, so archives with the same file name in different folders look different.
                    Insets insets = getInsets();
                    int width = table.getColumnModel().getColumn(column).getWidth() - insets.left - insets.right;
                    setText(LogViewerFormat.fitName(getText(), width, getFontMetrics(getFont())::stringWidth));
                }
            }
            return this;
        }
    }
}
