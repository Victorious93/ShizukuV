package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Triple<File, File, PathScope> {
        val root = tmp.newFolder("root").canonicalFile
        val outside = tmp.newFolder("outside").canonicalFile
        File(root, "a.txt").writeText("hello")
        File(outside, "secret.txt").writeText("nope")
        // Deny list must not interfere with the temp dir.
        return Triple(root, outside, PathScope(listOf(root.path), hardDeny = listOf("/proc")))
    }

    private fun code(block: () -> Unit): ErrorCode? = try { block(); null } catch (e: PathDeniedException) { e.code }

    @Test fun allowsFileInsideRoot() {
        val (root, _, s) = setup()
        assertEquals(File(root, "a.txt").path, s.resolveExisting(File(root, "a.txt").path).path)
    }

    @Test fun rejectsParentTraversalEvenWhenItStaysInside() {
        val (root, _, s) = setup()
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting("${root.path}/sub/../a.txt") })
    }

    @Test fun rejectsTraversalOutOfRoot() {
        val (root, outside, s) = setup()
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting("${root.path}/../outside/secret.txt") })
    }

    @Test fun rejectsAbsolutePathOutsideRoots() {
        val (_, outside, s) = setup()
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting(File(outside, "secret.txt").path) })
    }

    @Test fun rejectsSiblingWithSharedPrefix() {
        val (root, _, s) = setup()
        val sib = File(root.parentFile, root.name + "-evil").apply { mkdir() }
        File(sib, "x").writeText("x")
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting(File(sib, "x").path) })
    }

    @Test fun rejectsLeafSymlinkPointingOutside() {
        val (root, outside, s) = setup()
        Files.createSymbolicLink(File(root, "link").toPath(), File(outside, "secret.txt").toPath())
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting(File(root, "link").path) })
    }

    @Test fun rejectsDirectorySymlinkEscape() {
        val (root, outside, s) = setup()
        Files.createSymbolicLink(File(root, "dirlink").toPath(), outside.toPath())
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting("${root.path}/dirlink/secret.txt") })
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveForCreate("${root.path}/dirlink/new.txt") })
    }

    @Test fun rejectsDanglingAndInScopeLeafSymlinksForWrite() {
        val (root, _, s) = setup()
        Files.createSymbolicLink(File(root, "dangling").toPath(), File("/nonexistent/target").toPath())
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveForCreate("${root.path}/dangling") })
        Files.createSymbolicLink(File(root, "inlink").toPath(), File(root, "a.txt").toPath())
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveForCreate("${root.path}/inlink") })
    }

    @Test fun rejectsNulRelativeEmptyAndOverlong() {
        val (root, _, s) = setup()
        assertEquals(ErrorCode.INVALID_ARGUMENT, code { s.resolveExisting("${root.path}/a\u0000.txt") })
        assertEquals(ErrorCode.INVALID_ARGUMENT, code { s.resolveExisting("a.txt") })
        assertEquals(ErrorCode.INVALID_ARGUMENT, code { s.resolveExisting("") })
        assertEquals(ErrorCode.INVALID_ARGUMENT, code { s.resolveExisting("/" + "a".repeat(5000)) })
    }

    @Test fun missingFileIsNotFoundInsideScope() {
        val (root, _, s) = setup()
        assertEquals(ErrorCode.NOT_FOUND, code { s.resolveExisting("${root.path}/missing") })
    }

    @Test fun createRequiresExistingParentInsideScope() {
        val (root, _, s) = setup()
        assertEquals(File(root, "new.txt").path, s.resolveForCreate("${root.path}/new.txt").path)
        assertEquals(ErrorCode.NOT_FOUND, code { s.resolveForCreate("${root.path}/nodir/new.txt") })
    }

    @Test fun hardDenyWinsOverGrantedRoot() {
        val dir = tmp.newFolder("protected").canonicalFile
        File(dir, "f").writeText("x")
        val s = PathScope(listOf(dir.parentFile.path), hardDeny = listOf(dir.path))
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting(File(dir, "f").path) })
    }

    @Test fun defaultHardDenyBlocksOtherAppsPrivateData() {
        // A grant asking for /data/data (or a parent covering it) yields no effective root at all.
        val s = PathScope(listOf("/data/data", "/data/user/0", "/", "/proc"))
        assertTrue(s.effectiveRoots.isEmpty())
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, code { s.resolveExisting("/data/data/com.other.app/databases/x.db") })
    }

    @Test fun rootsWithTraversalAreDropped() {
        val s = PathScope(listOf("/sdcard/../data/data"))
        assertTrue(s.effectiveRoots.isEmpty())
    }
}
