package com.versioncontrol.versioncontrol.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Sha256HasherTests {
    @Test
    void hashesKnownByteAndUtf8Vectors() {
        assertThat(Sha256Hasher.hashBytes(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256Hasher.hashUtf8("hello")).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(Sha256Hasher.hashUtf8("Hello")).isEqualTo("185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969");
        assertThat(Sha256Hasher.hashUtf8("你好")).isEqualTo("670d9743542cae3ea7ebe36af56bd53648b0a1126162e78d81a32934a711302e");
        assertThat(Sha256Hasher.hashBytes(new byte[] {0, 1, 2, (byte) 0xff}))
                .isEqualTo("3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56");
    }

    @Test
    void hashesExactBytesWithoutTextNormalizationOrWhitespaceChanges() {
        assertThat(Sha256Hasher.hashUtf8("Hello")).isNotEqualTo(Sha256Hasher.hashUtf8("hello"));
        assertThat(Sha256Hasher.hashUtf8("Hello")).isNotEqualTo(Sha256Hasher.hashUtf8("Hello\n"));
        assertThat(Sha256Hasher.hashUtf8(" x ")).isNotEqualTo(Sha256Hasher.hashUtf8("x"));
        assertThat(Sha256Hasher.hashUtf8("é"))
                .isNotEqualTo(Sha256Hasher.hashBytes("e\u0301".getBytes(StandardCharsets.UTF_8)));
        byte[] exact = {0, (byte) 0xff, 10, 13};
        assertThat(Sha256Hasher.hashBytes(exact)).isEqualTo(Sha256Hasher.hashBytes(exact.clone()));
        assertThat(Sha256Hasher.hashBytes(exact)).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsNullBytesTextAndMalformedHashValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> Sha256Hasher.hashBytes(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Sha256Hasher.hashUtf8(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Sha256Hasher.hashBytesFromHex("A".repeat(64)));
        assertThat(Sha256Hasher.hashBytesFromHex("a".repeat(64))).hasSize(32);
    }
}
