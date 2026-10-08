// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.ui.AuthorizationControllerFactory;
import com.mirth.connect.client.ui.PlatformUI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-modal log viewer, laid out like a text editor: a narrow list of the log
 * files on the left (which can be hidden), and on the right one toolbar above a
 * paged read-only viewer that gets all the remaining height, with a one-line
 * status line directly under it. "Find on this page..." opens a small non-modal
 * dialog; "Search all files..." opens a small dialog whose search first counts the
 * matching lines in each file. The counts dock under the viewer, one collapsed
 * group per file, with a status line of their own at the bottom. A group's lines
 * are fetched when it is expanded, and the next lines when its last row is clicked.
 *
 * <p>The engine runs every search, including the one that marks the matches on
 * the page shown: while the results are open each page request carries the same
 * search, and the viewer paints the match positions the engine returns. The only
 * regular expression the viewer runs itself is Find on this page's, on the page
 * shown, with a time limit ({@link LogPageFind}).</p>
 *
 * <p>Every server call runs in a SwingWorker, and the response is dropped when
 * the user has moved on since (each kind of request has a sequence number).
 * Routine conditions (a rotated file, a busy server, no access) show in the
 * notice bar at the top rather than in a popup.</p>
 *
 * <p>Each part of the window is its own class: {@link LogNoticeBar}, {@link LogFilePane} (with
 * {@link LogDownload}) in its {@link LogFileSplit}, {@link LogToolbar}, {@link LogViewerPane}, {@link LogFindDialog},
 * and {@link LogSearchRunner} with its {@link LogResultsPanel} and {@link LogSearchDialog}. The parts do
 * not call one another (the find dialog is handed the viewer's text area, which is what it
 * searches): each tells this dialog what the user did, through a listener, and the dialog decides
 * what follows. It holds the state the parts share (the file list, the file shown and whether it is
 * gone), makes the list and page requests, and puts their answers in the parts. The search runner
 * makes the search requests and holds the open search, which every page request carries; a
 * download makes its own request and reports how it ended.</p>
 */
