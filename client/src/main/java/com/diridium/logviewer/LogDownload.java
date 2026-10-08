// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import javax.ws.rs.core.Response;

import net.miginfocom.swing.MigLayout;

import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.ui.PlatformUI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloading a log file exactly as it is on disk: asks where to save it (in the folder of the last
 * download), streams it into a part file of its own beside the target ({@link LogDownloadCopier}
 * never takes a name that is already there, and removes the part file on every way out but
 * success; the Administrator removes it on exit should it still be there) and renames it to the
 * chosen name once all of it has arrived. Its progress is measured against the Content-Length the
 * engine sends. This is also the row under the file list that shows the download's progress, its
 * Cancel button, and how the last one ended. One download runs at a time.
 */
final class LogDownload extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(LogDownload.class);

    private final Component owner;
    private final Runnable starting;
    private final Runnable changed;
    private final BiConsumer<LogFileInfo, Throwable> failed;
    private final JProgressBar downloadBar;
    private final JButton btnCancelDownload;
    private final JLabel lblDownload;
    private DownloadWorker activeDownload;

    /**
     * @param owner the window the save and replace dialogs open over
     * @param starting the user has chosen where to save, and the download is about to start
     * @param changed a download started or ended, so the controls that depend on one running are set again
     * @param failed the download of a file failed (the part file is already removed)
     */
    LogDownload(Component owner, LogTooltips tips, Runnable starting, Runnable changed,
            BiConsumer<LogFileInfo, Throwable> failed) {
        super(new MigLayout("insets 0, fillx", "[grow][]", ""));
        this.owner = owner;
        this.starting = starting;
        this.changed = changed;
        this.failed = failed;

        downloadBar = new JProgressBar(0, 1000);
        downloadBar.setStringPainted(true);
        tips.tip(downloadBar, "How much of the file has been saved so far.");
        btnCancelDownload = new JButton("Cancel");
        tips.tip(btnCancelDownload, "Stops the download and removes the partly saved file.");
        btnCancelDownload.addActionListener(e -> cancelDownload());
        lblDownload = new JLabel(" ");
        lblDownload.putClientProperty("html.disable", Boolean.TRUE);
        tips.tip(lblDownload, "The state of the last download.");
        add(downloadBar, "growx");
        add(btnCancelDownload, "wrap");
        add(lblDownload, "span, growx, wmin 0");
        downloadBar.setVisible(false);
        btnCancelDownload.setVisible(false);
    }

    /** True from the start of a download until it has ended, a cancelled one included. */
    boolean isRunning() {
        return activeDownload != null;
    }

    /** True while a download runs that has not been cancelled. */
    boolean isActive() {
        return activeDownload != null && !activeDownload.cancelled.get();
    }

    /** Asks where to save the file, then downloads it there. Does nothing without a file, or while a download runs. */
    void start(LogFileInfo file) {
        if (file == null || activeDownload != null) {
            return;
        }
        lblDownload.setText(" ");
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save " + file.getName());
        String name = LogViewerFormat.saveName(file.getName());
        File folder = LogViewerPreferences.downloadFolder();
        chooser.setSelectedFile(folder != null ? new File(folder, name) : new File(name));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        LogViewerPreferences.rememberDownloadFolder(target.getAbsoluteFile().getParentFile());
        if (target.exists()) {
            JLabel replace = new JLabel(target.getName() + " already exists. Replace it?");
            replace.putClientProperty("html.disable", Boolean.TRUE);
            if (JOptionPane.showConfirmDialog(owner, replace, "Download Log File",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
        }

        starting.run();
        DownloadWorker worker = new DownloadWorker(file, target);
        activeDownload = worker;
        // Until the engine answers with the file's length.
        downloadBar.setIndeterminate(true);
        downloadBar.setValue(0);
        downloadBar.setString("0 B");
        downloadBar.setVisible(true);
        btnCancelDownload.setVisible(true);
        lblDownload.setText("Downloading " + file.getName() + "...");
        changed.run();
        worker.execute();
    }

    /** The dialog is closing: a download still running stops, and its part file is removed. */
    void abandon() {
        if (activeDownload != null) {
            activeDownload.cancelled.set(true);
            activeDownload = null;
        }
    }

    /**
     * Stops the download and gives the dialog back at once. The stream is closed on a
     * background thread (closing it drains the rest of the body), and the copier removes
     * the part file; Download stays off until that is done.
     */
    private void cancelDownload() {
        DownloadWorker worker = activeDownload;
        if (worker == null) {
            return;
        }
        worker.cancelled.set(true);
        downloadBar.setVisible(false);
        btnCancelDownload.setVisible(false);
        lblDownload.setText("Cancelling the download...");
    }

    private void finishDownload(DownloadWorker worker, File saved, Throwable failure) {
        if (worker != activeDownload) {
            return;
        }
        activeDownload = null;
        downloadBar.setVisible(false);
        btnCancelDownload.setVisible(false);
        if (failure != null && !worker.cancelled.get()) {
            lblDownload.setText(" ");
            failed.accept(worker.file, failure);
        } else if (saved == null) {
            // Cancelled, and anything that failed after Cancel was pressed is part of stopping.
            lblDownload.setText("Download cancelled.");
        } else {
            lblDownload.setText("Saved " + saved.getName() + " (" + LogViewerFormat.bytes(saved.length()) + ").");
        }
        changed.run();
    }

    /** A fresh proxy per call; the Administrator can replace its client (after a re-login). */
    private static LogViewerServletInterface servlet() {
        return PlatformUI.MIRTH_FRAME.mirthClient.getServlet(LogViewerServletInterface.class, ExecuteType.ASYNC);
    }

    private final class DownloadWorker extends SwingWorker<File, Long> {

        final AtomicBoolean cancelled = new AtomicBoolean();
        private final LogFileInfo file;
        private final File target;
        // The length the engine sent (Content-Length), or -1 until it answers or when it did not say.
        private volatile long total = -1;

        DownloadWorker(LogFileInfo file, File target) {
            this.file = file;
            this.target = target;
        }

        @Override
        protected File doInBackground() throws Exception {
            Response response = servlet().download(file.getId());
            total = response.getLength();
            InputStream in = response.readEntity(InputStream.class);
            Path part;
            boolean complete;
            try {
                part = LogDownloadCopier.createPart(target.toPath());
                part.toFile().deleteOnExit();
                long[] lastPublished = {0};
                complete = LogDownloadCopier.copy(in, part, cancelled::get, written -> {
                    // Not every chunk: the event queue only needs the last figure of each moment.
                    long now = System.currentTimeMillis();
                    if (now - lastPublished[0] >= 100) {
                        lastPublished[0] = now;
                        publish(written);
                    }
                });
            } finally {
                closeInBackground(in);
            }
            if (!complete || cancelled.get()) {
                // Cancel pressed after the last byte arrived still means do not save it.
                Files.deleteIfExists(part);
                return null;
            }
            try {
                Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }
            return target;
        }

        @Override
        protected void process(List<Long> written) {
            if (cancelled.get() || this != activeDownload) {
                return;
            }
            long bytes = written.get(written.size() - 1);
            long length = total;
            if (length > 0) {
                downloadBar.setIndeterminate(false);
                downloadBar.setValue((int) Math.min(1000, bytes * 1000 / length));
            }
            downloadBar.setString(LogViewerFormat.progress(bytes, length));
        }

        @Override
        protected void done() {
            try {
                finishDownload(this, get(), null);
            } catch (Exception e) {
                finishDownload(this, null, e);
            }
        }
    }

    /** Closing a client stream early drains the rest of the body, so it must not be on a thread anyone waits on. */
    private static void closeInBackground(InputStream in) {
        Thread closer = new Thread(() -> {
            try {
                in.close();
            } catch (IOException e) {
                log.debug("Closing the download stream failed", e);
            }
        }, "log-viewer-download-close");
        closer.setDaemon(true);
        closer.start();
    }
}
