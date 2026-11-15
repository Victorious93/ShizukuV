package ai.victorious.bridge.core

/**
 * Removes secrets from any text before it is stored, shown or exported.
 * It is a safety net applied on top of the rule that secrets are never put into log events at all.
 */
object Redactor {
    const val MASK = "[redacted]"

    private val keyValue = Regex(
        """(?i)\b(pass(?:word|code|phrase)?|pairing[_ -]?code|pair[_ -]?code|token|secret|api[_-]?key|authorization|auth|cookie|private[_ -]?key|credential)s?\b\s*[:=]\s*("[^"]*"|'[^']*'|\S+)""",
    )
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val urlUserInfo = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@""")
    private val longToken = Regex("""\b[A-Za-z0-9+/_-]{32,}={0,2}\b""")
    private val pemBlock = Regex("""-----BEGIN [A-Z ]+-----[\s\S]*?(-----END [A-Z ]+-----|$)""")
    private val pairingDigits = Regex("""(?i)(pair\w*\D{0,20})\b\d{6}\b""")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s: String = text
        s = pemBlock.replace(s, MASK)
        s = urlUserInfo.replace(s) { "${it.groupValues[1]}$MASK@" }
        s = bearer.replace(s) { "${it.groupValues[1]} $MASK" }
        s = keyValue.replace(s) { "${it.groupValues[1]}=$MASK" }
        s = pairingDigits.replace(s) { "${it.groupValues[1]}$MASK" }
        s = longToken.replace(s, MASK)
        return s
    }

    /**
     * Reduces a command line to something safe to record: the executable name and the number of
     * arguments. Arguments are dropped entirely because they routinely carry secrets.
     */
    fun summarizeCommand(argv: List<String>): String {
        if (argv.isEmpty()) return "(empty)"
        val exe = argv[0].substringAfterLast('/').take(64)
        val extra = argv.size - 1
        return if (extra > 0) "$exe (+$extra args)" else exe
    }

    fun summarizeShell(command: String): String {
        val first = command.trim().split(Regex("""\s+""")).firstOrNull().orEmpty().substringAfterLast('/').take(64)
        return if (first.isEmpty()) "(empty)" else "$first (shell command, arguments omitted)"
    }
}
