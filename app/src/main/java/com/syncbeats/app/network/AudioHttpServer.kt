package com.syncbeats.app.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

class AudioHttpServer(private val context: Context) {

    private var server: CIOApplicationEngine? = null
    private val currentUri = AtomicReference<Uri?>(null)
    private val currentMimeType = AtomicReference("audio/mpeg")
    private val currentFileSize = AtomicReference(0L)

    fun setAudioTrack(uri: Uri): Pair<String, Long> {
        currentUri.set(uri)
        val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
        currentMimeType.set(mime)

        var fileName = "Audio Track"
        var fileSize = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            }
        }
        if (fileSize <= 0L) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) fileSize = afd.length
            }
        }
        currentFileSize.set(fileSize)
        return fileName to fileSize
    }

    fun start(port: Int = NetworkUtils.HTTP_PORT) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/stream") {
                    val uri = currentUri.get()
                    if (uri == null) {
                        call.respond(HttpStatusCode.NotFound, "No track loaded")
                        return@get
                    }

                    val totalSize = currentFileSize.get()
                    val contentType = runCatching {
                        ContentType.parse(currentMimeType.get())
                    }.getOrDefault(ContentType.Audio.MPEG)

                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    var start = 0L
                    var end = if (totalSize > 0) totalSize - 1 else -1L
                    var isPartial = false

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalSize > 0) {
                        val rangeSpec = rangeHeader.removePrefix("bytes=").split(",")[0].trim()
                        val parts = rangeSpec.split("-")
                        start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        val parsedEnd = parts.getOrNull(1)?.toLongOrNull()
                        end = (parsedEnd ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
                        if (start > end || start < 0) {
                            call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                            return@get
                        }
                        isPartial = true
                    }

                    val contentLength = if (end >= start && totalSize > 0) (end - start + 1) else -1L
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    if (contentLength > 0) {
                        call.response.header(HttpHeaders.ContentLength, contentLength.toString())
                    }
                    if (isPartial) {
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                    }

                    val status = if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK
                    call.respondBytesWriter(contentType = contentType, status = status) {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                FileInputStream(pfd.fileDescriptor).use { fis ->
                                    if (start > 0) {
                                        fis.channel.position(start)
                                    }
                                    val buffer = ByteArray(64 * 1024)
                                    var remaining = if (contentLength > 0) contentLength else Long.MAX_VALUE
                                    while (remaining > 0) {
                                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                        val read = fis.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        writeFully(buffer, 0, read)
                                        remaining -= read
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        server = null
    }
}
