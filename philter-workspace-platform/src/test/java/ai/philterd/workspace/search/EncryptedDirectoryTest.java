/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The encrypting Lucene directory behind the embedded search index (#7). */
class EncryptedDirectoryTest {

    private static SecretKey key(final int seed) {
        final byte[] bytes = new byte[32];
        new Random(seed).nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private static Directory open(final Path dir, final SecretKey key) throws Exception {
        return new EncryptedDirectory(new NIOFSDirectory(dir), key);
    }

    @Test
    void anyRangeReadsBackAsWrittenAndLengthsArePlaintext(@TempDir final Path dir) throws Exception {
        final byte[] data = new byte[100_003];
        new Random(7).nextBytes(data);
        try (Directory d = open(dir, key(1))) {
            try (IndexOutput out = d.createOutput("f", IOContext.DEFAULT)) {
                out.writeByte(data[0]);
                out.writeBytes(data, 1, data.length - 1);
                assertEquals(data.length, out.getFilePointer());
                final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                assertEquals(crc.getValue(), out.getChecksum(), "the checksum covers the plaintext");
            }
            assertEquals(data.length, d.fileLength("f"));
            assertEquals(data.length + EncryptedDirectory.HEADER_BYTES, Files.size(dir.resolve("f")));
            try (IndexInput in = d.openInput("f", IOContext.DEFAULT)) {
                assertEquals(data.length, in.length());
                final Random r = new Random(11);
                for (int i = 0; i < 200; i++) {
                    final int from = r.nextInt(data.length);
                    final int len = r.nextInt(Math.min(5000, data.length - from) + 1);
                    final byte[] read = new byte[len];
                    in.seek(from);
                    in.readBytes(read, 0, len);
                    assertArrayEquals(Arrays.copyOfRange(data, from, from + len), read, "at " + from);
                }
                final IndexInput slice = in.slice("s", 12_345, 50_000);
                final IndexInput clone = slice.clone();
                clone.seek(17);
                slice.seek(49_999);
                assertEquals(data[12_345 + 17], clone.readByte(), "a clone keeps its own position");
                assertEquals(data[12_345 + 49_999], slice.readByte());
            }
        }
    }

    @Test
    void aLuceneIndexWorksThroughItAndHoldsNoPlaintext(@TempDir final Path dir) throws Exception {
        final String secret = "Jane Roe 123-45-6789 zebracorn";
        try (Directory d = open(dir, key(1))) {
            final IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setMaxBufferedDocs(10);
            try (IndexWriter writer = new IndexWriter(d, config)) {
                for (int i = 0; i < 200; i++) {
                    final Document doc = new Document();
                    doc.add(new StringField("id", "d" + i, Field.Store.YES));
                    doc.add(new TextField("text", i == 42 ? secret : "routine filing number " + i, Field.Store.YES));
                    writer.addDocument(doc);
                }
                writer.commit();
                writer.forceMerge(1);
                writer.commit();
            }
            try (DirectoryReader reader = DirectoryReader.open(d)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                assertEquals(200, reader.numDocs());
                final var hits = searcher.search(new TermQuery(new Term("text", "zebracorn")), 5);
                assertEquals(1, hits.totalHits.value);
                assertEquals(secret, searcher.storedFields().document(hits.scoreDocs[0].doc).get("text"));
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                final String raw = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("zebracorn") || raw.contains("Jane Roe") || raw.contains("routine filing"),
                        "plaintext in " + f.getFileName());
            }
        }
    }

    @Test
    void anotherKeyCannotReadTheIndex(@TempDir final Path dir) throws Exception {
        try (Directory d = open(dir, key(1));
             IndexWriter writer = new IndexWriter(d, new IndexWriterConfig(new StandardAnalyzer()))) {
            final Document doc = new Document();
            doc.add(new TextField("text", "hello", Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
        try (Directory wrong = open(dir, key(2))) {
            assertThrows(Exception.class, () -> DirectoryReader.open(wrong).close());
        }
        try (Directory plain = new NIOFSDirectory(dir)) {
            assertThrows(Exception.class, () -> DirectoryReader.open(plain).close());
        }
    }

    @Test
    void theCounterCarriesAcrossTheIv() {
        final byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 0xff);
        final byte[] next = EncryptedDirectory.ivAt(iv, 16);
        assertTrue(Arrays.equals(new byte[16], next), "wraps to zero after the largest counter");
        final byte[] zero = new byte[16];
        final byte[] block3 = EncryptedDirectory.ivAt(zero, 3 * 16 + 5);
        assertEquals(3, block3[15]);
    }
}
