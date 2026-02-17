package org.endless

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
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

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {

    // ── Plugins ───────────────────────────────────────────────────────────────

    install(ContentNegotiation) { jackson() }

    install(StatusPages) {
        // Catch-all: anything that bubbles past the route handlers
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
    // Add / remove adapters here; the registry and routes adapt automatically.

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

        // Health check — useful for Render / Railway keep-alive pings
        get("/health") {
            call.respond(mapOf(
                "status"          to "ok",
                "decompilers"     to registry.availableDecompilers,
                "jarDecompilers"  to registry.jarCapableDecompilers
            ))
        }

        // ── POST /decompile/class ─────────────────────────────────────────────
        //
        // Multipart fields:
        //   file  (required) — the .class file
        //   mode  (optional, default "cfr") — decompiler name
        //
        post("/decompile/class") {
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
                    "status" to "error", "error" to "MISSING_FILE",
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
        // Multipart fields:
        //   file  (required) — the .jar file
        //   mode  (optional, default "jadx") — decompiler name; must support JARs
        //
        post("/decompile/jar") {
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
                    "status" to "error", "error" to "MISSING_FILE",
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
                val zipFile = File.createTempFile("sources-", ".zip")

                val outcome = registry.runJar(mode, tempJar, outDir)

                // Zip the output regardless; include a warnings.txt if any were captured
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    if (outcome is DecompileOutcome.Success) {
                        val msgs = outcome.result.warnings + outcome.result.errors
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
                Pair(zipFile, outcome)
            }

            if (outcome is DecompileOutcome.Failure) {
                zipFile.delete()
                call.respond(HttpStatusCode.InternalServerError, outcome.toResponseMap())
                return@post
            }

            // Stream zip back to the caller then delete the temp file
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
