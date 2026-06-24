/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.LocalDirectoryDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DestinationWriterTest {

    private final DestinationWriter writer = new DestinationWriter(mock(SymmetricCipher.class));

    private static LocalDirectoryDestination dest(final Path dir) {
        final LocalDirectoryDestination d = new LocalDirectoryDestination();
        d.setDirectoryPath(dir.toString());
        return d;
    }

    @Test
    void writeLocalBytesWritesContent(@TempDir final Path dir) throws Exception {
        final byte[] payload = "hello\nworld".getBytes(StandardCharsets.UTF_8);

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "out.jsonl", payload);

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getError());
        assertArrayEquals(payload, Files.readAllBytes(dir.resolve("out.jsonl")));
    }

    @Test
    void writeLocalFileStreamsContent(@TempDir final Path dir, @TempDir final Path src) throws Exception {
        // The streaming overload copies a source file (the temp export spool) into the destination.
        final Path source = src.resolve("spool.jsonl");
        final byte[] payload = "{\"text\":\"a\",\"spans\":[]}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source, payload);

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "out.jsonl", source);

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getError());
        assertArrayEquals(payload, Files.readAllBytes(dir.resolve("out.jsonl")));
    }

    @Test
    void writeLocalFileRefusesPathTraversal(@TempDir final Path dir, @TempDir final Path src) throws Exception {
        final Path source = src.resolve("spool.jsonl");
        Files.write(source, "x".getBytes(StandardCharsets.UTF_8));

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "../escaped.jsonl", source);

        assertFalse(result.isOk(), "a filename escaping the destination directory must be refused");
        assertFalse(Files.exists(dir.getParent().resolve("escaped.jsonl")));
    }

    @Test
    void writeLocalFailsWhenDirectoryMissing(@TempDir final Path dir) throws Exception {
        final Path source = dir.resolve("spool.jsonl");
        Files.write(source, "x".getBytes(StandardCharsets.UTF_8));
        final LocalDirectoryDestination missing = dest(dir.resolve("does-not-exist"));

        final DestinationWriter.Result result = writer.writeLocal(missing, "out.jsonl", source);

        assertFalse(result.isOk(), "writing to a non-existent directory must fail");
    }
}
