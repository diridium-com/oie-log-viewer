// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

/**
 * The Administrator's XStream refuses any class it was not told it may build, and the refusal shows
 * only at run time, as a failed request. So the list the plugin allows must hold every class of this
 * plugin that an answer can contain, and nothing else: this walks every answer type's fields.
 */
class LogViewerClientPluginTypesTest {

    @Test
    void theAllowedClassesAreExactlyThoseTheAnswersHold() {
        Set<Class<?>> reachable = new HashSet<>();
        for (Method method : LogViewerServletInterface.class.getMethods()) {
            // The download is a stream of the file's bytes, not XML.
            if (method.getReturnType() != Response.class) {
                walk(method.getGenericReturnType(), reachable);
            }
        }
        // The body of every error the servlet sends.
        walk(LogViewerError.class, reachable);

        assertEquals(reachable, new HashSet<>(LogViewerClientPlugin.DESERIALIZED));
    }

    private static void walk(Type type, Set<Class<?>> found) {
        if (type instanceof ParameterizedType) {
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                walk(argument, found);
            }
            walk(((ParameterizedType) type).getRawType(), found);
        } else if (type instanceof GenericArrayType) {
            walk(((GenericArrayType) type).getGenericComponentType(), found);
        } else if (type instanceof Class) {
            Class<?> c = (Class<?>) type;
            if (c.isArray()) {
                walk(c.getComponentType(), found);
            } else if (c.getName().startsWith("com.diridium.logviewer.") && found.add(c)) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        walk(field.getGenericType(), found);
                    }
                }
            }
        }
    }
}
