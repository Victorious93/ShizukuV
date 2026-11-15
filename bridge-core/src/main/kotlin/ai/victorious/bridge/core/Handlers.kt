package ai.victorious.bridge.core

import java.io.File
import java.io.RandomAccessFile

/** Facts about the device supplied by the Android-side service (version, SELinux context, properties...). */
fun interface SystemInfo {
    fun snapshot(): Map<String, Any?>
}

class HandlerConfig(
    val shellPath: String = "/system/bin/sh",
    val settingsBin: String = "/system/bin/settings",
    val pmBin: String = "/system/bin/pm",
    val amBin: String = "/system/bin/am",
    val dumpsysBin: String = "/system/bin/dumpsys",
    val maxChunk: Int = 1024 * 1024,
    val maxListEntries: Int = 1000,
    val shellDefaultTimeoutMs: Long = 30_000,
    val shellMaxTimeoutMs: Long = 300_000,
    val symlinks: SymlinkProbe = NioSymlinkProbe,
)

class DefaultHandlers(
    private val system: SystemInfo,
    private val commands: CommandExecutor,
    private val cfg: HandlerConfig = HandlerConfig(),
    private val shellCommands: CommandExecutor = commands,
) : OperationHandlers {

    override fun execute(ctx: ExecContext, op: Operation, args: Args): Map<String, Any?> = when (op) {
        Operation.DIAGNOSTICS -> system.snapshot() + mapOf(
            "exec_mode" to ctx.execMode.id, "bridge_api_version" to BridgeIdentity.BRIDGE_API_VERSION,
        )
        Operation.SETTINGS_LIST -> mapOf(
            "settings" to SettingsPolicy.specs.map { "${it.namespace}/${it.key}:${if (it.writable) "rw" else "ro"}" },
        )
        Operation.SETTINGS_GET -> settingsGet(ctx, args)
        Operation.SETTINGS_PUT -> settingsPut(ctx, args)
        Operation.FILES_STAT -> filesStat(ctx, args)
        Operation.FILES_LIST -> filesList(ctx, args)
        Operation.FILES_READ -> filesRead(ctx, args)
        Operation.FILES_WRITE -> filesWrite(ctx, args)
        Operation.FILES_MKDIR -> filesMkdir(ctx, args)
        Operation.FILES_DELETE -> filesDelete(ctx, args)
        Operation.PACKAGES_LIST -> packagesList(ctx, args)
        Operation.PACKAGES_INFO -> packagesInfo(ctx, args)
        Operation.PACKAGES_SET_ENABLED -> packagesSetEnabled(ctx, args)
        Operation.PACKAGES_FORCE_STOP -> packagesForceStop(ctx, args)
        Operation.SHELL_EXEC -> shellExec(ctx, args)
    }

    // ---- settings ----------------------------------------------------------------------------

    private fun settingsGet(ctx: ExecContext, a: Args): Map<String, Any?> {
        val ns = a.string("namespace", 16)
        val key = a.string("key", 64)
        if (SettingsPolicy.find(ns, key) == null) throw BridgeException(ErrorCode.UNSUPPORTED_OPERATION, "setting not supported")
        val out = commands.run(listOf(cfg.settingsBin, "get", ns!!, key!!), 10_000, ctx.cancel)
        requireRan(out)
        val v = out.stdout.trim()
        return mapOf("value" to if (v == "null" || v.isEmpty()) null else v)
    }

    private fun settingsPut(ctx: ExecContext, a: Args): Map<String, Any?> {
        val ns = a.string("namespace", 16)
        val key = a.string("key", 64)
        val value = a.string("value", 32)
        SettingsPolicy.checkWrite(ns, key, value)?.let { throw BridgeException(it, "setting or value not allowed") }
        val out = commands.run(listOf(cfg.settingsBin, "put", ns!!, key!!, value!!), 10_000, ctx.cancel)
        requireRan(out)
        return emptyMap()
    }

    // ---- files -------------------------------------------------------------------------------

    private fun scope(ctx: ExecContext): PathScope {
        if (ctx.grant.fileRoots.isEmpty()) throw BridgeException(ErrorCode.PATH_OUT_OF_SCOPE, "no approved file locations")
        return PathScope(ctx.grant.fileRoots, symlinks = cfg.symlinks)
    }

    private inline fun <T> scoped(block: () -> T): T = try {
        block()
    } catch (e: PathDeniedException) {
        throw BridgeException(e.code, e.message ?: e.code.name)
    } catch (e: SecurityException) {
        throw BridgeException(ErrorCode.IO_ERROR, "permission denied by the operating system")
    }

    private fun filesStat(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val f = scope(ctx).resolveExisting(a.string("path")!!)
        mapOf("exists" to true, "is_dir" to f.isDirectory, "size" to f.length(), "mtime" to f.lastModified(),
            "can_read" to f.canRead(), "can_write" to f.canWrite())
    }

    private fun filesList(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val f = scope(ctx).resolveExisting(a.string("path")!!)
        if (!f.isDirectory) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "not a directory")
        val offset = a.long("offset", 0, 0, Int.MAX_VALUE.toLong()).toInt()
        val limit = a.long("limit", 200, 1, cfg.maxListEntries.toLong()).toInt()
        val names = (f.list() ?: throw BridgeException(ErrorCode.IO_ERROR, "cannot list")).sorted()
        val page = names.drop(offset).take(limit)
        val entries = page.map { n ->
            val c = File(f, n)
            // Symlink targets are reported as links and never followed or sized.
            val link = cfg.symlinks.isSymlink(c)
            "${if (link) "l" else if (c.isDirectory) "d" else "f"}\t${if (link) 0 else c.length()}\t$n"
        }
        mapOf("entries" to entries, "total" to names.size, "has_more" to (offset + page.size < names.size))
    }

    private fun filesRead(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val f = scope(ctx).resolveExisting(a.string("path")!!)
        if (!f.isFile) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "not a regular file")
        val offset = a.long("offset", 0, 0, Long.MAX_VALUE)
        val length = a.long("length", cfg.maxChunk.toLong(), 1, cfg.maxChunk.toLong()).toInt()
        if (ctx.cancel.isCancelled) throw BridgeException(ErrorCode.CANCELLED, "cancelled")
        try {
            RandomAccessFile(f, "r").use { raf ->
                val size = raf.length()
                if (offset >= size) return@scoped mapOf("data" to ByteArray(0), "eof" to true, "size" to size)
                raf.seek(offset)
                val n = minOf(length.toLong(), size - offset).toInt()
                val buf = ByteArray(n)
                raf.readFully(buf)
                mapOf("data" to buf, "eof" to (offset + n >= size), "size" to size)
            }
        } catch (e: java.io.IOException) {
            throw BridgeException(ErrorCode.IO_ERROR, e.javaClass.simpleName)
        }
    }

    private fun filesWrite(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val path = a.string("path")!!
        val data = a.bytes("data", cfg.maxChunk)
        val offset = a.long("offset", 0, 0, Long.MAX_VALUE)
        val truncate = a.bool("truncate", false)
        val createOnly = a.bool("create_only", false)
        val f = scope(ctx).resolveForCreate(path)
        if (f.isDirectory) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "is a directory")
        if (createOnly && f.exists()) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "already exists")
        if (ctx.cancel.isCancelled) throw BridgeException(ErrorCode.CANCELLED, "cancelled")
        try {
            RandomAccessFile(f, "rw").use { raf ->
                if (truncate) raf.setLength(0)
                if (offset > raf.length()) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "offset beyond end")
                raf.seek(offset)
                raf.write(data)
            }
        } catch (e: java.io.IOException) {
            throw BridgeException(ErrorCode.IO_ERROR, e.javaClass.simpleName)
        }
        mapOf("written" to data.size)
    }

    private fun filesMkdir(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val f = scope(ctx).resolveForCreate(a.string("path")!!)
        if (f.exists()) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "already exists")
        if (!f.mkdir()) throw BridgeException(ErrorCode.IO_ERROR, "mkdir failed")
        emptyMap()
    }

    private fun filesDelete(ctx: ExecContext, a: Args): Map<String, Any?> = scoped {
        val s = scope(ctx)
        val f = s.resolveExisting(a.string("path")!!)
        if (s.effectiveRoots.contains(f.path)) throw BridgeException(ErrorCode.PATH_OUT_OF_SCOPE, "cannot delete an approved root")
        if (f.isDirectory && (f.list()?.isNotEmpty() == true)) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "directory not empty (recursive delete is not offered)")
        if (!f.delete()) throw BridgeException(ErrorCode.IO_ERROR, "delete failed")
        emptyMap()
    }

    // ---- packages ----------------------------------------------------------------------------

    private fun userArg(a: Args): String = a.long("user", 0, 0, 100000).toString()

    private fun pkgArg(a: Args, mustNotBeProtected: Boolean): String {
        val p = a.string("package", 255)
        if (!PackagePolicy.isValidName(p)) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "invalid package name")
        if (mustNotBeProtected && PackagePolicy.isProtected(p!!)) throw BridgeException(ErrorCode.DENIED, "protected package")
        return p!!
    }

    private fun packagesList(ctx: ExecContext, a: Args): Map<String, Any?> {
        val filter = a.string("filter", 16, required = false) ?: "all"
        val flag = when (filter) {
            "all" -> null; "user" -> "-3"; "system" -> "-s"; "enabled" -> "-e"; "disabled" -> "-d"
            else -> throw BridgeException(ErrorCode.INVALID_ARGUMENT, "bad filter")
        }
        val argv = mutableListOf(cfg.pmBin, "list", "packages")
        if (flag != null) argv += flag
        argv += listOf("--user", userArg(a))
        val out = commands.run(argv, 20_000, ctx.cancel)
        requireRan(out)
        val names = out.stdout.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { PackagePolicy.isValidName(it) }.sorted().toList()
        return mapOf("packages" to names, "truncated" to out.stdoutTruncated)
    }

    private fun packagesInfo(ctx: ExecContext, a: Args): Map<String, Any?> {
        val pkg = pkgArg(a, mustNotBeProtected = false)
        val out = commands.run(listOf(cfg.dumpsysBin, "package", pkg), 20_000, ctx.cancel)
        requireRan(out)
        val wanted = listOf("versionCode", "versionName", "firstInstallTime", "lastUpdateTime", "installerPackageName", "userId", "enabled")
        val info = LinkedHashMap<String, Any?>()
        out.stdout.lineSequence().map { it.trim() }.forEach { line ->
            for (k in wanted) {
                if (!info.containsKey(k) && line.startsWith("$k=")) info[k] = line.substringAfter('=').substringBefore(' ').take(128)
            }
        }
        if (info.isEmpty()) throw BridgeException(ErrorCode.NOT_FOUND, "package not found")
        return info + ("package" to pkg)
    }

    private fun packagesSetEnabled(ctx: ExecContext, a: Args): Map<String, Any?> {
        val pkg = pkgArg(a, mustNotBeProtected = true)
        val enabled = a.bool("enabled", true)
        val argv = if (enabled) listOf(cfg.pmBin, "enable", "--user", userArg(a), pkg)
        else listOf(cfg.pmBin, "disable-user", "--user", userArg(a), pkg)
        requireRan(commands.run(argv, 20_000, ctx.cancel))
        return emptyMap()
    }

    private fun packagesForceStop(ctx: ExecContext, a: Args): Map<String, Any?> {
        val pkg = pkgArg(a, mustNotBeProtected = true)
        requireRan(commands.run(listOf(cfg.amBin, "force-stop", "--user", userArg(a), pkg), 20_000, ctx.cancel))
        return emptyMap()
    }

    // ---- shell -------------------------------------------------------------------------------

    private fun shellExec(ctx: ExecContext, a: Args): Map<String, Any?> {
        val command = a.string("command", 8192)!!
        val timeout = a.long("timeout_ms", cfg.shellDefaultTimeoutMs, 100, cfg.shellMaxTimeoutMs)
        val out = shellCommands.run(listOf(cfg.shellPath, "-c", command), timeout, ctx.cancel)
        if (out.startFailure != null) throw BridgeException(ErrorCode.EXEC_FAILED, "could not start shell")
        if (out.cancelled) throw BridgeException(ErrorCode.CANCELLED, "cancelled")
        if (out.timedOut) throw BridgeException(ErrorCode.TIMEOUT, "command timed out")
        return mapOf(
            "exit_code" to out.exitCode, "stdout" to out.stdout, "stderr" to out.stderr,
            "stdout_truncated" to out.stdoutTruncated, "stderr_truncated" to out.stderrTruncated,
        )
    }

    private fun requireRan(out: ProcessOutcome) {
        if (out.startFailure != null) throw BridgeException(ErrorCode.EXEC_FAILED, "system tool unavailable")
        if (out.cancelled) throw BridgeException(ErrorCode.CANCELLED, "cancelled")
        if (out.timedOut) throw BridgeException(ErrorCode.TIMEOUT, "system tool timed out")
        if (out.exitCode != 0) throw BridgeException(ErrorCode.EXEC_FAILED, "system tool failed (exit ${out.exitCode})")
    }
}
