package com.versioncontrol.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.versioncontrol.exception.InvalidRequestException;

class DiscussionReplyParentValidationTests {
    @Test
    void rejectsSelfParentAndAllowsDistinctOrMissingParent() {
        assertThatThrownBy(() -> DiscussionReplyService.validateParentRelationship(42L, 42L))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("A reply cannot reference itself as its parent");
        assertThatCode(() -> DiscussionReplyService.validateParentRelationship(42L, 41L)).doesNotThrowAnyException();
        assertThatCode(() -> DiscussionReplyService.validateParentRelationship(42L, null)).doesNotThrowAnyException();
    }
}
