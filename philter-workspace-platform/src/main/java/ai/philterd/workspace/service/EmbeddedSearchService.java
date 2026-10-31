/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.search.EncryptedDirectory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The full-text index in Philter Workspace itself ({@code philter-workspace.search.backend=embedded}, the default),
 * with Apache Lucene, needing no other service (#7).
 *
 * <p>The index is a single directory on local disk ({@code philter-workspace.search.index-dir}). It is read through
 * file channels rather than memory-mapped, so it does not take memory from the rest of the device, and every file in it
 * is encrypted with a key derived from {@code philter-workspace.crypto.secret} ({@link EncryptedDirectory}), since it
 * holds documents' unredacted text. The database stays the record: the index can be deleted and rebuilt from it.
 *
 * <p>Only one Philter Workspace may use an index directory; a second instance on the same directory cannot write to
 * it. Deployments with several instances use the OpenSearch backend.
 */
public class EmbeddedSearchService implements DocumentSearchService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedSearchService.class);

    static final String ID = "id";
    static final String BATCH_ID = "batchId";
    static final String FILENAME = "filename";
    static final String STATUS = "status";
    static final String TEXT = "originalText";
    /** As the OpenSearch backend: up to 100 hits a page and 20 similar documents. */
    private static final int MAX_PAGE = 100;
    private static final int MAX_SIMILAR = 20;
    /** OpenSearch's default number of highlight fragments per document. */
    private static final int PASSAGES = 5;
    /** How far into a document highlighting looks, as OpenSearch's default {@code max_analyzed_offset}. */
    private static final int MAX_HIGHLIGHT_CHARS = 1_000_000;

    private final Path indexDirectory;
    private final SecretKey key;
    private final GeneralSettingsService settings;
    private final Analyzer analyzer = new StandardAnalyzer();

    private volatile Index index;

    private record Index(EncryptedDirectory directory, IndexWriter writer, SearcherManager searchers) {}

    public EmbeddedSearchService(final Path indexDirectory, final SecretKey key, final GeneralSettingsService settings) {
        this.indexDirectory = indexDirectory;
        this.key = key;
        this.settings = settings;
    }

    @Override
    public String backend() {
        return SearchBackendConfiguration.EMBEDDED;
    }

    /** The directory the index is stored in. */
    public Path indexDirectory() {
        return indexDirectory;
    }

    /** Opens the index on first use, so a deployment with search off never creates or locks it. */
    private Index open() throws IOException {
        Index i = index;
        if (i != null) return i;
        synchronized (this) {
            if (index == null) {
                Files.createDirectories(indexDirectory);
                final EncryptedDirectory directory = new EncryptedDirectory(new NIOFSDirectory(indexDirectory), key);
                try {
                    final IndexWriterConfig config = new IndexWriterConfig(analyzer)
                            .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                    final IndexWriter writer = new IndexWriter(directory, config);
                    index = new Index(directory, writer, new SearcherManager(writer, null));
                } catch (IOException | RuntimeException e) {
                    directory.close();
                    throw e;
                }
            }
            return index;
        }
    }

    private boolean enabled() {
        try {
            return settings.load().isFullTextSearchEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void indexDocument(final Document document) {
        if (document == null || document.getId() == null || !enabled()) return;
        try {
            final Index i = open();
            i.writer().updateDocument(new Term(ID, document.getId()), toLucene(document));
            commit(i);
        } catch (IOException | RuntimeException e) {
            // The id only: the document's text and filename can carry PII.
            log.warn("Embedded search could not index document {}: {}", document.getId(),
                    e.getClass().getSimpleName());
        }
    }

    /** Indexes many documents with one commit, for a rebuild. */
    public void indexDocuments(final List<Document> documents) throws IOException {
        if (!enabled()) return;
        final Index i = open();
        for (Document d : documents) {
            if (d != null && d.getId() != null) {
                i.writer().updateDocument(new Term(ID, d.getId()), toLucene(d));
            }
        }
        commit(i);
    }

    private static org.apache.lucene.document.Document toLucene(final Document document) {
        final org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
        doc.add(new StringField(ID, document.getId(), Field.Store.YES));
        doc.add(new StringField(BATCH_ID, nullToEmpty(document.getBatchId()), Field.Store.YES));
        doc.add(new StoredField(FILENAME, nullToEmpty(document.getFilename())));
        doc.add(new StoredField(STATUS, nullToEmpty(document.getStatus())));
        doc.add(new TextField(TEXT, nullToEmpty(document.getOriginalText()), Field.Store.YES));
        return doc;
    }

    private static void commit(final Index i) throws IOException {
        i.writer().commit();
        i.searchers().maybeRefresh();
    }

    @Override
    public boolean removeDocument(final String documentId) {
        if (documentId == null || !enabled()) return true;
        try {
            final Index i = open();
            i.writer().deleteDocuments(new Term(ID, documentId));
            commit(i);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Embedded search could not remove document {}; its indexed copy remains: {}", documentId,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public SearchResults search(final String query, final int from, final int size,
                                final Collection<String> allowedBatchIds) {
        final int safeFrom = Math.max(0, from);
        final int safeSize = Math.max(1, Math.min(MAX_PAGE, size));
        final SearchResults empty = new SearchResults(0, safeFrom, safeSize, List.of());
        if (query == null || query.isBlank() || !enabled()) return empty;
        if (allowedBatchIds != null && allowedBatchIds.isEmpty()) return empty;
        // A match over the analyzed text, as OpenSearch's match query; the query is never parsed as syntax.
        final Query match = new QueryBuilder(analyzer).createBooleanQuery(TEXT, query);
        if (match == null) return empty;
        final Query scoped = allowedBatchIds == null ? match : new BooleanQuery.Builder()
                .add(match, BooleanClause.Occur.MUST)
                .add(new TermInSetQuery(BATCH_ID, allowedBatchIds.stream().filter(id -> id != null && !id.isBlank())
                        .map(BytesRef::new).toList()), BooleanClause.Occur.FILTER)
                .build();
        IndexSearcher searcher = null;
        Index i = null;
        try {
            i = open();
            searcher = i.searchers().acquire();
            final long total = searcher.count(scoped);
            final TopDocs top = searcher.search(scoped, safeFrom + safeSize);
            final ScoreDoc[] page = Arrays.copyOfRange(top.scoreDocs, Math.min(safeFrom, top.scoreDocs.length),
                    top.scoreDocs.length);
            final SearchHighlights.Tags tags = SearchHighlights.Tags.fresh();
            final String separator = "[[arbHL_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_SEP]]";
            final String[] highlights = highlight(searcher, match, page, tags, separator);
            final List<SearchHit> hits = new ArrayList<>();
            for (int n = 0; n < page.length; n++) {
                final List<String> snippets = new ArrayList<>();
                if (highlights[n] != null && !highlights[n].isEmpty()) {
                    for (String passage : highlights[n].split(java.util.regex.Pattern.quote(separator))) {
                        snippets.add(SearchHighlights.sanitize(passage.strip(), tags.open(), tags.close()));
                    }
                }
                hits.add(hit(searcher, page[n].doc, List.copyOf(snippets)));
            }
            return new SearchResults(total, safeFrom, safeSize, List.copyOf(hits));
        } catch (IOException | RuntimeException e) {
            // Never the query: people search for PII.
            log.warn("Embedded search failed (query length {}): {}", query.length(), e.getClass().getSimpleName());
            return empty;
        } finally {
            release(i, searcher);
        }
    }

    /** Each document's passages, joined by {@code separator}, with matches between the request's sentinels. */
    private String[] highlight(final IndexSearcher searcher, final Query query, final ScoreDoc[] page,
                               final SearchHighlights.Tags tags, final String separator) throws IOException {
        if (page.length == 0) return new String[0];
        final UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                .withFormatter(new SentinelFormatter(tags, separator))
                .withMaxLength(MAX_HIGHLIGHT_CHARS)
                .build();
        final int[] docIds = Arrays.stream(page).mapToInt(d -> d.doc).toArray();
        final Map<String, String[]> byField = highlighter.highlightFields(new String[]{TEXT}, query, docIds,
                new int[]{PASSAGES});
        final String[] out = byField.get(TEXT);
        return out == null ? new String[page.length] : out;
    }

    /**
     * Each passage with matches between the request's sentinels, joined by a per-request separator that, like the
     * sentinels, document text cannot forge. Escaped afterwards.
     */
    private static final class SentinelFormatter extends PassageFormatter {
        private final SearchHighlights.Tags tags;
        private final String separator;

        SentinelFormatter(final SearchHighlights.Tags tags, final String separator) {
            this.tags = tags;
            this.separator = separator;
        }

        @Override
        public Object format(final Passage[] passages, final String content) {
            final List<String> out = new ArrayList<>();
            for (Passage p : passages) {
                final StringBuilder sb = new StringBuilder();
                int pos = p.getStartOffset();
                for (int m = 0; m < p.getNumMatches(); m++) {
                    final int start = Math.max(pos, p.getMatchStarts()[m]);
                    final int end = Math.min(p.getEndOffset(), p.getMatchEnds()[m]);
                    if (start >= end) continue;
                    sb.append(content, pos, start).append(tags.open()).append(content, start, end).append(tags.close());
                    pos = end;
                }
                sb.append(content, pos, p.getEndOffset());
                out.add(sb.toString().strip());
            }
            return String.join(separator, out);
        }
    }

    @Override
    public SearchResults findSimilar(final String documentId, final String batchId, final int size) {
        final int safeSize = Math.max(1, Math.min(MAX_SIMILAR, size));
        final SearchResults empty = new SearchResults(0, 0, size, List.of());
        if (documentId == null || documentId.isBlank() || !enabled()) return empty;
        IndexSearcher searcher = null;
        Index i = null;
        try {
            i = open();
            searcher = i.searchers().acquire();
            final TopDocs source = searcher.search(new TermQuery(new Term(ID, documentId)), 1);
            if (source.scoreDocs.length == 0) return empty;
            // As the OpenSearch backend's more_like_this: every term that occurs at least once, in a document
            // that occurs in at least one other.
            final MoreLikeThis mlt = new MoreLikeThis(searcher.getIndexReader());
            mlt.setAnalyzer(analyzer);
            mlt.setFieldNames(new String[]{TEXT});
            mlt.setMinTermFreq(1);
            mlt.setMinDocFreq(1);
            final BooleanQuery.Builder query = new BooleanQuery.Builder()
                    .add(mlt.like(source.scoreDocs[0].doc), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(ID, documentId)), BooleanClause.Occur.MUST_NOT);
            if (batchId != null && !batchId.isBlank()) {
                query.add(new TermQuery(new Term(BATCH_ID, batchId)), BooleanClause.Occur.FILTER);
            }
            final Query built = query.build();
            final long total = searcher.count(built);
            final TopDocs top = searcher.search(built, safeSize);
            final List<SearchHit> hits = new ArrayList<>();
            for (ScoreDoc d : top.scoreDocs) {
                hits.add(hit(searcher, d.doc, List.of()));
            }
            return new SearchResults(total, 0, safeSize, List.copyOf(hits));
        } catch (IOException | RuntimeException e) {
            log.warn("Embedded findSimilar failed for document {}: {}", documentId, e.getClass().getSimpleName());
            return empty;
        } finally {
            release(i, searcher);
        }
    }

    private static SearchHit hit(final IndexSearcher searcher, final int doc, final List<String> highlights)
            throws IOException {
        final org.apache.lucene.document.Document stored = searcher.storedFields().document(doc,
                java.util.Set.of(ID, BATCH_ID, FILENAME, STATUS));
        return new SearchHit(nullToEmpty(stored.get(ID)), nullToEmpty(stored.get(BATCH_ID)),
                nullToEmpty(stored.get(FILENAME)), nullToEmpty(stored.get(STATUS)), highlights);
    }

    private static void release(final Index i, final IndexSearcher searcher) {
        if (i != null && searcher != null) {
            try {
                i.searchers().release(searcher);
            } catch (IOException e) {
                log.debug("Could not release an embedded searcher: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Starts the index afresh: closes it, deletes its files, and creates an empty one. Unlike deleting its documents,
     * this also recovers an index that cannot be opened, such as one written under an earlier crypto secret or a
     * damaged one, which is why a rebuild starts here.
     */
    @Override
    public synchronized boolean clear() {
        if (!enabled()) return true;
        try {
            close();
            if (Files.isDirectory(indexDirectory)) {
                try (java.util.stream.Stream<Path> files = Files.list(indexDirectory)) {
                    for (Path f : files.toList()) {
                        if (Files.isRegularFile(f)) {
                            Files.delete(f);
                        }
                    }
                }
            }
            open();
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Embedded search could not empty the index: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public State probe() {
        if (!enabled()) return State.DISABLED;
        try {
            open();
            return State.REACHABLE;
        } catch (IOException | RuntimeException e) {
            log.debug("Embedded search index could not be opened: {}", e.getClass().getSimpleName());
            return State.UNREACHABLE;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        final Index i = index;
        index = null;
        if (i != null) {
            try {
                i.searchers().close();
                i.writer().close();
            } finally {
                i.directory().close();
            }
        }
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
