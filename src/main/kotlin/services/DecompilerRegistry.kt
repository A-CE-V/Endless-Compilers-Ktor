package org.endless.services

import org.endless.model.DecompileOutcome
import org.endless.model.DecompileResult
import java.io.File

// ── Magic-byte constants ──────────────────────────────────────────────────────
private val CLASS_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
private val JAR_MAGIC   = byteArrayOf(0x50, 0x4B, 0x03, 0x04)   // PK\x03\x04

// ── Size limits ───────────────────────────────────────────────────────────────
private const val MAX_CLASS_BYTES = 10L * 1024 * 1024   //  10 MB per .class
private const val MAX_JAR_BYTES   = 200L * 1024 * 1024  // 200 MB per JAR

/**
 * Central registry that maps mode strings → [DecompilerAdapter] and performs
 * all input validation before any decompiler is invoked.
 */
class DecompilerRegistry(adapters: List<DecompilerAdapter>) {

    private val byName: Map<String, DecompilerAdapter> =
        adapters.associateBy { it.name.lowercase() }

    val availableDecompilers: Set<String> get() = byName.keys
    val jarCapableDecompilers: Set<String>
        get() = byName.filter { it.value.supportsJar }.keys

    fun resolve(mode: String): DecompilerAdapter? = byName[mode.lowercase()]

    // ── Validation helpers ────────────────────────────────────────────────────

    fun validateClassBytes(bytes: ByteArray, mode: String): DecompileOutcome.Failure? {
        if (bytes.size > MAX_CLASS_BYTES)
            return DecompileOutcome.Failure("FILE_TOO_LARGE",
                "Class file exceeds ${MAX_CLASS_BYTES / 1024 / 1024} MB limit.", mode)
        if (!bytes.startsWith(CLASS_MAGIC))
            return DecompileOutcome.Failure("INVALID_CLASS_FILE",
                "Uploaded file does not start with 0xCAFEBABE — not a valid .class file.", mode)
        return null
    }

    fun validateJarBytes(bytes: ByteArray, mode: String): DecompileOutcome.Failure? {
        if (bytes.size > MAX_JAR_BYTES)
            return DecompileOutcome.Failure("FILE_TOO_LARGE",
                "JAR file exceeds ${MAX_JAR_BYTES / 1024 / 1024} MB limit.", mode)
        if (!bytes.startsWith(JAR_MAGIC))
            return DecompileOutcome.Failure("INVALID_JAR_FILE",
                "Uploaded file does not start with PK header — not a valid JAR/ZIP file.", mode)
        return null
    }

    fun validateMode(mode: String, requiresJar: Boolean = false): DecompileOutcome.Failure? {
        val adapter = resolve(mode) ?: return DecompileOutcome.Failure(
            "UNKNOWN_MODE",
            "Unknown decompiler '$mode'. Available: ${availableDecompilers.joinToString()}.", mode
        )
        if (requiresJar && !adapter.supportsJar)
            return DecompileOutcome.Failure(
                "JAR_NOT_SUPPORTED",
                "Decompiler '$mode' does not support JAR input. " +
                "JAR-capable decompilers: ${jarCapableDecompilers.joinToString()}.", mode
            )
        return null
    }

    // ── Timed invocation ─────────────────────────────────────────────────────

    fun runClass(mode: String, bytes: ByteArray, fileName: String): DecompileOutcome {
        val adapter = resolve(mode)!!           // caller must validate mode first
        val t0 = System.currentTimeMillis()
        val result = runCatching { adapter.decompileClass(bytes, fileName) }
            .getOrElse { ex ->
                return DecompileOutcome.Failure("DECOMPILE_FAILED",
                    ex.message ?: ex.javaClass.simpleName, mode)
            }
        return DecompileOutcome.Success(result, mode, System.currentTimeMillis() - t0)
    }

    fun runJar(mode: String, jar: File, outDir: File): DecompileOutcome {
        val adapter = resolve(mode)!!
        val t0 = System.currentTimeMillis()
        val result = runCatching { adapter.decompileJar(jar, outDir) }
            .getOrElse { ex ->
                return DecompileOutcome.Failure("DECOMPILE_FAILED",
                    ex.message ?: ex.javaClass.simpleName, mode)
            }
        return DecompileOutcome.Success(result, mode, System.currentTimeMillis() - t0)
    }
}

// ── Tiny ByteArray utility ────────────────────────────────────────────────────
private fun ByteArray.startsWith(magic: ByteArray): Boolean {
    if (size < magic.size) return false
    return magic.indices.all { this[it] == magic[it] }
}
