// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingWorker;

import com.diridium.logviewer.LogResultsPanel.GroupNode;
import com.diridium.logviewer.LogResultsPanel.NoteNode;

/**
 * Search all files, for the dialog: the Search dialog, the count and Count the rest, each group's
 * lines, and the results docked under the viewer. The engine runs the search; this makes the
 * requests and puts the answers in the results panel. The page side stays the dialog's: it asks
 * {@link #highlightParams()} for the search to send with each page request, and is told when a
 * count has finished so the page on screen can be read again with the marks.
 */
final class LogSearchRunner {

    /** What the search side needs from the dialog, which holds the file list and the page. */
    interface Host {
        /** The file "This file" searches: the one shown, else the one selected; null when neither. */
        LogFileInfo scopeFile();

        /** The listed file with this id, which names its group; null when it is no longer listed. */
        LogFileInfo listedFile(String fileId);

        /** Shows a routine message in the notice bar. */
        void showNotice(String message);

        void hideNotice();

        /** A request failed: the notice bar says why. */
        void showError(Throwable error);

        /** A new search started, the search failed or the results closed: the old marks come off the page. */
        void marksChanged();

        /** A new search's count arrived. */
        void countFinished(LogSearchResult result);

        /** A matching line was double-clicked: show it in the viewer. */
        void openMatch(LogSearchMatch match, String fileName);

        /** A request started or ended, so the controls are set again. */
        void stateChanged();
    }

    private final Host host;
    private final LogResultsPanel resultsPanel;
    private final LogSearchDialog searchDialog;
    // The viewer, and the panel it sits in, which the results dock under.
    private final JPanel rightContent;
    private final Component viewer;
    private JSplitPane resultsSplit;

    // The search, while its results are docked under the viewer
    private boolean resultsOpen;
    private LogSearchParams highlightParams;
    private LogSearchParams lastSearch;
    private LogSearchResult lastCount;
    // Changes when the results are replaced or closed, so a group's late answer is dropped.
    private int resultsGen;
    // A count that returns after a newer one was made is ignored.
    private int searchSeq;
    private boolean searchBusy;

    /**
     * @param owner the log viewer's window, which the Search dialog opens over
     * @param rightContent the panel that holds {@code viewer}, which the results dock under
     */
    LogSearchRunner(JDialog owner, LogTooltips tips, JPanel rightContent, Component viewer, Host host) {
        this.host = host;
        this.rightContent = rightContent;
        this.viewer = viewer;
        searchDialog = new LogSearchDialog(owner, tips, () -> count(false));
        resultsPanel = new LogResultsPanel(tips, new LogResultsPanel.Listener() {
            @Override
            public void editSearch(LogSearchParams search) {
                searchDialog.reopen(search);
            }

            @Override
            public void countRest() {
                count(true);
            }

            @Override
            public void close() {
                closeResults();
            }

            @Override
            public void loadMatches(GroupNode group) {
                LogSearchRunner.this.loadMatches(group);
            }

            @Override
            public void openMatch(LogSearchMatch match, String fileName) {
                host.openMatch(match, fileName);
            }

            @Override
            public LogFileInfo listedFile(String fileId) {
                return host.listedFile(fileId);
            }
        });
    }

    /** Search all files... was pressed. */
    void openDialog() {
        searchDialog.open();
    }

    /** The search to send with each page request, so the engine marks its matches; null without one. */
    LogSearchParams highlightParams() {
        return highlightParams;
    }

    /** The search whose count arrived last; null before one did and after the results close. */
    LogSearchParams countedSearch() {
        return lastSearch;
    }

    /** True while a count runs. */
    boolean isBusy() {
        return searchBusy;
    }

    /** The parts of the results and the Search dialog that depend on a count running. */
    void updateControls() {
        resultsPanel.updateStatus(searchBusy);
        resultsPanel.setCountRestEnabled(!searchBusy);
        searchDialog.setSearchEnabled(!searchBusy);
    }

    /** The dialog is closing: nothing still in flight may touch the results. */
    void dispose() {
        searchSeq++;
        resultsGen++;
        searchDialog.dispose();
    }

