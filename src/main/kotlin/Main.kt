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

import org.endless.services.CfrAdapter
import org.endless.services.JadxAdapter
import org.endless.services.ProcyonAdapter

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // 1. Install Plugins
    install(ContentNegotiation) {
        jackson()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to cause.localizedMessage))
        }
    }

    // 2. Initialize Services (Singletons)
    val cfr = CfrAdapter()
    val procyon = ProcyonAdapter()
    val jadx = JadxAdapter()

    // 3. Define Routes
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "engine" to "ktor"))
        }

        post("/decompile/class") {
            // Stream multipart data
            val multipart = call.receiveMultipart()
            var mode = "cfr"
            var fileBytes: ByteArray? = null
            var fileName = ""

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "mode") mode = part.value
                    }
                    is PartData.FileItem -> {
                        if (part.name == "file") {
                            fileName = part.originalFileName ?: "Unknown"
                            // Read bytes into memory (Caution: limit file size in real apps)
                            fileBytes = part.streamProvider().readBytes()
                        }
                    }
                    else -> part.dispose()
                }
            }

            if (fileBytes == null || fileBytes!!.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file uploaded"))
                return@post
            }

            // Move heavy work to IO thread to keep server responsive
            val result = withContext(Dispatchers.IO) {
                try {
                    val source = when (mode.lowercase()) {
                        "procyon" -> procyon.decompileClass(fileBytes!!, fileName)
                        "jadx" -> jadx.decompileClass(fileBytes!!)
                        else -> cfr.decompileClass(fileBytes!!, fileName) // Default CFR
                    }
                    mapOf("status" to "success", "mode" to mode, "source" to source)
                } catch (e: Exception) {
                    e.printStackTrace()
                    mapOf("status" to "error", "message" to e.message)
                }
            }

            call.respond(result)
        }
    }
}