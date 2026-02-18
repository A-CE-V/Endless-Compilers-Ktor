package org.endless

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.endless.model.DecompileOutcome
import org.endless.model.toResponseMap
import org.endless.services.*

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// ── HMAC Auth ─────────────────────────────────────────────────────────────────

/**
 * Mirrors the Node.js verifyInternalKey middleware exactly:
 *
 *   hmac = HMAC-SHA256(INTERNAL_API_KEY)
 *   hmac.update(x-auth-timestamp)
 *   hmac.update(rawBody)          ← only when body is non-empty
 *   expected == x-auth-signature  ← hex digest
 *
 * Requires the DoubleReceive plugin to be installed so that calling
 * receive<ByteArray>() here does not consume the stream before
 * receiveMultipart() is called later in the route handler.
 *
 * Returns true when the request is authentic, false otherwise.
 * On false the caller must immediately return a 401 response.
 */
private suspend fun ApplicationCall.verifyHmacAuth(): Boolean {
    val secret    = System.getenv("INTERNAL_API_KEY")
    val signature = request.headers["x-auth-signature"]
    val timestamp = request.headers["x-auth-timestamp"]

    // ── 1. Presence check ─────────────────────────────────────────────────────
    if (secret.isNullOrBlank() || signature.isNullOrBlank() || timestamp.isNullOrBlank()) {
        application.log.warn("[Auth] Missing auth headers or INTERNAL_API_KEY env var is not set.")
        return false
    }

    // ── 2. Replay-attack guard (60-second window, same as Node.js) ────────────
    val ts = timestamp.toLongOrNull()
    if (ts == null || abs(System.currentTimeMillis() - ts) > 60_000L) {
        application.log.warn("[Auth] Request expired or malformed timestamp: $timestamp")
        return false
    }

    // ── 3. Buffer raw body (DoubleReceive lets us call this without consuming
    //       the stream that receiveMultipart() will need afterwards) ───────────
    val rawBody = receive<ByteArray>()

    // ── 4. Compute HMAC-SHA256(timestamp [+ rawBody]) ─────────────────────────
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        update(timestamp.toByteArray(Charsets.UTF_8))
        if (rawBody.isNotEmpty()) update(rawBody)
    }
    val calculated = mac.doFinal().joinToString("") { "%02x".format(it) }

    // ── 5. Constant-time-ish comparison (hex strings, same length always) ─────
    val matches = calculated == signature
    if (!matches) {
        application.log.warn(
            "[Auth] Signature mismatch — " +
            "received=${signature.take(6)}… calculated=${calculated.take(6)}…"
        )
    }
    return matches
}

