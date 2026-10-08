// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.Properties;

import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;

/**
 * Server-side lifecycle hook whose only real job is to publish the plugin's
 * two permissions to the engine.
 *
 * <p>Registered via {@code <serverClasses>} in {@code plugin.xml}. At startup
 * the engine hands every service plugin's {@link ExtensionPermission}s to the
 * active authorization controller. Role-based controllers use them to decide
 * who may call this plugin's operations; without them an operation has no
 * permission mapping and typically falls through to allowed for any
 * authenticated user. The stock controller ignores them, so on installs
 * without role-based access control every administrator can use the plugin.</p>
 */
public class LogViewerServicePlugin implements ServicePlugin {

    @Override
    public String getPluginPointName() {
        return LogViewerServletInterface.PLUGIN_NAME;
    }

    /**
     * The extension name must match the name the servlet passes to
     * {@code MirthServlet}: operations reach the authorization controller as
     * {@code "<extensionName>#<opName>"}, so a mismatch registers a key
     * nothing looks up. Operation names come from reflecting over the servlet
     * interface, so an operation added later cannot ship unregistered.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        ExtensionPermission view = new ExtensionPermission(
                LogViewerServletInterface.PLUGIN_NAME,
                LogViewerServletInterface.PERMISSION_VIEW,
                "Allows listing, reading and searching the engine's log files. Log files are "
                        + "server-wide and can hold patient data from any channel; roles limited to "
                        + "specific channels are refused.",
                OperationUtil.getOperationNamesForPermission(LogViewerServletInterface.PERMISSION_VIEW,
                        LogViewerServletInterface.class),
                new String[] {LogViewerServletInterface.TASK_VIEW});

        ExtensionPermission download = new ExtensionPermission(
                LogViewerServletInterface.PLUGIN_NAME,
                LogViewerServletInterface.PERMISSION_DOWNLOAD,
                "Allows downloading whole log files, including rotated archives, as they are on disk.",
                OperationUtil.getOperationNamesForPermission(LogViewerServletInterface.PERMISSION_DOWNLOAD,
                        LogViewerServletInterface.class),
                new String[] {LogViewerServletInterface.TASK_DOWNLOAD});

        return new ExtensionPermission[] {view, download};
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void init(Properties properties) {
    }

    @Override
    public void update(Properties properties) {
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }
}
