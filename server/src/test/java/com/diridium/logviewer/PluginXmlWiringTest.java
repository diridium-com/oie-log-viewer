// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.mirth.connect.plugins.ServicePlugin;

/**
 * Checks plugin.xml against the code. A service plugin missing from
 * {@code <serverClasses>} never registers its permissions, and a renamed
 * servlet leaves an API provider pointing at nothing; the compiler sees
 * neither, and the engine only logs it at startup.
 */
class PluginXmlWiringTest {

    /** Maven runs the server module's tests from its own directory. */
    private static final Path PLUGIN_XML = Path.of("..", "package", "resources", "plugin.xml");

    private static Element pluginXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(PLUGIN_XML.toFile());
        return document.getDocumentElement();
    }

    private static List<String> texts(Element root, String parent, String child) {
        List<String> values = new ArrayList<>();
        NodeList parents = root.getElementsByTagName(parent);
        for (int i = 0; i < parents.getLength(); i++) {
            NodeList children = ((Element) parents.item(i)).getElementsByTagName(child);
            for (int j = 0; j < children.getLength(); j++) {
                values.add(children.item(j).getTextContent().trim());
            }
        }
        return values;
    }

    @Test
    void nameIsTheOneOperationsAreKeyedBy() throws Exception {
        assertEquals(LogViewerServletInterface.PLUGIN_NAME,
                pluginXml().getElementsByTagName("name").item(0).getTextContent().trim());
    }

    @Test
    void serverClassesNameTheServicePlugin() throws Exception {
        List<String> serverClasses = texts(pluginXml(), "serverClasses", "string");
        assertEquals(List.of(LogViewerServicePlugin.class.getName()), serverClasses);
        assertTrue(ServicePlugin.class.isAssignableFrom(Class.forName(serverClasses.get(0))));
    }

    @Test
    void apiProvidersNameTheServletAndItsInterface() throws Exception {
        NodeList providers = pluginXml().getElementsByTagName("apiProvider");
        String servletInterface = null;
        String servlet = null;
        for (int i = 0; i < providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            if ("SERVLET_INTERFACE".equals(provider.getAttribute("type"))) {
                servletInterface = provider.getAttribute("name");
            } else if ("SERVER_CLASS".equals(provider.getAttribute("type"))) {
                servlet = provider.getAttribute("name");
            }
        }
        assertEquals(LogViewerServletInterface.class.getName(), servletInterface);
        assertEquals(LogViewerServlet.class.getName(), servlet);
        // Loaded without initializing, so the servlet's static service is not built.
        Class<?> servletClass = Class.forName(servlet, false, getClass().getClassLoader());
        assertTrue(LogViewerServletInterface.class.isAssignableFrom(servletClass));
    }
}
