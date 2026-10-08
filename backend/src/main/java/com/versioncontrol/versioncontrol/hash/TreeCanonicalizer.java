package com.versioncontrol.versioncontrol.hash;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.validation.TreeEntryNameValidator;

/** Canonical, domain-separated encoding for tree contents. All integers are big-endian. */
public final class TreeCanonicalizer {
    private static final byte[] PREFIX = "VC-TREE\0v1".getBytes(StandardCharsets.US_ASCII);

    private TreeCanonicalizer() { }

    /**
     * Produces the v1 tree encoding. The prefix keeps tree bytes in a separate namespace from
     * raw blobs and commit encodings, preventing cross-kind hash ambiguity.
     */
    public static byte[] canonicalize(Collection<TreeHashInput> entries) {
        if (entries == null) throw new IllegalArgumentException("entries are required");

        List<EncodedEntry> encoded = new ArrayList<>(entries.size());
        Set<String> names = new HashSet<>();
        for (TreeHashInput entry : entries) {
            if (entry == null || entry.name() == null || entry.type() == null) {
                throw new IllegalArgumentException("tree entry, name, and type are required");
            }
            String normalizedName = Normalizer.normalize(entry.name(), Normalizer.Form.NFC);
            if (!TreeEntryNameValidator.isValidName(normalizedName)) {
                throw new IllegalArgumentException("tree entry name must be a valid path segment");
            }
            if (!names.add(normalizedName)) {
                throw new IllegalArgumentException("tree entry names must be unique after NFC normalization");
            }
            byte[] nameBytes = normalizedName.getBytes(StandardCharsets.UTF_8);
            byte[] target = Sha256Hasher.hashBytesFromHex(entry.targetHash());
            encoded.add(new EncodedEntry(nameBytes, entry.type(), target));
        }
        encoded.sort((left, right) -> compareUnsigned(left.nameBytes(), right.nameBytes()));

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(PREFIX);
            out.writeInt(encoded.size()); // fixed-width uint32 representation; Java limits collections to int size.
            for (EncodedEntry entry : encoded) {
                out.writeByte(entry.type() == TreeEntryType.FILE ? 0x01 : 0x02);
                out.writeInt(entry.nameBytes().length);
                out.write(entry.nameBytes());
                out.write(entry.targetHash()); // raw 32-byte SHA-256 value, not hexadecimal text.
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory tree encoding failed", impossible);
        }
    }

    public static String hash(Collection<TreeHashInput> entries) {
        return Sha256Hasher.hashBytes(canonicalize(entries));
    }

    private static int compareUnsigned(byte[] first, byte[] second) {
        int common = Math.min(first.length, second.length);
        for (int i = 0; i < common; i++) {
            int difference = Byte.toUnsignedInt(first[i]) - Byte.toUnsignedInt(second[i]);
            if (difference != 0) return difference;
        }
        return Integer.compare(first.length, second.length);
    }

    private record EncodedEntry(byte[] nameBytes, TreeEntryType type, byte[] targetHash) { }
}
