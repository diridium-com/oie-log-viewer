// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.model.ExtensionPermission;

/**
 * Guards the registration contract between this plugin and the engine's
 * authorization controller. Every failure here would otherwise be silent: a
 * wrong extension name, an unregistered operation or a mismatched task name
 * leaves the plugin looking installed while a permission quietly fails to
 * apply.
 */
class LogViewerServicePluginTest {

    private static Map<String, ExtensionPermission> permissions() {
        Map<String, ExtensionPermission> byName = new HashMap<>();
        for (ExtensionPermission permission : new LogViewerServicePlugin().getExtensionPermissions()) {
            byName.put(permission.getDisplayName(), permission);
        }
        assertEquals(Set.of(LogViewerServletInterface.PERMISSION_VIEW, LogViewerServletInterface.PERMISSION_DOWNLOAD),
                byName.keySet());
        return byName;
    }

    @Test
    void extensionNameMatchesTheNameTheServletRegistersUnder() {
        for (ExtensionPermission permission : permissions().values()) {
            assertEquals(LogViewerServletInterface.PLUGIN_NAME, permission.getExtensionName());
        }
        assertEquals(LogViewerServletInterface.PLUGIN_NAME, new LogViewerServicePlugin().getPluginPointName());
    }

    @Test
    void eachOperationIsRegisteredUnderItsOwnPermission() {
        Map<String, Set<String>> annotated = new HashMap<>();
        for (Method method : LogViewerServletInterface.class.getMethods()) {
            MirthOperation operation = method.getAnnotation(MirthOperation.class);
            if (operation != null) {
                annotated.computeIfAbsent(operation.permission(), p -> new HashSet<>()).add(operation.name());
            }
        }
        assertEquals(Set.of("listFiles", "readPage", "search", "count"), annotated.get(LogViewerServletInterface.PERMISSION_VIEW));
        assertEquals(Set.of("download"), annotated.get(LogViewerServletInterface.PERMISSION_DOWNLOAD));
        assertEquals(2, annotated.size(), "no operation may name any other permission");

        Map<String, ExtensionPermission> registered = permissions();
        for (Map.Entry<String, Set<String>> entry : annotated.entrySet()) {
            assertEquals(entry.getValue(),
                    new HashSet<>(Arrays.asList(registered.get(entry.getKey()).getOperationNames())), entry.getKey());
        }
    }

    @Test
    void everyOperationIsAuditedAndAsynchronous() {
        for (Method method : LogViewerServletInterface.class.getMethods()) {
            MirthOperation operation = method.getAnnotation(MirthOperation.class);
            if (operation != null) {
                assertTrue(operation.auditable(), operation.name() + " must leave an audit event");
                assertEquals(ExecuteType.ASYNC, operation.type(), operation.name() + " must not block other requests");
            }
        }
    }

    @Test
    void eachPermissionGatesItsClientTask() {
        Map<String, ExtensionPermission> registered = permissions();
        assertArrayEquals(new String[] {LogViewerServletInterface.TASK_VIEW},
                registered.get(LogViewerServletInterface.PERMISSION_VIEW).getTaskNames());
        assertArrayEquals(new String[] {LogViewerServletInterface.TASK_DOWNLOAD},
                registered.get(LogViewerServletInterface.PERMISSION_DOWNLOAD).getTaskNames());
    }

    @Test
    void offersXmlBeforeJsonSoTheAdministratorKeepsCarriageReturns() {
        // The Swing proxy takes whichever format the server lists first, and its
        // JSON path normalizes every CR to LF (measured on 4.6.0). Matches the
        // order on every engine servlet interface.
        Produces produces = LogViewerServletInterface.class.getAnnotation(Produces.class);
        assertArrayEquals(new String[] {MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON}, produces.value());
    }

    @Test
    void downloadStreamsOctetsNotTheInterfacesJsonOrXml() throws Exception {
        // Measured on 4.6.0: without its own @Produces the stream still arrives
        // raw, but labelled application/json.
        Method download = LogViewerServletInterface.class.getMethod("download", String.class);
        // A Response, so a client can read the Content-Length the servlet sends.
        assertEquals(Response.class, download.getReturnType());
        Produces produces = download.getAnnotation(Produces.class);
        assertNotNull(produces);
        assertArrayEquals(new String[] {MediaType.APPLICATION_OCTET_STREAM}, produces.value());
    }
}
