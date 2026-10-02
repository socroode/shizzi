package dev.shizzi

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class MessagingHttpServer(
    context: Context,
    private val accountsProvider: () -> Map<String, MessagingAccount>,
) {
    private val store = MessagingStore(File(context.filesDir, STORE_FILE))
    private val presence = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientPool = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return isListening()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), PORT))
            }
        } catch (failure: IOException) {
            running.set(false)
            Log.e(TAG, "messaging server failed to bind $LOOPBACK_HOST:$PORT", failure)
            return false
        }

        serverSocket = socket
        acceptExecutor.execute { acceptLoop(socket) }
        Log.i(TAG, "messaging server listening on $LOOPBACK_HOST:$PORT")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientPool.shutdownNow()
        presence.clear()
        Log.i(TAG, "messaging server stopped")
    }

    fun isListening(): Boolean =
        running.get() && serverSocket?.let { it.isBound && !it.isClosed } == true

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (failure: IOException) {
                    if (running.get()) Log.w(TAG, "messaging accept failed", failure)
                    break
                }
                try {
                    clientPool.execute { handleSafely(client) }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        } finally {
            running.set(false)
            runCatching { server.close() }
            if (serverSocket === server) serverSocket = null
        }
    }

    private fun handleSafely(socket: Socket) {
        try {
            handle(socket)
        } catch (failure: Exception) {
            if (!isNormalDisconnect(failure)) Log.w(TAG, "messaging client error", failure)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            if (!client.inetAddress.isLoopbackAddress) {
                writeJson(client, 403, JSONObject().put("ok", false).put("message", "Accès local uniquement."))
                return
            }

            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) {
                    headers[line.substring(0, split).trim().lowercase(Locale.US)] =
                        line.substring(split + 1).trim()
                }
            }

            val accounts = accountsProvider()
            val account = MessagingStore.normalizeAccount(headers["x-shizzi-chat-account"].orEmpty())
            if (account.isBlank() || accounts[account]?.enabled != true) {
                writeJson(
                    output,
                    401,
                    JSONObject().put("ok", false).put("message", "Compte Shizzi requis."),
                    headOnly = method == "HEAD",
                )
                return
            }

            val now = System.currentTimeMillis()
            presence[account] = now
            prunePresence(now)

            val uri = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val body = if (method == "POST") readBody(input, headers) else ByteArray(0)

            when {
                method in setOf("GET", "HEAD") && (uri == "/" || uri == "/index.html") ->
                    writeText(output, 200, "OK", "text/html; charset=utf-8", page(), method == "HEAD")

                method == "GET" && uri == "/api/snapshot" -> {
                    val payload = store.snapshot(
                        accountRaw = account,
                        accounts = accounts,
                        presence = presence,
                        nowMillis = now,
                        conversationIdRaw = query["conversation"],
                        markRead = query["markRead"] != "0",
                    )
                    writeJson(output, if (payload.optBoolean("ok")) 200 else 403, payload)
                }

                method == "POST" && uri == "/api/send" -> {
                    val payload = parseJson(body)
                    val result = store.send(
                        senderRaw = account,
                        conversationIdRaw = payload.optString("conversationId"),
                        textRaw = payload.optString("text"),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 400, result)
                }

                method == "POST" && uri == "/api/group/create" -> {
                    val payload = parseJson(body)
                    val rawMembers = buildList {
                        val array = payload.optJSONArray("members") ?: JSONArray()
                        for (index in 0 until array.length()) add(array.optString(index))
                    }
                    val result = store.createGroup(
                        creatorRaw = account,
                        nameRaw = payload.optString("name"),
                        membersRaw = rawMembers,
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 400, result)
                }

                method == "POST" && uri == "/api/heartbeat" -> {
                    presence[account] = now
                    writeJson(
                        output,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("unread", store.unreadTotal(account, accounts)),
                    )
                }

                method in setOf("GET", "HEAD") && uri == "/health" ->
                    writeText(output, 200, "OK", "text/plain; charset=utf-8", "ok", method == "HEAD")

                else -> writeJson(output, 404, JSONObject().put("ok", false).put("message", "Introuvable."))
            }
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (length > MAX_BODY_BYTES) throw IllegalArgumentException("Requête trop volumineuse.")
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == out.size) out else out.copyOf(offset)
    }

    private fun parseJson(body: ByteArray): JSONObject =
        runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }

    private fun prunePresence(now: Long) {
        presence.entries.removeIf { now - it.value > PRESENCE_RETENTION_MILLIS }
    }

    private fun isNormalDisconnect(failure: Exception): Boolean {
        if (failure is SocketException) return true
        val message = failure.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("socket closed")
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val index = pair.indexOf('=')
                val key = if (index >= 0) pair.substring(0, index) else pair
                val value = if (index >= 0) pair.substring(index + 1) else ""
                decode(key) to decode(value)
            }
            .toMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
            if (bytes.size > 16_384) throw IOException("HTTP line too long")
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeJson(socket: Socket, code: Int, payload: JSONObject) {
        val output = BufferedOutputStream(socket.getOutputStream())
        writeJson(output, code, payload)
    }

    private fun writeJson(
        output: BufferedOutputStream,
        code: Int,
        payload: JSONObject,
        headOnly: Boolean = false,
    ) {
        writeText(output, code, reason(code), "application/json; charset=utf-8", payload.toString(), headOnly)
    }

    private fun writeText(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        headOnly: Boolean,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun page(): String = """
        <!doctype html>
        <html lang="fr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
          <title>Messagerie · Shizzi+</title>
          <style>
            :root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif;background:#07111f;color:#f8fafc}
            *{box-sizing:border-box}body{margin:0;background:#07111f}
            button,input{font:inherit}.app{height:100vh;display:grid;grid-template-columns:340px 1fr}
            aside{border-right:1px solid #ffffff16;background:#0b1728;display:flex;flex-direction:column;min-height:0}
            .brand{padding:20px;border-bottom:1px solid #ffffff12}.brand h1{margin:0;font-size:24px}.muted{color:#94a3b8}
            .toolbar{padding:12px;display:grid;grid-template-columns:1fr auto;gap:8px}
            .toolbar input,.composer input,.group input{border:1px solid #334155;background:#111d30;color:#fff;border-radius:14px;padding:12px}
            .toolbar button,.composer button,.group button{border:0;border-radius:14px;padding:12px 14px;background:#22d3ee;color:#06202a;font-weight:800}
            .list{overflow:auto;padding:6px 10px 16px}.row{width:100%;text-align:left;border:0;background:transparent;color:#fff;border-radius:14px;padding:12px;display:grid;grid-template-columns:1fr auto;gap:4px}
            .row:hover,.row.active{background:#ffffff0c}.name{font-weight:800}.preview{grid-column:1/2;color:#94a3b8;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .badge{align-self:center;background:#38bdf8;color:#061827;border-radius:999px;padding:3px 8px;font-size:12px;font-weight:900}.online{color:#34d399}.offline{color:#64748b}
            main{display:flex;flex-direction:column;min-width:0;background:linear-gradient(160deg,#081522,#101827)}
            .head{padding:17px 20px;border-bottom:1px solid #ffffff12;display:flex;align-items:center;gap:12px}.head strong{font-size:19px}
            .back{display:none;border:0;background:transparent;color:#7dd3fc;font-size:24px}.messages{flex:1;overflow:auto;padding:18px;display:flex;flex-direction:column;gap:10px}
            .empty{margin:auto;color:#94a3b8;text-align:center;max-width:360px}.bubble{max-width:min(78%,620px);padding:11px 14px;border-radius:17px;background:#17243a;align-self:flex-start}
            .bubble.mine{background:#164e63;align-self:flex-end}.meta{font-size:11px;color:#9fb1c5;margin-bottom:4px}.body{white-space:pre-wrap;overflow-wrap:anywhere}
            .composer{display:grid;grid-template-columns:1fr auto;gap:10px;padding:14px;border-top:1px solid #ffffff12}.composer[hidden]{display:none}
            dialog{border:1px solid #ffffff20;border-radius:22px;background:#0f172a;color:#fff;width:min(92vw,520px);padding:20px}.group{display:grid;gap:12px}
            .members{max-height:240px;overflow:auto;display:grid;gap:8px}.member{display:flex;gap:10px;align-items:center}.member input{width:18px;height:18px}
            @media(max-width:760px){.app{grid-template-columns:1fr}aside{display:flex}.app.chat-open aside{display:none}.app:not(.chat-open) main{display:none}.back{display:block}}
          </style>
        </head>
        <body>
        <div id="app" class="app">
          <aside>
            <div class="brand"><h1>Messagerie Shizzi</h1><div id="self" class="muted">Connexion…</div></div>
            <div class="toolbar"><input id="search" placeholder="Rechercher"><button id="newGroup">+ Groupe</button></div>
            <div id="list" class="list"></div>
          </aside>
          <main>
            <div class="head"><button id="back" class="back">‹</button><div><strong id="title">Messagerie locale</strong><div id="presence" class="muted">Choisis une conversation</div></div></div>
            <div id="messages" class="messages"><div class="empty">Messages privés et groupes restent sur le réseau local Shizzi et n'utilisent pas le quota Internet.</div></div>
            <form id="composer" class="composer" hidden><input id="text" maxlength="2000" autocomplete="off" placeholder="Écrire un message…"><button>Envoyer</button></form>
          </main>
        </div>
        <dialog id="groupDialog"><form id="groupForm" class="group" method="dialog">
          <h2>Nouveau groupe</h2><input id="groupName" maxlength="60" placeholder="Nom du groupe" required>
          <div id="groupMembers" class="members"></div>
          <div style="display:flex;gap:8px;justify-content:flex-end"><button type="button" id="cancelGroup">Annuler</button><button type="submit">Créer</button></div>
        </form></dialog>
        <script>
        (function(){
          var selected="", state=null;
          var app=document.getElementById("app"), list=document.getElementById("list"), messages=document.getElementById("messages");
          var composer=document.getElementById("composer"), text=document.getElementById("text"), search=document.getElementById("search");
          var groupDialog=document.getElementById("groupDialog"), groupMembers=document.getElementById("groupMembers");
          function time(ms){if(!ms)return"";var d=new Date(ms);return d.toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"})}
          function userByNumber(n){return state&&state.users ? state.users.find(function(u){return u.number===n}) : null}
          function currentConversation(){return state&&state.conversations ? state.conversations.find(function(c){return c.id===selected}) : null}
          function renderList(){
            if(!state)return; var q=search.value.trim().toLowerCase(); list.textContent="";
            state.conversations.filter(function(c){return !q||c.name.toLowerCase().includes(q)}).forEach(function(c){
              var b=document.createElement("button"); b.className="row"+(c.id===selected?" active":""); b.dataset.id=c.id;
              var n=document.createElement("div"); n.className="name"; n.textContent=(c.type==="group"?"👥 ":"")+c.name; b.appendChild(n);
              if(c.unread>0){var badge=document.createElement("span");badge.className="badge";badge.textContent=c.unread;b.appendChild(badge)}
              var p=document.createElement("div");p.className="preview";p.textContent=c.lastMessage||"Aucun message";b.appendChild(p);
              b.onclick=function(){selected=c.id;app.classList.add("chat-open");refresh(true)}; list.appendChild(b);
            });
          }
          function renderMessages(){
            var c=currentConversation(); messages.textContent="";
            if(!c){messages.innerHTML='<div class="empty">Choisis une conversation.</div>';composer.hidden=true;return}
            document.getElementById("title").textContent=c.name; composer.hidden=false;
            if(c.type==="direct"){
              var other=c.members.find(function(n){return n!==state.self.number}), u=userByNumber(other);
              var online=u&&u.online; document.getElementById("presence").textContent=online?"● En ligne":"Hors ligne";
              document.getElementById("presence").className=online?"online":"offline";
            }else{
              document.getElementById("presence").textContent=c.members.length+" membres";document.getElementById("presence").className="muted";
            }
            if(!state.messages.length){messages.innerHTML='<div class="empty">Aucun message. Écris le premier.</div>';return}
            state.messages.forEach(function(m){
              var b=document.createElement("div");b.className="bubble"+(m.sender===state.self.number?" mine":"");
              var meta=document.createElement("div");meta.className="meta";meta.textContent=m.senderName+" · "+time(m.createdAtMillis);
              var body=document.createElement("div");body.className="body";body.textContent=m.text;b.appendChild(meta);b.appendChild(body);messages.appendChild(b);
            });
            messages.scrollTop=messages.scrollHeight;
          }
          function render(){
            if(!state)return;document.getElementById("self").textContent=state.self.name+" · "+state.self.number;
            renderList();renderMessages();
            var total=state.conversations.reduce(function(a,c){return a+Number(c.unread||0)},0);
            document.title=(total>0?"("+total+") ":"")+"Messagerie · Shizzi+";
          }
          async function refresh(markRead){
            try{
              var url="/chat/api/snapshot"+(selected?"?conversation="+encodeURIComponent(selected)+"&markRead="+(markRead?"1":"0"):"");
              var r=await fetch(url,{cache:"no-store"});var j=await r.json();if(!j.ok)return;state=j;
              if(selected&&!state.conversations.some(function(c){return c.id===selected}))selected="";
              render();
            }catch(_){}
          }
          composer.onsubmit=async function(e){
            e.preventDefault();var value=text.value.trim();if(!selected||!value)return;text.value="";
            await fetch("/chat/api/send",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({conversationId:selected,text:value})});refresh(true);
          };
          search.oninput=renderList; document.getElementById("back").onclick=function(){app.classList.remove("chat-open")};
          document.getElementById("newGroup").onclick=function(){
            if(!state)return;document.getElementById("groupName").value="";groupMembers.textContent="";
            state.users.filter(function(u){return u.number!==state.self.number}).forEach(function(u){
              var label=document.createElement("label");label.className="member";var cb=document.createElement("input");cb.type="checkbox";cb.value=u.number;
              var span=document.createElement("span");span.textContent=u.name+" · "+u.number;label.appendChild(cb);label.appendChild(span);groupMembers.appendChild(label);
            });groupDialog.showModal();
          };
          document.getElementById("cancelGroup").onclick=function(){groupDialog.close()};
          document.getElementById("groupForm").onsubmit=async function(e){
            e.preventDefault();var name=document.getElementById("groupName").value.trim();
            var members=Array.from(groupMembers.querySelectorAll('input:checked')).map(function(x){return x.value});
            var r=await fetch("/chat/api/group/create",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({name:name,members:members})});
            var j=await r.json();if(j.ok){selected=j.conversationId;groupDialog.close();app.classList.add("chat-open");refresh(true)}
          };
          refresh(false);setInterval(function(){refresh(false)},2000);
        })();
        </script>
        </body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "ShizziMessaging"
        private const val STORE_FILE = "shizzi-messaging-v1.json"
        private const val LOOPBACK_HOST = "127.0.0.1"
        internal const val PORT = 8090
        internal const val MAX_CLIENTS = 32
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val PRESENCE_RETENTION_MILLIS = 60_000L
    }
}
