// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Where an uploaded file may go: inside the root, with a subtitle or font extension, no `..`. */
internal object PhoneUploadPaths {
    val ALLOWED_EXTENSIONS = LibraryMatcher.SUBTITLE_EXTENSIONS + SfntNames.FONT_EXTENSIONS

    fun resolve(root: File, relativePath: String): File? {
        val parts = relativePath.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.size > 6) return null
        if (parts.any { it == ".." || it.startsWith(".") || it.length > 160 || it.any { c -> c.code < 32 || c in "<>:\"|?*" } }) return null
        val extension = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in ALLOWED_EXTENSIONS) return null
        val target = parts.fold(root) { dir, part -> File(dir, part) }
        val rootPath = root.canonicalPath + File.separator
        return target.takeIf { it.canonicalPath.startsWith(rootPath) }
    }
}

/**
 * "Enviar desde el celular": a LAN page, alive only while its screen is open, where a phone picks
 * a whole folder (`<input webkitdirectory>`) and sends its subtitles and fonts to the TV. Every
 * request needs the random token from the QR code. Files land in `filesDir/kai-subtitles/Subtítulos`.
 */
internal class SubtitleUploadServer private constructor(
    private val root: File,
    private val token: String,
    port: Int,
    private val onReceived: (count: Int) -> Unit
) : NanoHTTPD(port) {
    private val received = AtomicInteger(0)
    private var totalBytes = 0L

    val path: String get() = "/u/$token/"

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/u/$token/"
        if (!session.uri.startsWith(prefix)) return status(404, "not found")
        val rest = session.uri.removePrefix(prefix)
        return when {
            session.method == Method.GET && rest.isEmpty() ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE).withSafeHeaders()
            session.method == Method.POST && rest == "file" -> receive(session)
            else -> status(404, "not found")
        }
    }

    private fun receive(session: IHTTPSession): Response {
        val relative = session.parameters["path"]?.firstOrNull()
            ?: session.queryParameterString?.let { query ->
                query.split('&').firstOrNull { it.startsWith("path=") }?.removePrefix("path=")?.let { URLDecoder.decode(it, "UTF-8") }
            }
            ?: return status(400, "path")
        val target = PhoneUploadPaths.resolve(root, relative) ?: return status(400, "type")
        val length = session.headers["content-length"]?.toLongOrNull() ?: return status(411, "length")
        if (length <= 0 || length > MAX_FILE_BYTES) return status(413, "size")
        synchronized(this) {
            if (totalBytes + length > MAX_SESSION_BYTES) return status(413, "session")
            totalBytes += length
        }
        return try {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.part")
            temporary.outputStream().use { out ->
                val input = session.inputStream
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) throw IOException("short body")
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) throw IOException("rename")
            onReceived(received.incrementAndGet())
            status(200, "ok")
        } catch (error: IOException) {
            status(500, "write")
        }
    }

    private fun status(code: Int, message: String): Response =
        newFixedLengthResponse(Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", message)
            .withSafeHeaders()

    private fun Response.withSafeHeaders(): Response = apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
    }

    companion object {
        private const val MAX_FILE_BYTES = 30L * 1024 * 1024
        private const val MAX_SESSION_BYTES = 500L * 1024 * 1024

        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun start(root: File, onReceived: (Int) -> Unit, startPort: Int = 8130, attempts: Int = 10): SubtitleUploadServer? {
            root.mkdirs()
            val token = newToken()
            for (port in startPort until startPort + attempts) {
                try {
                    return SubtitleUploadServer(root, token, port, onReceived).also { it.start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }

        private val PAGE = """
<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio Arc Kai</title><style>body{font-family:sans-serif;background:#111;color:#eee;max-width:560px;margin:24px auto;padding:0 16px}
button,input{font-size:18px;margin:12px 0}#log{white-space:pre-wrap;font-size:14px;color:#aaa}</style></head><body>
<h2>Subtítulos → TV</h2>
<p>Elige tu carpeta <b>Subtítulos</b> (o la de un anime). Se envían los .ass, .ssa, .srt, .vtt y las fuentes .ttf, .otf, .ttc.</p>
<p>Choose your <b>Subtítulos</b> folder (or one anime's folder). Subtitles and fonts are sent to the TV.</p>
<input id="dir" type="file" webkitdirectory directory multiple><br><button id="send">Enviar / Send</button>
<div id="log"></div>
<script>
const ok=/\.(ass|ssa|srt|vtt|ttf|otf|ttc|otc)$/i;
document.getElementById('send').onclick=async()=>{
 const files=[...document.getElementById('dir').files].filter(f=>ok.test(f.name));
 const log=document.getElementById('log');let n=0;log.textContent=files.length+' archivos / files\n';
 for(const f of files){
  let p=f.webkitRelativePath||f.name;const parts=p.split('/');
  if(parts.length>1&&/^subt[ií]tulos$/i.test(parts[0]))parts.shift();p=parts.join('/');
  try{const r=await fetch('file?path='+encodeURIComponent(p),{method:'POST',body:f});
   log.textContent+=(r.ok?'✓ ':'✗ ')+p+'\n';if(r.ok)n++;}catch(e){log.textContent+='✗ '+p+'\n';}
 }
 log.textContent+='\n'+n+' / '+files.length;
};
</script></body></html>
""".trimIndent()
    }
}
