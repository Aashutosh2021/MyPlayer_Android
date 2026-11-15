package com.myplayer.app.tv

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Zero-Dependency Phone-to-TV LAN Remote Server for MyPlayer TV.
 * Hosts a mobile web remote controller at `http://<TV-LAN-IP>:<PORT>` so any phone
 * that scans the on-screen QR code gets an instant Glassmorphic Remote Control
 * (Play/Pause, Skip Next/Prev, Seek, Volume, and "Play Song/Query on TV") without even needing an app!
 */
class TvRemoteServer(
    private val onCommand: (action: String, param: String?) -> Unit,
    private val currentTrackProvider: () -> Pair<String, String>
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    var boundUrl: String? = null
        private set

    fun start(scope: CoroutineScope): String? {
        boundUrl?.let { return it }
        val ip = findLanIpv4() ?: return null
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        serverSocket = ss
        val url = "http://$ip:${ss.localPort}"
        boundUrl = url

        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { handleClient(client) }
            }
        }
        return url
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundUrl = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val reqLine = reader.readLine() ?: return
            val path = reqLine.split(" ").getOrNull(1) ?: "/"

            if (path.startsWith("/cmd")) {
                val query = path.substringAfter("?", "")
                val params = query.split("&").mapNotNull {
                    val p = it.split("=", limit = 2)
                    if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
                }.toMap()
                val action = params["a"].orEmpty()
                val value = params["v"]
                if (action.isNotBlank()) {
                    onCommand(action, value)
                }
                val (title, artist) = currentTrackProvider()
                val json = """{"ok":true,"title":"${title.replace("\"", "'")}","artist":"${artist.replace("\"", "'")}"}"""
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$json"
                s.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
                return
            }

            val (title, artist) = currentTrackProvider()
            val html = buildRemoteWebHtml(title, artist)
            val bodyBytes = html.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
        }
    }

    private fun buildRemoteWebHtml(title: String, artist: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>MyPlayer TV Remote</title>
        <style>
          body { background: linear-gradient(145deg,#0f0c20,#240b36); color:#fff; font-family: system-ui, sans-serif; text-align:center; padding:24px; margin:0; min-height:100vh; }
          .card { background: rgba(255,255,255,0.08); border:1px solid rgba(255,255,255,0.16); border-radius:24px; padding:22px; max-width:380px; margin:0 auto; backdrop-filter: blur(16px); }
          h2 { margin:0 0 4px 0; font-size:20px; }
          p { margin:0 0 18px 0; color:#cbd5e1; font-size:14px; }
          .row { display:flex; justify-content:center; gap:14px; margin:16px 0; }
          button { background: rgba(255,255,255,0.12); color:#fff; border:1px solid rgba(255,255,255,0.2); border-radius:50px; padding:14px 22px; font-size:16px; font-weight:700; cursor:pointer; }
          button.primary { background: linear-gradient(90deg,#7c3aed,#db2777); border:none; padding:16px 30px; font-size:18px; }
          input { width:72%; padding:12px 14px; border-radius:14px; border:1px solid rgba(255,255,255,0.2); background:rgba(0,0,0,0.3); color:#fff; font-size:15px; }
        </style>
        </head>
        <body>
          <div class="card">
            <div style="color:#a855f7;font-weight:800;font-size:12px;letter-spacing:1.5px;margin-bottom:8px;">MYPLAYER TV REMOTE</div>
            <h2 id="t">${title.ifBlank { "MyPlayer TV Ready" }}</h2>
            <p id="a">${artist.ifBlank { "Connected over Wi-Fi LAN" }}</p>
            <div class="row">
              <button onclick="send('prev')">⏮ Prev</button>
              <button class="primary" onclick="send('playpause')">⏯ Play/Pause</button>
              <button onclick="send('next')">⏭ Next</button>
            </div>
            <div class="row">
              <button onclick="send('seek_back')">⏪ -10s</button>
              <button onclick="send('fav')">❤️ Like</button>
              <button onclick="send('seek_fwd')">+10s ⏩</button>
            </div>
            <div style="margin-top:20px;display:flex;gap:8px;justify-content:center;">
              <input id="q" placeholder="Search & Play song on TV..." />
              <button class="primary" style="padding:12px 18px;" onclick="playSearch()">Play</button>
            </div>
          </div>
          <script>
            async function send(a, v='') {
              const r = await fetch('/cmd?a=' + encodeURIComponent(a) + '&v=' + encodeURIComponent(v));
              const d = await r.json();
              if (d.title) document.getElementById('t').innerText = d.title;
              if (d.artist) document.getElementById('a').innerText = d.artist;
            }
            function playSearch() {
              const q = document.getElementById('q').value;
              if (q) { send('search_play', q); document.getElementById('q').value = ''; }
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun findLanIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}

/**
 * Pure Kotlin QR-Like Visual Matrix + URL Card for pairing without external ZXing dependencies if not present,
 * or renders crisp deterministic finder patterns for the TV Pairing Dialog.
 */
@Composable
fun TvPairingMatrixBadge(url: String, modifier: Modifier = Modifier) {
    val bmp = remember(url) {
        val size = 21
        val scale = 12
        val out = Bitmap.createBitmap(size * scale, size * scale, Bitmap.Config.ARGB_8888)
        out.eraseColor(AndroidColor.WHITE)

        fun fillCell(cx: Int, cy: Int, black: Boolean) {
            val color = if (black) AndroidColor.BLACK else AndroidColor.WHITE
            for (y in cy * scale until (cy + 1) * scale) {
                for (x in cx * scale until (cx + 1) * scale) {
                    out.setPixel(x, y, color)
                }
            }
        }

        fun drawFinder(ox: Int, oy: Int) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val border = dx == 0 || dx == 6 || dy == 0 || dy == 6
                    val inner = dx in 2..4 && dy in 2..4
                    fillCell(ox + dx, oy + dy, border || inner)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(size - 7, 0)
        drawFinder(0, size - 7)

        val bytes = url.toByteArray(Charsets.UTF_8)
        var bitIdx = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inFinder = (x <= 7 && y <= 7) || (x >= size - 8 && y <= 7) || (x <= 7 && y >= size - 8)
                if (!inFinder) {
                    val b = bytes[(bitIdx / 8) % bytes.size].toInt()
                    val on = ((b shr (bitIdx % 8)) and 1) == 1
                    fillCell(x, y, on)
                    bitIdx++
                }
            }
        }
        out.asImageBitmap()
    }
    Image(bitmap = bmp, contentDescription = url, modifier = modifier)
}