public class LogViewerDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(LogViewerDialog.class);

    private final boolean canDownload;
    private final LogTooltips tips = new LogTooltips();

    // The parts of the window
    private LogNoticeBar noticeBar;
    private LogFilePane filePane;
    private LogDownload download;
    private LogToolbar toolbar;
    private LogViewerPane viewerPane;
    private LogFindDialog findDialog;
    private LogSearchRunner search;
    private LogFileSplit topSplit;

    // The file on screen when it is gone (a rollover renamed or removed it): its page stays,
    // but it can no longer be paged or downloaded. Null otherwise.
    private LogFileInfo goneFile;
    // A request for the file on screen was refused as STALE: the next list load enters the gone
    // state whatever it lists, since the engine has just said the name no longer leads to that file.
    private LogFileInfo goneAfterList;

    // The file list (the file pane shows it), the file selected in it and the file whose page is shown
    private final List<LogFileInfo> files = new ArrayList<>();
    private LogFileInfo current;
    private LogFileInfo viewed;
    // The search the page on screen was read with, whose marks it carries; null for none.
    private LogSearchParams pageMarks;

    // A request that returns after a newer one of its kind was made is ignored.
    private int listSeq;
    private int pageSeq;
    private boolean listBusy;
    private boolean pageBusy;

    public LogViewerDialog(JFrame parent) {
        super(parent, "Log Viewer", false); // non-modal

        canDownload = AuthorizationControllerFactory.getAuthorizationController()
                .checkTask(LogViewerClientPlugin.TASK_GROUP, LogViewerServletInterface.TASK_DOWNLOAD);

        initComponents();

        // Where the user left it last time, if that is still on a screen (shrunk to fit it); otherwise
        // 1280 x 800 centred on the Administrator window. The smallest it may be follows that screen.
        Rectangle bounds = LogViewerPreferences.openingBounds(parent);
        setMinimumSize(LogViewerWindowBounds.minimumSize(LogViewerPreferences.screenAt(bounds).getSize()));
        setBounds(bounds);
        // The file list's width, folded away when the user left it so.
        topSplit.place(bounds.width);

        // Escape does not close the viewer (it closes the Find and Search dialogs). The window's close
        // button closes it for good, so the next opening starts afresh.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        findDialog.installKeys(getRootPane());

        loadFiles(null, true);
    }

    /** The window's close button: asks first while a download runs, since closing cancels it. */
    private void close() {
        if (download.isActive() && JOptionPane.showConfirmDialog(this,
                "A download is in progress. Cancel it and close?", "Log Viewer",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        dispose();
    }

    @Override
    public void dispose() {
        rememberBounds();
        // Anything still in flight must not touch a dialog that is gone.
        listSeq++;
        pageSeq++;
        if (download != null) {
            download.abandon();
        }
        tips.restoreDismissDelay();
        if (search != null) {
            search.dispose();
        }
        if (findDialog != null) {
            findDialog.dispose();
        }
        super.dispose();
    }

    // ------------------------------------------------------------------
    // Layout: the parts, and what each one's controls lead to
    // ------------------------------------------------------------------

    private void initComponents() {
        setLayout(new BorderLayout(0, 0));

        noticeBar = new LogNoticeBar(tips, reopenName -> {
            hideNotice();
            loadFiles(reopenName, false);
        }, this::openFile);
        add(noticeBar, BorderLayout.NORTH);

        viewerPane = new LogViewerPane(tips, new LogViewerPane.Host() {
            @Override
            public LogFileInfo statusFile() {
                return viewed != null ? viewed : current != null ? current : (files.isEmpty() ? null : files.get(0));
            }

            @Override
            public boolean pageLoading() {
                return pageBusy;
            }

            @Override
            public boolean searchMarksOn() {
                return search.highlightParams() != null && pageMarks == search.highlightParams();
            }

            @Override
            public void loadLatest() {
                if (viewed != null) {
                    hideNotice();
                    loadPage(viewed, LogPageAnchor.TAIL, null, null, false);
                }
            }

            @Override
            public void textReplaced() {
                findDialog.markAgain();
            }
        });
        findDialog = new LogFindDialog(this, viewerPane.textArea(), tips);

        JPanel rightContent = new JPanel(new BorderLayout());
        rightContent.add(viewerPane, BorderLayout.CENTER);
        search = new LogSearchRunner(this, tips, rightContent, viewerPane, new LogSearchRunner.Host() {
            @Override
            public LogFileInfo scopeFile() {
                return viewed != null ? viewed : current;
            }

            @Override
            public LogFileInfo listedFile(String fileId) {
                return fileById(fileId);
            }

            @Override
            public void showNotice(String message) {
                noticeBar.showNotice(message, false, null);
            }

            @Override
            public void hideNotice() {
                LogViewerDialog.this.hideNotice();
            }

            @Override
            public void showError(Throwable error) {
                LogViewerDialog.this.showError(error, false, null);
            }

            @Override
            public void marksChanged() {
                viewerPane.clearSearchMarks();
            }

            @Override
            public void countFinished(LogSearchResult result) {
                if (result.getStopReason() != LogSearchStopReason.FILES_ROTATED) {
                    rereadForMarks();
                }
            }

            @Override
            public void openMatch(LogSearchMatch match, String fileName) {
                LogViewerDialog.this.openMatch(match, fileName);
            }

            @Override
            public void stateChanged() {
                updateControls();
            }
        });
        toolbar = new LogToolbar(tips, new LogToolbar.Listener() {
            @Override
            public void turnPage(LogPageAnchor anchor) {
                LogViewerDialog.this.turnPage(anchor);
            }

            @Override
            public void wordWrap(boolean on) {
                viewerPane.setWordWrap(on);
            }

            @Override
            public void specialCharacters(boolean on) {
                viewerPane.setSpecialCharacters(on);
            }

            @Override
            public void find() {
                findDialog.open();
            }

            @Override
            public void searchAll() {
                search.openDialog();
            }
        });
        JPanel rightPane = new JPanel(new BorderLayout());
        rightPane.add(toolbar, BorderLayout.NORTH);
        rightPane.add(rightContent, BorderLayout.CENTER);

        download = new LogDownload(this, tips, this::hideNotice, this::updateControls, this::downloadFailed);
        filePane = new LogFilePane(files, tips, canDownload, download, this::openFile, () -> {
            hideNotice();
            loadFiles(null, false);
        }, this::startDownload);

        topSplit = new LogFileSplit(filePane, rightPane);
        add(topSplit, BorderLayout.CENTER);

        updateControls();
    }

    /** Saves the size and position the user leaves the window at. */
    private void rememberBounds() {
        if (!isDisplayable()) {
            return;
        }
        LogViewerPreferences.rememberBounds(getBounds());
    }

    // ------------------------------------------------------------------
    // Notice bar
    // ------------------------------------------------------------------

    /** Hides the notice, except the gone notice, which stays until a file is opened or Dismiss is pressed. */
    private void hideNotice() {
        if (goneFile != null) {
            return;
        }
        noticeBar.hideNotice();
    }

    /**
     * The file on screen no longer exists under its name. Its page, highlights and the results
     * stay; paging and downloading are off until another file is opened.
     *
     * @param now the file that has that name in the fresh list, or null when none does
     */
    private void enterGone(LogFileInfo shown, LogFileInfo now) {
        goneFile = shown;
        goneAfterList = null;
        current = shown;
        filePane.select(null);
        noticeBar.showGone(shown.getName(), now);
        updateControls();
    }

    /** Called when any file is opened. */
    private void endGone() {
        goneFile = null;
        noticeBar.forgetOpenFile();
    }

    private void showError(Throwable error, boolean download, String fileName) {
        // An ExecutionException from a SwingWorker only wraps the real failure.
        log.warn("Log viewer request failed", error);
        LogViewerNotice notice = LogViewerNotice.from(error, download, canDownload);
        noticeBar.showNotice(notice.getMessage(), notice.isRefreshOffered(), fileName);
    }

    // ------------------------------------------------------------------
    // Server calls
    // ------------------------------------------------------------------

    /** A fresh proxy per call; the Administrator can replace its client (after a re-login). */
    static LogViewerServletInterface servlet() {
        return PlatformUI.MIRTH_FRAME.mirthClient.getServlet(LogViewerServletInterface.class, ExecuteType.ASYNC);
    }

    /**
     * Loads the file list.
     *
     * @param reopenName when not null, select the file with this name and open it
     *        (used after a rotation)
     * @param openFirst when true, open the first viewable file (used at start-up)
     */
    private void loadFiles(String reopenName, boolean openFirst) {
        final int seq = ++listSeq;
        listBusy = true;
        updateControls();
        new SwingWorker<LogFileList, Void>() {
            @Override
            protected LogFileList doInBackground() throws Exception {
                return servlet().listFiles();
            }

            @Override
            protected void done() {
                if (seq != listSeq) {
                    return;
                }
                listBusy = false;
                try {
                    applyFiles(get(), reopenName, openFirst);
                } catch (Exception e) {
                    LogFileInfo pending = goneAfterList;
                    if (pending != null && viewed != null && viewed.getId().equals(pending.getId())) {
                        // The list could not say whether a file by that name exists now.
                        log.warn("Log viewer request failed", e);
                        enterGone(viewed, null);
                    } else {
                        showError(e, false, null);
                    }
                }
                updateControls();
            }
        }.execute();
    }

    private void applyFiles(LogFileList list, String reopenName, boolean openFirst) {
        final LogFileInfo pendingGone = goneAfterList;
        goneAfterList = null;
        String selectedName = current != null ? current.getName() : null;

        List<LogFileInfo> sorted = new ArrayList<>(list.getFiles());
        // The server orders newest first, which puts the active file first already; this
        // only guarantees it, and keeps the server's order otherwise (the sort is stable).
        sorted.sort((a, b) -> Boolean.compare(b.isActive(), a.isActive()));
        files.clear();
        files.addAll(sorted);
        filePane.filesChanged();

        // Under the list, where they stay: the notice bar would lose them to the next notice.
        filePane.setWarnings(list.getWarnings());

        if (reopenName != null) {
            LogFileInfo file = fileByName(reopenName);
            if (file == null) {
                noticeBar.showNotice("The file " + reopenName + " is no longer listed.", false, null);
                return;
            }
            openFile(file);
        } else if (openFirst) {
            for (LogFileInfo file : files) {
                if (file.isViewable()) {
                    openFile(file);
                    return;
                }
            }
        } else if (viewed != null) {
            // A plain refresh keeps what is on screen, and the list must agree with it: the
            // entry selected is the one the viewer shows (found by id, never by name, because
            // after a rollover the same name is a different file).
            LogFileInfo shown = fileById(viewed.getId());
            boolean refused = pendingGone != null && pendingGone.getId().equals(viewed.getId());
            if (shown != null && !refused) {
                if (goneFile != null) {
                    // The file came back under its name (a rollover undone).
                    endGone();
                    hideNotice();
                }
                current = shown;
                viewed = shown;
                filePane.select(shown);
            } else {
                enterGone(viewed, fileByName(viewed.getName()));
            }
        } else if (selectedName != null) {
            // Nothing is shown (a file that can only be downloaded): restore the selection by name.
            LogFileInfo file = fileByName(selectedName);
            if (file != null) {
                current = file;
                filePane.select(file);
            }
        }
    }

    /**
     * Selects a file in the list without triggering the load, then loads the page it opens on:
     * the last page of the active file (where the newest lines are), the first page of an archive.
     */
    private void openFile(LogFileInfo file) {
        endGone();
        current = file;
        filePane.select(file);
        if (!file.isViewable()) {
            clearViewer();
            noticeBar.showNotice(LogViewerNotice.notViewableText(file.getName(), file.getNote(), canDownload), false,
                    null);
            updateControls();
            return;
        }
        hideNotice();
        loadPage(file, file.isActive() ? LogPageAnchor.TAIL : LogPageAnchor.HEAD, null, null, false);
    }

    /** A page button: First and Last page need no offset; Previous and Next page start from the page shown. */
    private void turnPage(LogPageAnchor anchor) {
        Long offset = null;
        if (anchor == LogPageAnchor.BEFORE) {
            offset = viewerPane.page().getStartOffset();
        } else if (anchor == LogPageAnchor.AFTER) {
            offset = viewerPane.page().getEndOffset();
        }
        navigate(anchor, offset);
    }

    private void navigate(LogPageAnchor anchor, Long offset) {
        if (viewerPane.page() == null || viewed == null) {
            return;
        }
        hideNotice();
        loadPage(viewed, anchor, offset, null, false);
    }

    /**
     * Reads a page. While the results are open the same search goes with the request, and
     * the engine answers with the match positions in the page it returns.
     *
     * @param keepView keep the scroll position and the caret, for a page that is only re-read
     *        to add the search marks
     */
    private void loadPage(LogFileInfo file, LogPageAnchor anchor, Long offset, LogSearchMatch reveal,
            boolean keepView) {
        final int seq = ++pageSeq;
        final LogSearchParams marks = search.highlightParams();
        pageBusy = true;
        updateControls();
        new SwingWorker<LogPage, Void>() {
            @Override
            protected LogPage doInBackground() throws Exception {
                return servlet().readPage(file.getId(), anchor, offset, marks != null ? marks.query : null,
                        marks != null && marks.regex, marks != null && marks.caseSensitive);
            }

            @Override
            protected void done() {
                if (seq != pageSeq) {
                    return;
                }
                pageBusy = false;
                try {
                    showPage(get(), file, anchor, reveal, keepView, marks);
                    // A count that arrived while this request was out could not re-read the page then.
                    rereadForMarks();
                } catch (Exception e) {
                    if (LogViewerNotice.isStale(e) && viewed != null && viewed.getId().equals(file.getId())) {
                        // The file on screen is gone: keep its page, and learn from the list whether
                        // a file has that name now.
                        log.warn("Log viewer request failed", e);
                        goneAfterList = viewed;
                        loadFiles(null, false);
                    } else {
                        LogPage page = viewerPane.page();
                        if (!LogViewerNotice.isStale(e) && page != null && viewed != null
                                && !viewed.getId().equals(file.getId())) {
                            // Opening another file failed: the page on screen stays, and the list points
                            // back at its file, so clicking the failed file again tries it again.
                            current = viewed;
                            filePane.select(viewed);
                        } else if (LogViewerNotice.isStale(e) || page == null || !file.getId().equals(page.getFileId())) {
                            // A rotated file's old text no longer matches any id the server will accept.
                            // Nothing stays selected, so clicking the file again tries it again.
                            clearViewer();
                            current = null;
                            filePane.select(null);
                        }
                        showError(e, false, file.getName());
                    }
                }
                updateControls();
            }
        }.execute();
    }

    private void showPage(LogPage p, LogFileInfo file, LogPageAnchor anchor, LogSearchMatch reveal,
            boolean keepView, LogSearchParams marks) {
        viewed = file;
        pageMarks = marks;
        viewerPane.showPage(p, file, anchor, reveal, keepView);
        toolbar.setViewing(file.isActive() ? file.getName() + " (active file)" : file.getName());
    }

    private void clearViewer() {
        viewed = null;
        pageMarks = null;
        viewerPane.clear();
        toolbar.setViewing(" ");
    }

    /**
     * Reads the page on screen again with the marks of the search whose count has arrived, when it
     * was read without them: the same lines (from its start, keeping the scroll), never the end of
     * the file, so an active file's snapshot moves only through Load latest lines. Never while a
     * page request is in flight: that request calls this again when it lands.
     */
    private void rereadForMarks() {
        LogPage page = viewerPane.page();
        if (!marksMissing(search.highlightParams(), search.countedSearch(), pageMarks) || pageBusy || page == null
                || viewed == null || goneFile != null) {
            return;
        }
        loadPage(viewed, LogPageAnchor.AFTER, page.getStartOffset(), null, true);
    }

    /**
     * True when the page lacks the open search's marks and that search's count has arrived (a search
     * the engine refused never gets that far, and its marks would be refused too).
     *
     * @param open the search the results are open for, or null
     * @param counted the search whose count arrived last, or null
     * @param onPage the search the page was read with, or null
     */
    static boolean marksMissing(LogSearchParams open, LogSearchParams counted, LogSearchParams onPage) {
        return open != null && open == counted && open != onPage;
    }

    // ------------------------------------------------------------------
    // Search all files (the runner makes the requests)
    // ------------------------------------------------------------------

    private void openMatch(LogSearchMatch match, String fileName) {
        LogFileInfo file = fileById(match.getFileId());
        if (file == null) {
            // Rotated out of the list since the search; the server will say STALE if it is gone.
            file = new LogFileInfo();
            file.setId(match.getFileId());
            file.setName(fileName);
            file.setViewable(true);
        }
        endGone();
        current = file;
        filePane.select(file);
        hideNotice();
        loadPage(file, LogPageAnchor.AT, match.getMatchOffset(), match, false);
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private void startDownload() {
        // The file on screen, never the list's selection, though the button sits by the list:
        // after a rollover a name can point at a different file than the one being read. If the
        // page has rotated since, the server answers STALE and the notice says so. With no page
        // shown, the selected file is used.
        download.start(viewed != null ? viewed : current);
    }

    /** A download failed: refused as STALE for the file on screen, that file is gone; anything else is a notice. */
    private void downloadFailed(LogFileInfo file, Throwable failure) {
        if (LogViewerNotice.isStale(failure) && viewed != null && viewed.getId().equals(file.getId())) {
            log.warn("Log viewer request failed", failure);
            goneAfterList = viewed;
            loadFiles(null, false);
        } else {
            showError(failure, true, file.getName());
        }
    }

    // ------------------------------------------------------------------
    // Control state and lookups
    // ------------------------------------------------------------------

    /**
     * Enables each control from what is running and what is on screen. While the file on screen is
     * gone, the controls that are off for that reason say so in their tooltips.
     */
    private void updateControls() {
        String goneTip = goneFile != null ? LogViewerNotice.goneTooltip(goneFile.getName()) : null;
        filePane.setListEnabled(!listBusy && !pageBusy);
        // Not while a page is loading: the file shown is about to change.
        filePane.setDownloadEnabled((viewed != null || current != null) && !download.isRunning() && !pageBusy
                && goneFile == null, goneTip);
        search.updateControls();
        viewerPane.setLoadLatestEnabled(!pageBusy && goneFile == null, goneTip);
        LogPage page = viewerPane.page();
        boolean have = page != null && viewed != null && !pageBusy && goneFile == null;
        boolean atStart = have && page.getStartOffset() <= 0;
        // The active file too: at the end of its snapshot, its newer lines come through Load latest lines.
        boolean atEnd = have && page.isAtEnd();
        toolbar.setPaging(have && !atStart, have && !atEnd, atEnd && viewed.isActive(), goneTip);
        toolbar.setSearchEnabled(!search.isBusy());
        viewerPane.updateStatusLine();
    }

    private LogFileInfo fileById(String id) {
        for (LogFileInfo file : files) {
            if (file.getId().equals(id)) {
                return file;
            }
        }
        return null;
    }

    private LogFileInfo fileByName(String name) {
        for (LogFileInfo file : files) {
            if (file.getName().equals(name)) {
                return file;
            }
        }
        return null;
    }
}
