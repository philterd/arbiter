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

import org.apache.lucene.store.BufferedIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A Lucene {@link Directory} that encrypts every file it writes with AES-256 in CTR mode (#7), so the embedded search
 * index, which holds documents' unredacted text, is unreadable without Philter Workspace's key.
 *
 * <p>Each file starts with a 4-byte marker and a random 16-byte IV; the rest is the encrypted content, the same length
 * as the plaintext. CTR lets any position be decrypted without reading what comes before it, which Lucene's random
 * reads need. Lengths and file pointers are reported in plaintext terms, and the checksum Lucene writes in each file's
 * footer covers the plaintext, so Lucene's own integrity checks run unchanged.
 *
 * <p>CTR gives confidentiality, not authentication: Lucene's checksums catch accidental corruption, not deliberate
 * tampering by someone who can write the index files. The index is rebuildable from the database, which stays the
 * record.
 */
public final class EncryptedDirectory extends FilterDirectory {

    static final byte[] MAGIC = "PWE1".getBytes(StandardCharsets.US_ASCII);
    static final int IV_BYTES = 16;
    static final int HEADER_BYTES = MAGIC.length + IV_BYTES;
    private static final String TRANSFORM = "AES/CTR/NoPadding";
    private static final BigInteger COUNTER_SPACE = BigInteger.ONE.shiftLeft(128);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedDirectory(final Directory in, final SecretKey key) {
        super(in);
        this.key = key;
    }

    @Override
    public IndexOutput createOutput(final String name, final IOContext context) throws IOException {
        return new EncryptedIndexOutput(in.createOutput(name, context));
    }

    @Override
    public IndexOutput createTempOutput(final String prefix, final String suffix, final IOContext context)
            throws IOException {
        return new EncryptedIndexOutput(in.createTempOutput(prefix, suffix, context));
    }

    @Override
    public IndexInput openInput(final String name, final IOContext context) throws IOException {
        final IndexInput raw = in.openInput(name, context);
        try {
            if (raw.length() < HEADER_BYTES) {
                throw new IOException("Not an encrypted index file (too short): " + name);
            }
            final byte[] magic = new byte[MAGIC.length];
            raw.readBytes(magic, 0, magic.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Not an encrypted index file: " + name);
            }
            final byte[] iv = new byte[IV_BYTES];
            raw.readBytes(iv, 0, IV_BYTES);
            return new EncryptedIndexInput("EncryptedIndexInput(" + name + ")", raw, iv, key, 0,
                    raw.length() - HEADER_BYTES);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    @Override
    public long fileLength(final String name) throws IOException {
        return Math.max(0, in.fileLength(name) - HEADER_BYTES);
    }

    /** The IV for the block at {@code position}: the file's IV plus the block number, as a 128-bit counter. */
    static byte[] ivAt(final byte[] iv, final long position) {
        final BigInteger counter = new BigInteger(1, iv).add(BigInteger.valueOf(position / 16)).mod(COUNTER_SPACE);
        final byte[] raw = counter.toByteArray();
        final byte[] out = new byte[IV_BYTES];
        final int copy = Math.min(raw.length, IV_BYTES);
        System.arraycopy(raw, raw.length - copy, out, IV_BYTES - copy, copy);
        return out;
    }

    private final class EncryptedIndexOutput extends IndexOutput {

        private final IndexOutput out;
        private final Cipher cipher;
        private final CRC32 crc = new CRC32();
        private final byte[] one = new byte[1];
        private final byte[] oneOut = new byte[1];
        private byte[] scratch = new byte[8192];
        private long written;

        EncryptedIndexOutput(final IndexOutput out) throws IOException {
            super("EncryptedIndexOutput(" + out + ")", out.getName());
            this.out = out;
            final byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            try {
                cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            } catch (GeneralSecurityException e) {
                out.close();
                throw new IOException("Could not start encrypting " + out.getName(), e);
            }
            out.writeBytes(MAGIC, 0, MAGIC.length);
            out.writeBytes(iv, 0, IV_BYTES);
        }

        @Override
        public void writeByte(final byte b) throws IOException {
            one[0] = b;
            crc.update(b);
            encrypt(one, 0, 1, oneOut);
            out.writeByte(oneOut[0]);
            written++;
        }

        @Override
        public void writeBytes(final byte[] b, final int offset, final int length) throws IOException {
            crc.update(b, offset, length);
            if (scratch.length < length) {
                scratch = new byte[Math.max(length, scratch.length * 2)];
            }
            encrypt(b, offset, length, scratch);
            out.writeBytes(scratch, 0, length);
            written += length;
        }

        private void encrypt(final byte[] b, final int offset, final int length, final byte[] dest) throws IOException {
            try {
                // CTR is a stream mode: each update returns exactly as many bytes as it was given.
                final int produced = cipher.update(b, offset, length, dest, 0);
                if (produced != length) {
                    throw new IOException("The cipher returned " + produced + " bytes for " + length);
                }
            } catch (ShortBufferException e) {
                throw new IOException(e);
            }
        }

        @Override
        public long getFilePointer() {
            return written;
        }

        @Override
        public long getChecksum() {
            return crc.getValue();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** Reads and decrypts any range of an encrypted file; a slice or clone shares the file, not its position. */
    private static final class EncryptedIndexInput extends BufferedIndexInput {

        private IndexInput raw;
        private final byte[] iv;
        private final SecretKey key;
        /** Where this input starts in the file's plaintext, for a slice, and how long it is. */
        private final long start;
        private final long length;
        private boolean clone;
        private Cipher cipher;
        private byte[] scratch = new byte[0];

        EncryptedIndexInput(final String description, final IndexInput raw, final byte[] iv, final SecretKey key,
                            final long start, final long length) {
            super(description);
            this.raw = raw;
            this.iv = iv;
            this.key = key;
            this.start = start;
            this.length = length;
        }

        @Override
        protected void readInternal(final ByteBuffer b) throws IOException {
            final long position = start + getFilePointer();
            final int count = b.remaining();
            if (getFilePointer() + count > length) {
                throw new java.io.EOFException("Read past the end of " + this);
            }
            if (scratch.length < count) {
                scratch = new byte[count];
            }
            raw.seek(HEADER_BYTES + position);
            raw.readBytes(scratch, 0, count);
            try {
                if (cipher == null) {
                    cipher = Cipher.getInstance(TRANSFORM);
                }
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivAt(iv, position)));
                final int skip = (int) (position % 16);
                if (skip > 0) {
                    cipher.update(new byte[skip]);
                }
                final byte[] plain = cipher.update(scratch, 0, count);
                b.put(plain, 0, count);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not decrypt " + this, e);
            }
        }

        @Override
        protected void seekInternal(final long pos) throws IOException {
            if (pos > length) {
                throw new java.io.EOFException("Seek past the end of " + this);
            }
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public IndexInput slice(final String description, final long offset, final long sliceLength) throws IOException {
            if (offset < 0 || sliceLength < 0 || offset + sliceLength > length) {
                throw new IllegalArgumentException("Slice " + description + " is out of bounds of " + this);
            }
            final EncryptedIndexInput slice = new EncryptedIndexInput(getFullSliceDescription(description),
                    raw.clone(), iv, key, start + offset, sliceLength);
            slice.clone = true;
            return slice;
        }

        @Override
        public EncryptedIndexInput clone() {
            final EncryptedIndexInput c = (EncryptedIndexInput) super.clone();
            c.raw = raw.clone();
            c.cipher = null;
            c.scratch = new byte[0];
            c.clone = true;
            return c;
        }

        @Override
        public void close() throws IOException {
            // Clones and slices share the original's file, which only the original closes.
            if (!clone) {
                raw.close();
            }
        }
    }
}