    /**
     * Counts the matching lines in each file. Resuming carries on from where the time limit
     * stopped the last count and adds to it.
     */
    private void count(boolean resume) {
        final LogSearchParams params;
        final String resumeFileId;
        final Long resumeOffset;
        if (resume) {
            if (lastSearch == null || lastCount == null) {
                return;
            }
            params = lastSearch;
            resumeFileId = lastCount.getResumeFileId();
            resumeOffset = lastCount.getResumeOffset();
        } else {
            String query = searchDialog.query();
            if (query == null || query.isEmpty()) {
                return;
            }
            String fileId = null;
            String fileName = null;
            if (searchDialog.thisFileOnly()) {
                LogFileInfo scope = host.scopeFile();
                if (scope == null) {
                    host.showNotice("Open a file to search only that file.");
                    return;
                }
                fileId = scope.getId();
                fileName = scope.getName();
            }
            params = new LogSearchParams(query, searchDialog.regex(), searchDialog.caseSensitive(), fileId, fileName);
            resumeFileId = null;
            resumeOffset = null;
            resultsGen++;
            resultsPanel.clear();
            lastCount = null;
            openResults(params);
            // The page shows the old search's marks until it is read again with this one's.
            host.marksChanged();
        }

        host.hideNotice();
        final int seq = ++searchSeq;
        searchBusy = true;
        resultsPanel.countStarted(resume);
        host.stateChanged();
        new SwingWorker<LogSearchResult, Void>() {
            // How long the viewer waited for the engine, for the results' status line.
            private long elapsedMillis;

            @Override
            protected LogSearchResult doInBackground() throws Exception {
                long started = System.nanoTime();
                try {
                    return LogViewerDialog.servlet().count(params.query, params.regex, params.caseSensitive,
                            params.fileId, resumeFileId, resumeOffset);
                } finally {
                    elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                }
            }

            @Override
            protected void done() {
                if (seq != searchSeq) {
                    return;
                }
                searchBusy = false;
                resultsPanel.addElapsed(elapsedMillis);
                try {
                    showCountResult(params, get(), resume, resumeFileId, resumeOffset);
                } catch (Exception e) {
                    resultsPanel.countFailed(resume);
                    if (!resume) {
                        // A search the engine refused (a bad pattern) would be refused on every page read too.
                        highlightParams = null;
                        host.marksChanged();
                    }
                    host.showError(e);
                }
                host.stateChanged();
            }
        }.execute();
    }

    /** Docks the results panel under the viewer, if it is not there already. */
    private void openResults(LogSearchParams params) {
        highlightParams = params;
        resultsPanel.showSearch(params);
        if (resultsOpen) {
            return;
        }
        resultsOpen = true;
        rightContent.remove(viewer);
        resultsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewer, resultsPanel);
        resultsSplit.setContinuousLayout(true);
        resultsSplit.setResizeWeight(1.0);
        rightContent.add(resultsSplit, BorderLayout.CENTER);
        rightContent.revalidate();
        int height = rightContent.getHeight();
        if (height > 0) {
            resultsSplit.setDividerLocation((int) (height * 0.62));
        } else {
            resultsSplit.setDividerLocation(0.62);
        }
        rightContent.repaint();
    }

    /** Removes the results panel, forgets the search and clears the marks from the viewer. */
    private void closeResults() {
        if (!resultsOpen) {
            return;
        }
        // A search still running has nowhere to put its answer.
        searchSeq++;
        searchBusy = false;
        resultsOpen = false;
        highlightParams = null;
        lastSearch = null;
        lastCount = null;
        resultsGen++;
        resultsPanel.clear();
        rightContent.remove(resultsSplit);
        resultsSplit = null;
        rightContent.add(viewer, BorderLayout.CENTER);
        rightContent.revalidate();
        rightContent.repaint();
        host.marksChanged();
        host.stateChanged();
    }

    private void showCountResult(LogSearchParams params, LogSearchResult result, boolean resume,
            String sentFileId, Long sentOffset) {
        lastSearch = params;
        lastCount = result;
        resultsPanel.showCount(result, resume && LogCountTally.sameResumePoint(sentFileId, sentOffset, result));
        if (!resume) {
            host.countFinished(result);
        }
    }

    /** Fetches a group's lines: its first page of matches, or the next one when it has a resume point. */
    private void loadMatches(GroupNode group) {
        final LogSearchParams params = lastSearch;
        if (params == null || group.loading || group.gone) {
            return;
        }
        final int gen = resultsGen;
        final String resumeFileId = group.resumeFileId;
        final Long resumeOffset = group.resumeOffset;
        group.failed = false;
        resultsPanel.removeNotes(group);
        group.loading = true;
        final NoteNode loading = resultsPanel.addNote(group, group.loaded
                ? LogViewerFormat.loadingMoreRow(group.shown, group.count, group.complete) : "Loading matches...");
        resultsPanel.updateStatus(searchBusy);
        new SwingWorker<LogSearchResult, Void>() {
            @Override
            protected LogSearchResult doInBackground() throws Exception {
                return LogViewerDialog.servlet().search(params.query, params.regex, params.caseSensitive,
                        group.fileId, resumeFileId, resumeOffset);
            }

            @Override
            protected void done() {
                if (gen != resultsGen) {
                    return;
                }
                group.loading = false;
                resultsPanel.removeNote(group, loading);
                try {
                    resultsPanel.appendMatches(group, get(), resumeFileId, resumeOffset);
                } catch (Exception e) {
                    if (LogViewerNotice.isStale(e)) {
                        // Retrying would fail the same way, so the row is plain text, not a click target.
                        group.gone = true;
                        resultsPanel.addNote(group, LogViewerFormat.fileGoneRow(group.name));
                    } else {
                        group.failed = true;
                        if (group.loaded) {
                            resultsPanel.addMoreRow(group, LogViewerFormat.LOAD_MORE_FAILED);
                        } else {
                            resultsPanel.addNote(group,
                                    "The matches could not be loaded. Collapse and expand this file to try again.");
                        }
                        host.showError(e);
                    }
                }
                resultsPanel.updateStatus(searchBusy);
            }
        }.execute();
    }
}
