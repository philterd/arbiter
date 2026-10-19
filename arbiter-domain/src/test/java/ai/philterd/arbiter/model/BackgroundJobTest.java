/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundJobTest {

    @Test
    void openSearchAndElasticsearchTypesMapToDataImport() {
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT,
                BackgroundJob.categoryFor(BackgroundJob.TYPE_OPENSEARCH_INGEST));
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT,
                BackgroundJob.categoryFor(BackgroundJob.TYPE_ELASTICSEARCH_INGEST));
    }

    @Test
    void unknownAndNullTypesFallBackToOther() {
        assertEquals(BackgroundJob.CATEGORY_OTHER, BackgroundJob.categoryFor(null));
        assertEquals(BackgroundJob.CATEGORY_OTHER, BackgroundJob.categoryFor(""));
        assertEquals(BackgroundJob.CATEGORY_OTHER, BackgroundJob.categoryFor("CLEANUP"));
    }

    @Test
    void categoryLabelsAreHumanReadable() {
        assertEquals("Data Import Jobs", BackgroundJob.categoryLabel(BackgroundJob.CATEGORY_DATA_IMPORT));
        assertEquals("Other Jobs", BackgroundJob.categoryLabel(BackgroundJob.CATEGORY_OTHER));
        assertEquals("Other Jobs", BackgroundJob.categoryLabel(null));
        assertEquals("Other Jobs", BackgroundJob.categoryLabel("FUTURE_KIND"));
    }

    @Test
    void getCategoryDelegatesToCategoryFor() {
        final BackgroundJob job = new BackgroundJob();
        assertEquals(BackgroundJob.CATEGORY_OTHER, job.getCategory());

        job.setType(BackgroundJob.TYPE_OPENSEARCH_INGEST);
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, job.getCategory());

        job.setType(BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, job.getCategory());

        job.setType("UNKNOWN");
        assertEquals(BackgroundJob.CATEGORY_OTHER, job.getCategory());
    }

    @Test
    void failureMessagesNullSetterDoesNotNullField() {
        final BackgroundJob job = new BackgroundJob();
        job.setFailureMessages(null);
        assertEquals(0, job.getFailureMessages().size());
        job.getFailureMessages().add("ok"); // proves it's mutable, not a sentinel
        assertEquals(1, job.getFailureMessages().size());
    }

    @Test
    void runningImportIndexCoversEveryDataImportType() throws Exception {
        final Set<String> dataImportTypes = new TreeSet<>();
        for (Field f : BackgroundJob.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("TYPE_")) {
                final String type = (String) f.get(null);
                if (BackgroundJob.CATEGORY_DATA_IMPORT.equals(BackgroundJob.categoryFor(type))) {
                    dataImportTypes.add(type);
                }
            }
        }
        assertTrue(dataImportTypes.contains(BackgroundJob.TYPE_S3_INGEST));

        final String filter = BackgroundJob.class.getAnnotation(CompoundIndex.class).partialFilter();
        final String list = filter.substring(filter.indexOf('[') + 1, filter.indexOf(']'));
        final Set<String> indexed = Arrays.stream(list.split(","))
                .map(v -> v.trim().replace("'", ""))
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(dataImportTypes, indexed);
    }
}
