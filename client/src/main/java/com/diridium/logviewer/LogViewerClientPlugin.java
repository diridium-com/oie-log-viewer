// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JOptionPane;

import com.mirth.connect.client.core.TaskConstants;
import com.mirth.connect.client.ui.AuthorizationControllerFactory;
import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ClientPlugin;

/**
 * Plugin that adds a "View Log Files" task to the Administrator's Other task
 * pane, which opens the log viewer dialog.
 *
 * <p>Why the Other pane and not the Dashboard: the Dashboard hides every task
 * after its first whenever its table selection changes, then shows back only
 * its own tasks by position (4.6.0 {@code DashboardPanel}), so a plugin task
 * there disappears on the first click. The Other pane (Notifications, Help,
 * About, Logout) is made visible once at login and never hidden again, so the
 * entry is there in every view.</p>
 */
public class LogViewerClientPlugin extends ClientPlugin {

    /** The Other pane's name, which the engine passes as the group when it checks its tasks. */
    static final String TASK_GROUP = TaskConstants.OTHER_KEY;

    /**
     * The classes of this plugin the Administrator's XStream may build: every type the engine answers
     * with (the file list, a page, a search or count, the error body) and every class of this plugin
     * they hold, enums included. The JDK types in them (strings, boxed numbers, lists, int arrays) are
     * the engine's own defaults. A test walks the answers' fields to keep this list complete.
     */
    static final List<Class<?>> DESERIALIZED = Collections.unmodifiableList(Arrays.asList(
            LogFileList.class, LogFileInfo.class, LogPage.class, LogHighlightStop.class, LogSearchResult.class,
            LogSearchMatch.class, LogFileMatchCount.class, LogSearchStopReason.class, LogViewerError.class,
            LogViewerException.Kind.class));

    private Frame parent;
    private LogViewerDialog dialog;

    public LogViewerClientPlugin(String name) {
        super(LogViewerServletInterface.PLUGIN_NAME);
    }

    @Override
    public String getPluginPointName() {
        return LogViewerServletInterface.PLUGIN_NAME;
    }

    @Override
    public void start() {
        parent = PlatformUI.MIRTH_FRAME;

        // The Administrator's XStream builds only the classes it is told it may: these, and no other
        // class of this package. Without LogViewerError every failure would arrive as an anonymous one.
        List<String> names = new ArrayList<>();
        for (Class<?> type : DESERIALIZED) {
            names.add(type.getName());
        }
        ObjectXMLSerializer.getInstance().allowTypes(names, Collections.emptyList(), Collections.emptyList());

        ImageIcon icon = new ImageIcon(Frame.class.getResource("images/page_white_text.png"));

        // The task name comes from the servlet interface because the server registers the
        // same constant as the permission's task name, which lets role-based access
        // control hide the entry from roles without View Log Files when the engine sets
        // the Other pane's visibility at login. The Other pane has no popup menu.
        parent.addTask(LogViewerServletInterface.TASK_VIEW, "View Log Files",
                "Browse, search and download the engine's log files.", "",
                icon, parent.otherPane, null, this);
    }

    @Override
    public void stop() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    @Override
    public void reset() {
    }

    /**
     * Called when the user clicks "View Log Files". Checked again here, because
     * the pane's visibility is decided once at login and a role-based controller
     * that had not loaded its permissions by then would have left it showing.
     */
    public void viewLogFiles() {
        if (!AuthorizationControllerFactory.getAuthorizationController()
                .checkTask(TASK_GROUP, LogViewerServletInterface.TASK_VIEW)) {
            JOptionPane.showMessageDialog(parent,
                    "Your role does not allow viewing log files. It needs the View Log Files permission.",
                    "View Log Files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (dialog == null || !dialog.isDisplayable()) {
            dialog = new LogViewerDialog(parent);
        } else {
            dialog.toFront();
            dialog.requestFocus();
        }
        dialog.setVisible(true);
    }
}
