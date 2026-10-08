// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Dialog;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import net.miginfocom.swing.MigLayout;

/**
 * The small document-modal dialog behind "Search all files...": the text or Java regular expression
 * to look for, and whether to search every listed file or only the one shown. Search closes it and
 * runs the search, which the search runner does; this only asks. Search is off while a count runs,
 * however the dialog was opened.
 *
 * <p>Document-modal blocks every window under the same window without an owner. The viewer is owned
 * by the Administrator's window, so that is the root and the Administrator is blocked too while this
 * is open (measured on 4.6.0), as it was when this dialog was application-modal.</p>
 */
final class LogSearchDialog extends JDialog {

    /** The tooltip for a regular expression box: the search and the page marks are Java, not JavaScript. */
    static final String REGEX_TIP = "Java regular expression (java.util.regex), matched against one line at a time. "
            + "Not the same as the JavaScript regular expressions used in channel scripts.";
    private static final String REGEX_EXAMPLES = "Examples: ^ERROR finds lines that start with ERROR; "
            + "timeout|refused finds either word.";

    private final JTextField txtQuery;
    private final JCheckBox chkRegex;
    private final JCheckBox chkCase;
    private final JComboBox<String> cboScope;
    private final JButton btnRun;
    private final Runnable search;

    /**
     * @param owner the log viewer's window, which it opens over
     * @param search Search was pressed with something to search for; the dialog is already closed
     */
    LogSearchDialog(JDialog owner, LogTooltips tips, Runnable search) {
        super(owner, "Search all files", Dialog.ModalityType.DOCUMENT_MODAL);
        this.search = search;
        JPanel panel = new JPanel(new MigLayout("insets 12, fillx", "[][grow]", ""));

        JLabel lblQuery = new JLabel("Search for:");
        tips.tip(lblQuery, "The text to look for in the log files.");
        txtQuery = new JTextField(34);
        tips.tip(txtQuery, "The text to look for, up to 1,000 characters. With Java regular expression on, a pattern.",
                "The engine runs the search; the viewer only sends it.");
        txtQuery.addActionListener(e -> runSearchFromDialog());
        chkRegex = new JCheckBox("Java regular expression");
        tips.tip(chkRegex, REGEX_TIP, REGEX_EXAMPLES);
        // Said on the dialog itself, not only in a tooltip: the engine's Java regular expressions
        // differ from the JavaScript ones used in channel scripts.
        JLabel lblRegexNote = new JLabel("Regular expressions here are Java's (java.util.regex), not JavaScript's: "
                + "the engine runs the search.");
        chkCase = new JCheckBox("Match case");
        tips.tip(chkCase, "Only match text with the same upper and lower case.");
        JLabel lblScope = new JLabel("Search in:");
        tips.tip(lblScope, "Which files to search.");
        cboScope = new JComboBox<>(new String[] {"All files", "This file"});
        tips.tip(cboScope, "All files searches every listed log file, newest first. "
                + "This file searches only the file shown in the viewer.");
        btnRun = new JButton("Search");
        tips.tip(btnRun, "Closes this window and counts the matching lines in each file on the engine.");
        btnRun.addActionListener(e -> runSearchFromDialog());
        JButton btnCancel = new JButton("Cancel");
        tips.tip(btnCancel, "Closes this window without searching.");
        btnCancel.addActionListener(e -> setVisible(false));

        panel.add(lblQuery);
        panel.add(txtQuery, "growx, wrap");
        panel.add(chkRegex, "skip, split 2");
        panel.add(chkCase, "gapleft 12, wrap");
        panel.add(lblRegexNote, "skip, wrap");
        panel.add(lblScope);
        panel.add(cboScope, "wrap");
        panel.add(btnRun, "skip, split 2, align right, gaptop 10");
        panel.add(btnCancel, "gaptop 10");

        setContentPane(panel);
        getRootPane().setDefaultButton(btnRun);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });
        pack();
        setResizable(false);
    }

    void open() {
        pack();
        setLocationRelativeTo(getOwner());
        txtQuery.requestFocusInWindow();
        txtQuery.selectAll();
        setVisible(true);
    }

    /** The results title was clicked: the dialog, filled in with the search the results are for. */
    void reopen(LogSearchParams titleSearch) {
        txtQuery.setText(titleSearch.query);
        chkRegex.setSelected(titleSearch.regex);
        chkCase.setSelected(titleSearch.caseSensitive);
        cboScope.setSelectedIndex(titleSearch.fileId != null ? 1 : 0);
        open();
    }

    String query() {
        return txtQuery.getText();
    }

    boolean regex() {
        return chkRegex.isSelected();
    }

    boolean caseSensitive() {
        return chkCase.isSelected();
    }

    /** "This file" is chosen: only the file shown is searched. */
    boolean thisFileOnly() {
        return cboScope.getSelectedIndex() == 1;
    }

    /** Search is off while a count runs: one search at a time. */
    void setSearchEnabled(boolean enabled) {
        btnRun.setEnabled(enabled);
    }

    private void runSearchFromDialog() {
        if (txtQuery.getText().isEmpty() || !btnRun.isEnabled()) {
            return;
        }
        setVisible(false);
        search.run();
    }
}
