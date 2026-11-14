package com.gitoracle.githubbot;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A fix no test suite verified is opened as a draft, so it can't be merged unread. */
class DraftPullRequestTest {

    @Test
    void anUnverifiedFixIsOpenedAsADraft() throws IOException {
        GHRepository repo = mock(GHRepository.class);
        GHPullRequest draft = mock(GHPullRequest.class);
        when(repo.createPullRequest("t", "head", "main", "body", true, true)).thenReturn(draft);

        assertThat(GitHubController.openPullRequest(repo, "t", "head", "main", "body", true, "job")).isSameAs(draft);
        verify(repo, never()).createPullRequest(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aVerifiedFixIsOpenedNormally() throws IOException {
        GHRepository repo = mock(GHRepository.class);
        GHPullRequest pr = mock(GHPullRequest.class);
        when(repo.createPullRequest("t", "head", "main", "body")).thenReturn(pr);

        assertThat(GitHubController.openPullRequest(repo, "t", "head", "main", "body", false, "job")).isSameAs(pr);
        verify(repo, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(),
            anyBoolean(), anyBoolean());
    }

    @Test
    void whereGitHubRefusesDraftsTheFixIsStillOpened() throws IOException {
        GHRepository repo = mock(GHRepository.class);
        GHPullRequest pr = mock(GHPullRequest.class);
        when(repo.createPullRequest("t", "head", "main", "body", true, true))
            .thenThrow(new IOException("422 Draft pull requests are not supported in this repository"));
        when(repo.createPullRequest("t", "head", "main", "body")).thenReturn(pr);

        assertThat(GitHubController.openPullRequest(repo, "t", "head", "main", "body", true, "job")).isSameAs(pr);
    }
}
