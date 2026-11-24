package lab.recovery.live

/** Main-thread registration lease. Late callbacks from a closed lease cannot reach a later session. */
internal class LocationSubscription(
    private val source: LocationSource,
    private val precision: Precision,
) {
    private var started = false
    private var closed = false

    fun currentIssue(): LiveIssue? =
        if (source.precision() != precision) LiveIssue.PERMISSION_CHANGED else source.issue(precision)

    fun open(fix: (RawFix) -> Unit, issue: (LiveIssue) -> Unit) {
        if (started || closed) return
        val problem = currentIssue()
        if (problem != null) {
            issue(problem)
            return
        }
        started = true
        source.register(precision, { if (!closed) fix(it) }, { if (!closed) issue(it) })
    }

    fun close() {
        if (closed) return
        closed = true
        if (started) source.unregister()
    }
}
