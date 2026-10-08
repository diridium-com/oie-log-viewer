// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;

import net.miginfocom.swing.MigLayout;

import org.fife.ui.rsyntaxtextarea.DocumentRange;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;
import org.fife.ui.rtextarea.SmartHighlightPainter;

/**
 * The small non-modal dialog behind "Find on this page...", in the style of a text editor's
 * find box: the viewer stays usable while it is open. It finds and marks text in the page shown,
 * in the viewer's own text area; unlike Search all files, the engine is not asked. Plain text is
 * found with RSyntaxTextArea's search. A Java regular expression is matched by
 * {@link LogPageFind}, with a 2-second limit, because it runs on the event thread. Both match
 * what the viewer shows, so in special mode they can match the CR and LF labels too.
 */
final class LogFindDialog extends JDialog {

    private final RSyntaxTextArea area;
    // Mark all's marks for a regular expression; plain text's are RSyntaxTextArea's own.
    private final List<Object> patternMarks = new ArrayList<>();
    // What the pattern marks were made for (the text, then the case option), so finding again
    // with the same pattern does not mark the whole page again.
    private String patternMarked;
    private final JTextField txtFind;
    private final JCheckBox chkFindCase;
    private final JCheckBox chkFindRegex;
    private final JLabel lblFindStatus;
    private boolean findPlaced;
    // True while Mark all's marks are on the page, so a new page or display mode gets them again.
    private boolean findMarked;

    /**
     * @param owner the log viewer's window, which it opens beside
     * @param area the viewer's text area
     */
    LogFindDialog(JDialog owner, RSyntaxTextArea area, LogTooltips tips) {
        super(owner, "Find on this page", false);
        this.area = area;
        JPanel panel = new JPanel(new MigLayout("insets 10, fillx", "[][grow]", ""));

        JLabel lblFind = new JLabel("Find:");
        tips.tip(lblFind, "The text to look for in the page shown.");
        txtFind = new JTextField(26);
        tips.tip(txtFind, "Text to find in the page shown. Enter finds the next one; F3 and Shift+F3 find the next "
                + "and the previous one.");
        txtFind.addActionListener(e -> find(true));
        chkFindCase = new JCheckBox("Match case");
        tips.tip(chkFindCase, "Only find text with the same upper and lower case.");
        chkFindRegex = new JCheckBox("Java regular expression");
        tips.tip(chkFindRegex, "Java regular expression, matched against the page shown only.");
        JButton btnFindNext = new JButton("Find next");
        tips.tip(btnFindNext, "Finds the next occurrence in the page shown.");
        btnFindNext.addActionListener(e -> find(true));
        JButton btnFindPrevious = new JButton("Find previous");
        tips.tip(btnFindPrevious, "Finds the previous occurrence in the page shown.");
        btnFindPrevious.addActionListener(e -> find(false));
        JButton btnMarkAll = new JButton("Mark all");
        tips.tip(btnMarkAll, "Marks every occurrence in the page shown, and says how many there are.");
        btnMarkAll.addActionListener(e -> markAll());
        JButton btnFindClose = new JButton("Close");
        tips.tip(btnFindClose, "Closes this window and removes its marks from the page.");
        btnFindClose.addActionListener(e -> close());
        lblFindStatus = new JLabel(" ");
        tips.tip(lblFindStatus, "The result of the last find.");

        panel.add(lblFind);
        panel.add(txtFind, "growx, wrap");
        panel.add(chkFindCase, "skip, split 2");
        panel.add(chkFindRegex, "gapleft 12, wrap");
        panel.add(btnFindNext, "skip, split 4, gaptop 6");
        panel.add(btnFindPrevious, "gaptop 6");
        panel.add(btnMarkAll, "gaptop 6");
        panel.add(btnFindClose, "gaptop 6, wrap");
        panel.add(lblFindStatus, "skip, growx");

        setContentPane(panel);
        getRootPane().setDefaultButton(null);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        getRootPane().getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
        installKeys(getRootPane());
        pack();
        setResizable(false);
    }

