/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.repository.WorkspaceRepository;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.config.RepositoryConfigurationSource;

import java.util.Collection;
import java.util.List;

public class SqliteRepositoryConfigurationExtension extends RepositoryConfigurationExtensionSupport {

    /** Bean name of the {@link SqliteDocumentStore} the repositories use. */
    public static final String STORE_BEAN = "sqliteDocumentStore";

    @Override
    public String getModuleName() {
        return "SQLite";
    }

    @Override
    protected String getModulePrefix() {
        return "sqlite";
    }

    @Override
    public String getRepositoryFactoryBeanClassName() {
        return SqliteRepositoryFactoryBean.class.getName();
    }

    @Override
    protected Collection<Class<?>> getIdentifyingTypes() {
        return List.of(WorkspaceRepository.class);
    }

    @Override
    public void postProcess(final BeanDefinitionBuilder builder, final RepositoryConfigurationSource source) {
        builder.addPropertyReference("store", STORE_BEAN);
    }
}
