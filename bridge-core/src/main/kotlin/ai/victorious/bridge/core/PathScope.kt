package ai.victorious.bridge.core

import java.io.File
import java.io.IOException

class PathDeniedException(val code: ErrorCode, message: String) : Exception(message)

/** Tells whether a path's final component is a symbolic link without following it. */
fun interface SymlinkProbe {
    fun isSymlink(file: File): Boolean
}

/** Default probe for the JVM (tests). The service installs an `android.system.Os.lstat` based probe. */
object NioSymlinkProbe : SymlinkProbe {
    override fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())
}

/**
 * Enforces file-path scope at the privileged boundary.
 *
 * The check is done on the *resolved* path, never on the string the caller supplied:
 *  1. reject NUL bytes, relative paths and any `..` segment outright (no normalisation tricks);
 *  2. resolve every existing ancestor through symlinks ([File.getCanonicalFile]);
 *  3. require the result to lie inside a granted root *and* outside the hard-deny list;
 *  4. for the final component, refuse symlinks (so a link inside an allowed root cannot point out of it).
 *
 * Residual risk, documented in docs/ARCHITECTURE.md: between this check and the later `open`, a party
 * with write access to an intermediate directory could swap it for a symlink. The service narrows the
 * window by opening with O_NOFOLLOW on the leaf and by only operating on the resolved path.
 */
class PathScope(
    roots: List<String>,
    private val hardDeny: List<String> = DEFAULT_HARD_DENY,
    private val symlinks: SymlinkProbe = NioSymlinkProbe,
) {
    companion object {
        /** Prefixes that can never be granted, whatever the grant says. Includes other apps' private data. */
        val DEFAULT_HARD_DENY: List<String> = listOf(
            "/proc", "/sys", "/dev", "/data/data", "/data/user", "/data/user_de", "/data/system",
            "/data/misc", "/data/adb", "/data/vendor", "/data/app", "/data/dalvik-cache", "/vendor", "/system",
        )
        const val MAX_PATH_LENGTH = 4096
    }

    private val canonicalRoots: List<String> = roots.mapNotNull { r ->
        try {
            if (!r.startsWith("/") || r.contains('\u0000') || r.split('/').any { it == ".." }) null
            else File(r).canonicalPath.trimEnd('/').ifEmpty { "/" }
        } catch (_: IOException) {
            null
        }
    }.filter { root -> hardDeny.none { isUnder(root, it) } && root != "/" }

    val effectiveRoots: List<String> get() = canonicalRoots

    /** Resolves [path] for an operation on an existing entry. */
    fun resolveExisting(path: String, followLeafSymlink: Boolean = false): File {
        val f = validateSyntax(path)
        if (!followLeafSymlink && symlinks.isSymlink(f)) {
            throw PathDeniedException(ErrorCode.PATH_OUT_OF_SCOPE, "symbolic links are not followed")
        }
        // Scope first, existence second: answering NOT_FOUND for an out-of-scope path would let a caller
        // probe which files exist in locations it was never granted.
        val resolved = enforce(f.canonicalFile)
        if (!resolved.exists()) throw PathDeniedException(ErrorCode.NOT_FOUND, "no such file")
        return resolved
    }

    /** Resolves [path] for creating or writing a leaf whose parent must already exist. */
    fun resolveForCreate(path: String): File {
        val f = validateSyntax(path)
        val parent = f.parentFile ?: throw PathDeniedException(ErrorCode.INVALID_ARGUMENT, "no parent")
        val name = f.name
        if (name.isEmpty() || name == "." || name == "..") throw PathDeniedException(ErrorCode.INVALID_ARGUMENT, "bad name")
        val canonParent = parent.canonicalFile
        if (!canonParent.isDirectory) throw PathDeniedException(ErrorCode.NOT_FOUND, "parent directory missing")
        val resolved = File(canonParent, name)
        if (symlinks.isSymlink(resolved)) {
            throw PathDeniedException(ErrorCode.PATH_OUT_OF_SCOPE, "symbolic links are not followed")
        }
        return enforce(resolved)
    }

    private fun validateSyntax(path: String): File {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) throw PathDeniedException(ErrorCode.INVALID_ARGUMENT, "bad path length")
        if (path.contains('\u0000')) throw PathDeniedException(ErrorCode.INVALID_ARGUMENT, "NUL in path")
        if (!path.startsWith("/")) throw PathDeniedException(ErrorCode.INVALID_ARGUMENT, "path must be absolute")
        if (path.split('/').any { it == ".." }) throw PathDeniedException(ErrorCode.PATH_OUT_OF_SCOPE, "parent traversal")
        return File(path)
    }

    private fun enforce(resolved: File): File {
        val p = resolved.path
        if (hardDeny.any { isUnder(p, it) }) throw PathDeniedException(ErrorCode.PATH_OUT_OF_SCOPE, "protected location")
        if (canonicalRoots.none { isUnder(p, it) }) throw PathDeniedException(ErrorCode.PATH_OUT_OF_SCOPE, "outside approved locations")
        return resolved
    }

    private fun isUnder(path: String, prefix: String): Boolean {
        val pre = prefix.trimEnd('/')
        return path == pre || path.startsWith("$pre/")
    }
}
