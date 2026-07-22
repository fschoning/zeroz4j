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

import com.zeroz4j.api.store.TenantResolver;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

/**
 * CDI producer for injecting request-scoped EclipseStore {@link EmbeddedStorageManager} instances.
 *
 * <p>Uses {@link TenantResolver} (if available) to determine the current tenant ID and fetches the matching
 * storage manager from {@link TenantStorageProvider}.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>Tenant Resolution:</b> Checks CDI {@link Instance} of {@link TenantResolver}. If unsatisfied or returns null, defaults to {@code "default"}.</li>
 *   <li><b>Scope:</b> Produced method {@link #getStorageManager()} is {@code @RequestScoped}.</li>
 * </ul>
 */
public class EclipseStoreProducer {

    @Inject
    Instance<TenantResolver> tenantResolver;

    @Inject
    TenantStorageProvider storageProvider;

    /**
     * Resolves the current tenant ID and produces the corresponding {@link EmbeddedStorageManager} for the request.
     *
     * @return active {@link EmbeddedStorageManager} instance for the resolved tenant
     *
     * <p><b>Under the hood:</b> Checks {@code tenantResolver.isUnsatisfied()}. Resolves tenant ID. Invokes {@code storageProvider.getStorageManager(tenantId)}.</p>
     */
    @Produces
    @RequestScoped
    public EmbeddedStorageManager getStorageManager() {
        String tenantId = "default";
        if (!tenantResolver.isUnsatisfied()) {
            tenantId = tenantResolver.get().resolveTenant();
            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = "default";
            }
        }
        return storageProvider.getStorageManager(tenantId);
    }
}