/** Convenience: respond 401 and log — keeps route handlers tidy. */
private suspend fun ApplicationCall.respondUnauthorized(reason: String) {
    application.log.warn("[Auth] Rejected: $reason")
    respond(
        HttpStatusCode.Unauthorized,
        mapOf(
            "status"  to "error",
            "error"   to "UNAUTHORIZED",
            "message" to reason
        )
    )
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {

    // ── Plugins ───────────────────────────────────────────────────────────────

    install(ContentNegotiation) { jackson() }

    // Allows a route to call both receive<ByteArray>() (for HMAC) and
    // receiveMultipart() on the same request without an "already consumed" error.
    install(DoubleReceive)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "status"  to "error",
                    "error"   to "INTERNAL_ERROR",
                    "message" to (cause.localizedMessage ?: cause.javaClass.simpleName)
                )
            )
        }
    }

    // ── Decompiler registry ───────────────────────────────────────────────────

    val registry = DecompilerRegistry(
        listOf(
            CfrAdapter(),
            JadxAdapter(),
            ProcyonAdapter(),
            VineflowerAdapter(),
            JdCoreAdapter()
        )
    )

    // ── Routes ────────────────────────────────────────────────────────────────

    routing {

        // ── GET /health ───────────────────────────────────────────────────────
        // Intentionally left PUBLIC — no auth required.
        // Useful for Render / Railway keep-alive pings.
        get("/health") {
            call.respond(mapOf(
                "status"         to "ok",
                "decompilers"    to registry.availableDecompilers,
                "jarDecompilers" to registry.jarCapableDecompilers
            ))
        }

        // ── POST /decompile/class ─────────────────────────────────────────────
        //
        // Headers (required):
        //   x-auth-signature  — HMAC-SHA256 hex digest
        //   x-auth-timestamp  — Unix ms timestamp used when signing
        //
        // Multipart fields:
        //   file  (required) — the .class file
        //   mode  (optional, default "cfr") — decompiler name
        //
        post("/decompile/class") {

            // ── Auth ──────────────────────────────────────────────────────────
            if (!call.verifyHmacAuth()) {
                call.respondUnauthorized("Invalid or missing HMAC signature.")
                return@post
            }

            // ── Parse multipart ───────────────────────────────────────────────
            // DoubleReceive already buffered the body above; receiveMultipart()
            // reads from that same buffer, so the stream is still intact.
            val multipart = call.receiveMultipart()
            var mode      = "cfr"
            var fileBytes: ByteArray? = null
            var fileName  = "Unknown.class"

            multipart.forEachPart { part ->
                when {
                    part is PartData.FormItem && part.name == "mode" -> {
                        mode = part.value.trim()
                    }
                    part is PartData.FileItem && part.name == "file" -> {
                        fileName  = part.originalFileName?.trim()?.ifBlank { "Unknown.class" } ?: "Unknown.class"
                        fileBytes = part.streamProvider().readBytes()
                    }
                }
                part.dispose()
            }

            // ── Validate ──────────────────────────────────────────────────────
            val bytes = fileBytes
            if (bytes == null || bytes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "status"  to "error",
                    "error"   to "MISSING_FILE",
                    "message" to "No file was uploaded. Send the .class file as a multipart field named 'file'."
                ))
                return@post
            }

            registry.validateMode(mode)?.let { err ->
                call.respond(HttpStatusCode.BadRequest, err.toResponseMap()); return@post
            }
            registry.validateClassBytes(bytes, mode)?.let { err ->
                call.respond(HttpStatusCode.UnprocessableEntity, err.toResponseMap()); return@post
            }

            // ── Decompile (IO thread) ─────────────────────────────────────────
            val outcome = withContext(Dispatchers.IO) {
                registry.runClass(mode, bytes, fileName)
            }

            val statusCode = if (outcome is DecompileOutcome.Failure) HttpStatusCode.InternalServerError
                             else HttpStatusCode.OK
            call.respond(statusCode, outcome.toResponseMap())
        }

        // ── POST /decompile/jar ───────────────────────────────────────────────
        //
        // Headers (required):
        //   x-auth-signature  — HMAC-SHA256 hex digest
        //   x-auth-timestamp  — Unix ms timestamp used when signing
        //
        // Multipart fields:
        //   file  (required) — the .jar file
        //   mode  (optional, default "jadx") — decompiler name; must support JARs
        //
        post("/decompile/jar") {

            // ── Auth ──────────────────────────────────────────────────────────
            if (!call.verifyHmacAuth()) {
                call.respondUnauthorized("Invalid or missing HMAC signature.")
                return@post
            }

            // ── Parse multipart ───────────────────────────────────────────────
            val multipart = call.receiveMultipart()
            var mode      = "jadx"
            var jarBytes: ByteArray? = null

            multipart.forEachPart { part ->
                when {
                    part is PartData.FormItem && part.name == "mode" -> {
                        mode = part.value.trim()
                    }
                    part is PartData.FileItem && part.name == "file" -> {
                        jarBytes = part.streamProvider().readBytes()
                    }
                }
                part.dispose()
            }

            // ── Validate ──────────────────────────────────────────────────────
            val bytes = jarBytes
            if (bytes == null || bytes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "status"  to "error",
                    "error"   to "MISSING_FILE",
                    "message" to "No file was uploaded. Send the .jar file as a multipart field named 'file'."
                ))
                return@post
            }

            registry.validateMode(mode, requiresJar = true)?.let { err ->
                call.respond(HttpStatusCode.BadRequest, err.toResponseMap()); return@post
            }
            registry.validateJarBytes(bytes, mode)?.let { err ->
                call.respond(HttpStatusCode.UnprocessableEntity, err.toResponseMap()); return@post
            }

            // ── Decompile → Zip (IO thread) ───────────────────────────────────
            val (zipFile, outcome) = withContext(Dispatchers.IO) {
                val tempJar = File.createTempFile("jar-in-", ".jar").apply { writeBytes(bytes) }
                val outDir  = Files.createTempDirectory("jar-out-").toFile()
                val zipOut  = File.createTempFile("sources-", ".zip")

                val result = registry.runJar(mode, tempJar, outDir)

                ZipOutputStream(zipOut.outputStream().buffered()).use { zos ->
                    if (result is DecompileOutcome.Success) {
                        val msgs = result.result.warnings + result.result.errors
                        if (msgs.isNotEmpty()) {
                            zos.putNextEntry(ZipEntry("DECOMPILER_WARNINGS.txt"))
                            zos.write(msgs.joinToString("\n").toByteArray())
                            zos.closeEntry()
                        }
                    }
                    outDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.relativeTo(outDir).path))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                tempJar.delete()
                outDir.deleteRecursively()
                Pair(zipOut, result)
            }

            if (outcome is DecompileOutcome.Failure) {
                zipFile.delete()
                call.respond(HttpStatusCode.InternalServerError, outcome.toResponseMap())
                return@post
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "sources-$mode.zip")
                    .toString()
            )
            call.respondFile(zipFile)
            zipFile.delete()
        }
    }
}