    /** Ctrl+F (Cmd+F on macOS) opens the find dialog, and F3 and Shift+F3 find the next and previous. */
    void installKeys(JRootPane root) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "find");
        root.getActionMap().put("find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "findNext");
        root.getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                find(true);
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "findPrevious");
        root.getActionMap().put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                find(false);
            }
        });
    }

    void open() {
        String selected = area.getSelectedText();
        if (selected != null && !selected.isEmpty() && selected.indexOf('\n') < 0) {
            txtFind.setText(selected);
        }
        lblFindStatus.setText(" ");
        if (!findPlaced) {
            // Beside the viewer's top right corner, clear of the text being read.
            Window owner = getOwner();
            Point at = owner.getLocation();
            setLocation(at.x + Math.max(0, owner.getWidth() - getWidth() - 40), at.y + 120);
            findPlaced = true;
        }
        setVisible(true);
        toFront();
        // requestFocus, not InWindow: when the dialog is already open this also brings its window forward.
        txtFind.requestFocus();
        txtFind.selectAll();
    }

    /** The page's text was replaced, which dropped every mark: Mark all's marks go back on when they were there. */
    void markAgain() {
        if (findMarked) {
            markAll();
        }
    }

    private void close() {
        setVisible(false);
        clearMarks();
        findMarked = false;
        area.requestFocusInWindow();
    }

    /** Takes Mark all's marks off the page, a regular expression's and plain text's. */
    private void clearMarks() {
        area.clearMarkAllHighlights();
        Highlighter highlighter = area.getHighlighter();
        for (Object tag : patternMarks) {
            highlighter.removeHighlight(tag);
        }
        patternMarks.clear();
        patternMarked = null;
    }

    private SearchContext findContext(boolean forward) {
        SearchContext context = new SearchContext(txtFind.getText());
        context.setMatchCase(chkFindCase.isSelected());
        context.setRegularExpression(chkFindRegex.isSelected());
        context.setSearchForward(forward);
        context.setWholeWord(false);
        context.setMarkAll(findMarked);
        return context;
    }

    private void find(boolean forward) {
        if (txtFind.getText().isEmpty()) {
            clearMarks();
            findMarked = false;
            lblFindStatus.setText(" ");
            return;
        }
        if (chkFindRegex.isSelected()) {
            findPattern(forward);
            return;
        }
        if (findMarked && !patternMarks.isEmpty()) {
            // Mark all was a regular expression's; plain text's search marks again below.
            clearMarks();
        }
        SearchContext context = findContext(forward);
        SearchResult result = SearchEngine.find(area, context);
        LogPageFind.Outcome outcome = LogPageFind.Outcome.FOUND;
        if (!result.wasFound()) {
            // Wrap round once, from the other end.
            area.setCaretPosition(forward ? 0 : area.getDocument().getLength());
            result = SearchEngine.find(area, context);
            outcome = result.wasFound() ? LogPageFind.Outcome.WRAPPED : LogPageFind.Outcome.NOT_FOUND;
        }
        area.getCaret().setSelectionVisible(true);
        setStatus(LogPageFind.status(outcome, forward));
    }

    /** Find next or previous for a Java regular expression, from the selection, wrapping round once. */
    private void findPattern(boolean forward) {
        Pattern pattern = compileOrSay();
        if (pattern == null) {
            return;
        }
        if (findMarked && !marksAreFor(pattern)) {
            // As for plain text: with Mark all's marks on, finding marks the page again for what is asked now.
            markAll();
            if (!findMarked) {
                return;
            }
        }
        int from = forward ? Math.max(area.getCaret().getDot(), area.getCaret().getMark())
                : Math.min(area.getCaret().getDot(), area.getCaret().getMark());
        LogPageFind.Result result = LogPageFind.find(pattern, area.getText(), from, forward, System::nanoTime);
        if (!result.ranges.isEmpty()) {
            int[] match = result.ranges.get(0);
            RSyntaxUtilities.selectAndPossiblyCenter(area, new DocumentRange(match[0], match[1]), true);
            area.getCaret().setSelectionVisible(true);
        }
        setStatus(LogPageFind.status(result.outcome, forward));
    }

    /** The pattern in the find box, or null after saying it is not a valid regular expression. */
    private Pattern compileOrSay() {
        try {
            return LogPageFind.compile(txtFind.getText(), chkFindCase.isSelected());
        } catch (PatternSyntaxException e) {
            lblFindStatus.setText("Not a valid regular expression.");
            return null;
        }
    }

    private boolean marksAreFor(Pattern pattern) {
        return (pattern.pattern() + "\n" + chkFindCase.isSelected()).equals(patternMarked);
    }

    private void setStatus(String status) {
        lblFindStatus.setText(status.isEmpty() ? " " : status);
    }

    /** Marks every occurrence on the page, and keeps the marks on when the page or the display mode changes. */
    private void markAll() {
        clearMarks();
        findMarked = false;
        if (txtFind.getText().isEmpty()) {
            lblFindStatus.setText(" ");
            return;
        }
        if (chkFindRegex.isSelected()) {
            markPattern();
            return;
        }
        SearchContext context = findContext(true);
        // markAll only marks when the context asks for it.
        context.setMarkAll(true);
        SearchResult marked = SearchEngine.markAll(area, context);
        findMarked = marked.getMarkedCount() > 0;
        lblFindStatus.setText(LogPageFind.markedStatus(marked.getMarkedCount()));
    }

    /** Mark all for a Java regular expression: nothing is marked when the time limit or the stack stops it. */
    private void markPattern() {
        Pattern pattern = compileOrSay();
        if (pattern == null) {
            return;
        }
        LogPageFind.Result result = LogPageFind.all(pattern, area.getText(), System::nanoTime);
        if (result.outcome == LogPageFind.Outcome.TIME_LIMIT || result.outcome == LogPageFind.Outcome.TOO_COMPLEX) {
            setStatus(LogPageFind.status(result.outcome, true));
            return;
        }
        // Painted as RSyntaxTextArea paints its own Mark all.
        SmartHighlightPainter painter = new SmartHighlightPainter(area.getMarkAllHighlightColor());
        Highlighter highlighter = area.getHighlighter();
        for (int[] range : result.ranges) {
            try {
                patternMarks.add(highlighter.addHighlight(range[0], range[1], painter));
            } catch (BadLocationException e) {
                // The ranges come from the text just read; there is nothing else to mark.
            }
        }
        patternMarked = pattern.pattern() + "\n" + chkFindCase.isSelected();
        findMarked = !result.ranges.isEmpty();
        lblFindStatus.setText(LogPageFind.markedStatus(result.ranges.size()));
    }
}
