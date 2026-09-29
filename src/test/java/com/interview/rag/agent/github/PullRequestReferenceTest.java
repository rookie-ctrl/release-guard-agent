package com.interview.rag.agent.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class PullRequestReferenceTest {
    @Test
    void normalizesFilesLinkWithoutFollowingUserSuppliedUrls() {
        PullRequestReference reference = PullRequestReference.parse("https://github.com/rookie-ctrl/release-guard-agent/pull/12/files?diff=split#diff-test");
        assertThat(reference.url()).isEqualTo("https://github.com/rookie-ctrl/release-guard-agent/pull/12");
        assertThat(reference.apiPath()).isEqualTo("/repos/rookie-ctrl/release-guard-agent/pulls/12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://github.com/a/b/pull/1", "https://github.com.evil.test/a/b/pull/1",
            "https://github.com@evil.test/a/b/pull/1", "https://user@github.com/a/b/pull/1",
            "https://github.com:444/a/b/pull/1", "https://github.com/a/b/pull/0",
            "https://github.com/a/b/pull/999999999999999999", "https://github.com/a/../pull/1",
            "https://api.github.com/repos/a/b/pulls/1", "file:///a/b/pull/1"})
    void rejectsUntrustedOrMalformedLinks(String url) {
        assertThatThrownBy(() -> PullRequestReference.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
