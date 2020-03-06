/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.jpms;

import com.google.common.collect.ImmutableList;
import org.gradle.api.file.FileCollection;
import org.gradle.api.jpms.ModularClasspathHandling;
import org.gradle.api.specs.Spec;
import org.gradle.cache.internal.FileContentCache;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.internal.serialize.BaseSerializerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class JavaModuleDetector {

    private final Spec<? super File> classpathFilter = this::isNotModule;
    private final Spec<? super File> modulePathFilter = this::isModule;

    private static final String MODULE_INFO_SOURCE_FILE = "module-info.java";
    private static final String MODULE_INFO_CLASS_FILE = "module-info.class";
    private static final String AUTOMATIC_MODULE_NAME_ATTRIBUTE = "Automatic-Module-Name";

    private final FileContentCache<Boolean> cache;

    public JavaModuleDetector(FileContentCacheFactory cacheFactory) {
        this.cache = cacheFactory.newCache("java-modules", 20000, new ModuleInfoLocator(), new BaseSerializerFactory().getSerializerFor(Boolean.class));
    }

    public ImmutableList<File> inferClasspath(boolean forModule, ModularClasspathHandling modularClasspathHandling, FileCollection classpath) {
        if (classpath == null) {
            return ImmutableList.of();
        }
        if (!forModule) {
            return ImmutableList.copyOf(classpath);
        }
        if (modularClasspathHandling.getInferModulePath().get()) {
            return ImmutableList.copyOf(classpath.filter(classpathFilter));
        }
        return ImmutableList.copyOf(classpath);
    }

    public ImmutableList<File> inferModulePath(boolean forModule, ModularClasspathHandling modularClasspathHandling, FileCollection classpath) {
        if (classpath == null) {
            return ImmutableList.of();
        }
        if (!forModule) {
            return ImmutableList.of();
        }
        if (modularClasspathHandling.getInferModulePath().get()) {
            return ImmutableList.copyOf(classpath.filter(modulePathFilter));
        }
        return ImmutableList.of();
    }

    private boolean isModule(File file) {
        if (!file.exists()) {
            return false;
        }
        return cache.get(file);
    }

    private boolean isNotModule(File file) {
        if (!file.exists()) {
            return false;
        }
        return !isModule(file);
    }

    public static boolean isModuleSource(Iterable<File> sourcesRoots) {
        for (File srcFolder : sourcesRoots) {
            if (isModuleSourceFolder(srcFolder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModuleSourceFolder(File folder) {
        return new File(folder, MODULE_INFO_SOURCE_FILE).exists();
    }

    private static class ModuleInfoLocator implements FileContentCacheFactory.Calculator<Boolean> {

        @Override
        public Boolean calculate(File file, boolean isRegularFile) {
            if (isRegularFile) {
                return isJarFile(file) && isModuleJar(file);
            } else {
                return isModuleFolder(file);
            }
        }

        private boolean isJarFile(File file) {
            return file.getName().endsWith(".jar");
        }

        private boolean isModuleFolder(File folder) {
            return new File(folder, MODULE_INFO_CLASS_FILE).exists();
        }

        private boolean isModuleJar(File jarFile) {
            try (JarInputStream jarStream =  new JarInputStream(new FileInputStream(jarFile))) {
                if (containsAutomaticModuleName(jarStream)) {
                    return true;
                }
                ZipEntry next = jarStream.getNextEntry();
                while (next != null) {
                    if (next.getName().equals(MODULE_INFO_CLASS_FILE)) {
                        return true;
                    }
                    next = jarStream.getNextEntry();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return false;
        }

        private boolean containsAutomaticModuleName(JarInputStream jarStream) {
            return getAutomaticModuleName(jarStream.getManifest()) != null;
        }

        private String getAutomaticModuleName(Manifest manifest) {
            if (manifest == null) {
                return null;
            }
            return manifest.getMainAttributes().getValue(AUTOMATIC_MODULE_NAME_ATTRIBUTE);
        }
    }
}
