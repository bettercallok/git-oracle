package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2: deleteDirectory used Files.walk, which FOLLOWS symlinks to directories.
 * The tree being deleted is a clone of an untrusted repository, so a repo that
 * merely commits
 *
 *     ln -s /etc evil
 *
 * had that link descended into and everything underneath handed to
 * File::delete during ordinary cleanup. Whether anything was actually destroyed
 * came down to filesystem permissions, not to this code.
 *
 * These use a real temp filesystem rather than mocks, because the whole
 * question is what the filesystem does with a link.
 */
class WorkspaceDeletionTest {

    private void deleteDirectory(Path path) throws Exception {
        TestRunnerController controller = new TestRunnerController();
        ReflectionTestUtils.invokeMethod(controller, "deleteDirectory", path);
    }

    @Test
    void deletesAnOrdinaryTree(@TempDir Path tmp) throws Exception {
        Path workspace = tmp.resolve("job-1");
        Files.createDirectories(workspace.resolve("src/deep"));
        Files.writeString(workspace.resolve("src/deep/a.txt"), "x");
        Files.writeString(workspace.resolve("README.md"), "y");

        deleteDirectory(workspace);

        assertThat(Files.exists(workspace)).isFalse();
    }

    @Test
    void doesNotFollowASymlinkOutOfTheWorkspace(@TempDir Path tmp) throws Exception {
        // The payload: something valuable outside the tree being deleted.
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Path precious = outside.resolve("precious.conf");
        Files.writeString(precious, "DO NOT DELETE");

        Path workspace = tmp.resolve("job-2");
        Files.createDirectories(workspace);
        Files.createSymbolicLink(workspace.resolve("evil"), outside);

        deleteDirectory(workspace);

        // The workspace and the link are gone...
        assertThat(Files.exists(workspace)).isFalse();
        // ...but the link's TARGET is untouched. This is the whole finding.
        assertThat(Files.exists(precious)).isTrue();
        assertThat(Files.readString(precious)).isEqualTo("DO NOT DELETE");
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void removesTheSymlinkItselfNotJustItsTarget(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("target.txt");
        Files.writeString(target, "keep me");

        Path workspace = tmp.resolve("job-3");
        Files.createDirectories(workspace);
        Path link = workspace.resolve("link.txt");
        Files.createSymbolicLink(link, target);

        deleteDirectory(workspace);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void aDanglingSymlinkDoesNotAbortCleanup(@TempDir Path tmp) throws Exception {
        // git checkouts routinely contain links to paths that do not exist on
        // this machine; that must not leave the workspace behind.
        Path workspace = tmp.resolve("job-4");
        Files.createDirectories(workspace);
        Files.createSymbolicLink(workspace.resolve("dangling"), tmp.resolve("nope/missing"));
        Files.writeString(workspace.resolve("real.txt"), "x");

        deleteDirectory(workspace);

        assertThat(Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aMissingWorkspaceIsNotAnError(@TempDir Path tmp) throws Exception {
        deleteDirectory(tmp.resolve("never-existed"));
        // No exception is the assertion.
    }

    @Test
    void deletingAPathThatIsItselfASymlinkDoesNotTouchTheTarget(@TempDir Path tmp) throws Exception {
        Path realDir = tmp.resolve("real");
        Files.createDirectories(realDir);
        Files.writeString(realDir.resolve("keep.txt"), "keep");

        Path linkedWorkspace = tmp.resolve("linked-workspace");
        Files.createSymbolicLink(linkedWorkspace, realDir);

        deleteDirectory(linkedWorkspace);

        assertThat(Files.exists(realDir.resolve("keep.txt"))).isTrue();
    }
}
