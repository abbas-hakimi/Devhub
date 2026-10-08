package com.versioncontrol.versioncontrol.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.versioncontrol.entity.TreeEntryType;

class TreeCanonicalizerTests {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void emptyTreeHasStableDomainSeparatedCanonicalEncoding() {
        byte[] expected = ByteBuffer.allocate("VC-TREE\0v1".length() + 4)
                .put("VC-TREE\0v1".getBytes(StandardCharsets.US_ASCII)).putInt(0).array();
        assertThat(TreeCanonicalizer.canonicalize(List.of())).isEqualTo(expected);
        assertThat(TreeCanonicalizer.hash(List.of())).isEqualTo(TreeCanonicalizer.hash(List.of()));
    }

    @Test
    void entryOrderDoesNotAffectCanonicalBytesOrHash() {
        List<TreeHashInput> first = List.of(file("README.md", A), file("pom.xml", B), directory("src", C));
        List<TreeHashInput> second = List.of(directory("src", C), file("README.md", A), file("pom.xml", B));
        assertThat(TreeCanonicalizer.canonicalize(first)).isEqualTo(TreeCanonicalizer.canonicalize(second));
        assertThat(TreeCanonicalizer.hash(first)).isEqualTo(TreeCanonicalizer.hash(second));
    }

    @Test
    void encodingUsesUtf8UnsignedOrderingBigEndianLengthsAndRawHashBytes() {
        byte[] actual = TreeCanonicalizer.canonicalize(List.of(file("é", A), file("a", B)));
        ByteBuffer in = ByteBuffer.wrap(actual).order(ByteOrder.BIG_ENDIAN);
        byte[] prefix = new byte["VC-TREE\0v1".length()];
        in.get(prefix);
        assertThat(prefix).isEqualTo("VC-TREE\0v1".getBytes(StandardCharsets.US_ASCII));
        assertThat(in.getInt()).isEqualTo(2);
        assertThat(Byte.toUnsignedInt(in.get())).isEqualTo(0x01);
        assertThat(in.getInt()).isEqualTo(1);
        assertThat(in.get()).isEqualTo((byte) 'a');
        byte[] bHash = new byte[32]; in.get(bHash);
        assertThat(bHash).isEqualTo(Sha256Hasher.hashBytesFromHex(B));
        assertThat(Byte.toUnsignedInt(in.get())).isEqualTo(0x01);
        byte[] eName = "é".getBytes(StandardCharsets.UTF_8);
        assertThat(in.getInt()).isEqualTo(eName.length);
        byte[] readName = new byte[eName.length]; in.get(readName);
        assertThat(readName).isEqualTo(eName);
        byte[] aHash = new byte[32]; in.get(aHash);
        assertThat(aHash).isEqualTo(Sha256Hasher.hashBytesFromHex(A));
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void changingEveryEntryIdentityFieldChangesHash() {
        String original = TreeCanonicalizer.hash(List.of(file("name", A)));
        assertThat(TreeCanonicalizer.hash(List.of(file("other", A)))).isNotEqualTo(original);
        assertThat(TreeCanonicalizer.hash(List.of(directory("name", A)))).isNotEqualTo(original);
        assertThat(TreeCanonicalizer.hash(List.of(file("name", B)))).isNotEqualTo(original);
        assertThat(TreeCanonicalizer.hash(List.of(file("name", A), file("extra", B)))).isNotEqualTo(original);
        assertThat(TreeCanonicalizer.hash(List.of(directory("name", A))))
                .isNotEqualTo(TreeCanonicalizer.hash(List.of(directory("name", B))));
    }

    @Test
    void normalizesNamesToNfcButDoesNotNormalizeBlobContent() {
        assertThat(TreeCanonicalizer.hash(List.of(file("e\u0301", A))))
                .isEqualTo(TreeCanonicalizer.hash(List.of(file("é", A))));
        String composedContent = Sha256Hasher.hashUtf8("é");
        String decomposedContent = Sha256Hasher.hashUtf8("e\u0301");
        assertThat(composedContent).isNotEqualTo(decomposedContent);
        assertThat(TreeCanonicalizer.hash(List.of(file("name", composedContent))))
                .isNotEqualTo(TreeCanonicalizer.hash(List.of(file("name", decomposedContent))));
    }

    @Test
    void rejectsInvalidOrDuplicateNormalizedTreeEntries() {
        assertThatIllegalArgumentException().isThrownBy(() -> TreeCanonicalizer.canonicalize(null));
        assertThatIllegalArgumentException().isThrownBy(() -> TreeCanonicalizer.hash(List.of(file("../bad", A))));
        assertThatIllegalArgumentException().isThrownBy(() -> TreeCanonicalizer.hash(List.of(
                file("é", A), file("e\u0301", B))));
        assertThatIllegalArgumentException().isThrownBy(() -> TreeCanonicalizer.hash(List.of(new TreeHashInput("x", null, A))));
        assertThatIllegalArgumentException().isThrownBy(() -> TreeCanonicalizer.hash(List.of(new TreeHashInput("x", TreeEntryType.FILE, "bad"))));
    }

    private static TreeHashInput file(String name, String hash) { return new TreeHashInput(name, TreeEntryType.FILE, hash); }
    private static TreeHashInput directory(String name, String hash) { return new TreeHashInput(name, TreeEntryType.DIRECTORY, hash); }
}
