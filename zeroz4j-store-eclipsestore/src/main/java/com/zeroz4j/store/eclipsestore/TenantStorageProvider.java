/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.store.eclipsestore;

import com.zeroz4j.api.store.DataRootProvider;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application-scoped CDI manager for multi-tenant EclipseStore {@link EmbeddedStorageManager} storage engines.
 *
 * <p>Initializes and maintains separate persistent EclipseStore engines per tenant under subdirectories of {@code zeroz4j.store.path}.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>Root Initialization:</b> Uses {@link DataRootProvider} (if available) to initialize default tenant root objects via {@code createDefaultRoot(tenantId)}.</li>
 *   <li><b>Lazy Instantiation:</b> Uses {@link ConcurrentHashMap#computeIfAbsent} to lazily spin up storage engines on demand.</li>
 *   <li><b>Graceful Teardown:</b> {@link #shutdownAll()} shuts down all active tenant storage managers on bean destruction ({@code @PreDestroy}).</li>
 * </ul>
 */
@ApplicationScoped
public class TenantStorageProvider {

    private static final Logger LOG = Logger.getLogger(TenantStorageProvider.class.getName());

    @Inject
    @ConfigProperty(name = "zeroz4j.store.path", defaultValue = "./data")
    String basePath;

    @Inject
    Instance<DataRootProvider> dataRootProvider;

    private final ConcurrentHashMap<String, EmbeddedStorageManager> storageManagers = new ConcurrentHashMap<>();

    /**
     * Lazily gets or creates the EclipseStore {@link EmbeddedStorageManager} for a specific tenant.
     *
     * @param tenantId the tenant identifier string
     * @return active {@link EmbeddedStorageManager} instance for the specified tenant
     * @throws IllegalArgumentException if {@code tenantId} is null or empty
     *
     * <p><b>Under the hood:</b> Checks {@code dataRootProvider}. Instantiates root object. Invokes {@code EmbeddedStorage.start(root, Paths.get(basePath, tenantId))}.</p>
     */
    public EmbeddedStorageManager getStorageManager(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) {
            throw new IllegalArgumentException("Tenant ID must not be null or empty");
        }

        return storageManagers.computeIfAbsent(tenantId, tid -> {
            LOG.info("[zeroz4j-store] Starting EclipseStore for tenant: " + tid);
            Object root = null;
            if (!dataRootProvider.isUnsatisfied()) {
                root = dataRootProvider.get().createDefaultRoot(tid);
            }
            if (root == null) {
                root = new Object(); // Fallback
            }
            
            EmbeddedStorageManager manager = EmbeddedStorage.start(root, Paths.get(basePath, tid));
            return manager;
        });
    }

    /**
     * Shuts down all active tenant storage managers gracefully upon application teardown.
     *
     * <p><b>Under the hood:</b> Iterates through {@code storageManagers.values()} and calls {@code manager.shutdown()}.</p>
     */
    @PreDestroy
    public void shutdownAll() {
        storageManagers.values().forEach(manager -> {
            if (manager != null) {
                try {
                    manager.shutdown();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "[zeroz4j-store] Error shutting down EclipseStore: " + e.getMessage(), e);
                }
            }
        });
        storageManagers.clear();
    }
}
