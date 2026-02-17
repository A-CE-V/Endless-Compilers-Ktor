package org.endless.model

/**
 * Holds the output of a single decompilation attempt.
 *
 * [source]   — The decompiled Java source, or empty string on failure.
 * [warnings] — Non-fatal messages reported by the decompiler.
 * [errors]   — Fatal messages that caused partial or complete failure.
 */
data class DecompileResult(
    val source: String,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/** Typed outcome returned from every route handler. */
sealed class DecompileOutcome {
    data class Success(
        val result: DecompileResult,
        val decompiler: String,
        val elapsedMs: Long
    ) : DecompileOutcome()

    data class Failure(
        val errorCode: String,
        val message: String,
        val decompiler: String = "none"
    ) : DecompileOutcome()
}

/** Extension: turn an outcome into the JSON map the route will respond with. */
fun DecompileOutcome.toResponseMap(): Map<String, Any?> = when (this) {
    is DecompileOutcome.Success -> mapOf(
        "status"       to "success",
        "decompiler"   to decompiler,
        "source"       to result.source,
        "warnings"     to result.warnings,
        "errors"       to result.errors,
        "processingMs" to elapsedMs
    )
    is DecompileOutcome.Failure -> mapOf(
        "status"     to "error",
        "error"      to errorCode,
        "message"    to message,
        "decompiler" to decompiler
    )
}
