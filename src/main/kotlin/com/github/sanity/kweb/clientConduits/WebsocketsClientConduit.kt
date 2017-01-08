package com.github.sanity.kweb.clientConduits

import com.github.sanity.kweb.gson
import com.github.sanity.kweb.random
import io.netty.channel.Channel
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.wasabifx.wasabi.app.AppConfiguration
import org.wasabifx.wasabi.app.AppServer
import org.wasabifx.wasabi.protocol.websocket.respond
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by ian on 12/31/16.
 */

typealias OneTime = Boolean

class WebsocketsClientConduit(val port: Int, val startHead: String = "", val endHead: String = "", open val rh: CoreReceiver.() -> Boolean) : ClientConduit() {
    private val server = AppServer(AppConfiguration(port = port))
    private val clients: MutableMap<String, ClientConnection>

    init {
        //TODO: Need to do housekeeping to delete old client data
        clients = ConcurrentHashMap<String, ClientConnection>()

        val bootstrapHtml = String(Files.readAllBytes(Paths.get(javaClass.getResource("bootstrap.html").toURI())), StandardCharsets.UTF_8)
                .replace("<!-- START HEADER PLACEHOLDER -->", startHead)
                .replace("<!-- END HEADER PLACEHOLDER -->", endHead)

        server.get("/", {
            val newClientId = Math.abs(random.nextLong()).toString(16)
            val clientConnection = PreSetupClientConnection(newClientId)
            clients.put(newClientId, clientConnection)
            clientConnection.send(S2CWebsocketMessage(newClientId))
            rh.invoke(CoreReceiver(newClientId, this@WebsocketsClientConduit))
            response.send(bootstrapHtml.replace("<!-- INITIAL SCRIPT PLACEHOLDER -->", clientConnection.getPreMessages()))
        })

        server.channel("/ws") {
            if (frame is TextWebSocketFrame) {
                // TODO: This may be vulnerable to concurrency issues.  For example, what if
                // TODO: two incoming frames from a client are processed in parallel?
                val message = gson.fromJson((frame as TextWebSocketFrame).text(), C2SWebsocketMessage::class.java)
                if (message.hello != null) {
                    val existingClientData = clients.get(message.id) ?: throw RuntimeException("No client connection found for id ${message.id}")
                    if (existingClientData is PreSetupClientConnection) {
                        val clientData = WSClientConnection(id = message.id, clientChannel = ctx!!.channel(), handlers = existingClientData.handlers)
                        clients.put(message.id, clientData)
                    }
                } else {
                    val clientId = message.id ?: throw RuntimeException("Message has no id but is not hello")
                    val clientData = clients[clientId] ?: throw RuntimeException("No handler found for client $clientId")
                    when {
                        message.callback != null -> {
                            val (resultId, result) = message.callback
                            val resultHandler = clientData.handlers[resultId] ?: throw RuntimeException("No data handler for $resultId for client $clientId")
                            val oneTime = resultHandler(result)
                            if (oneTime) {
                                clientData.handlers.remove(resultId)
                            }
                        }
                    }
                }
            }
        }
        server.start()
    }


    override fun execute(clientId: String, js: String) {
        //println("execute($js)")
        val clientConnection = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        clientConnection.send(S2CWebsocketMessage(yourId = clientId, execute = Execute(js)))
    }

    override fun executeWithCallback(clientId: String, js: String, callbackId: Int, handler: (String) -> Boolean) {
        val clientConnection = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        clientConnection.handlers.put(callbackId, handler)
        clientConnection.send(S2CWebsocketMessage(yourId = clientId, execute = Execute(js)))
    }

    override fun evaluate(clientId: String, expression: String, handler: (String) -> Boolean) {
        val clientConnection = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val callbackId = Math.abs(random.nextInt())
        clientConnection.handlers.put(callbackId, handler)
        clientConnection.send(S2CWebsocketMessage(clientId, evaluate = Evaluate(expression, callbackId)))
    }

}


abstract class ClientConnection(open val id: String,
                                open val handlers: MutableMap<Int, (String) -> OneTime> = HashMap()) {
    abstract fun send(message: S2CWebsocketMessage)
}

private data class WSClientConnection(override val id: String,
                                      val clientChannel: Channel,
                                      override val handlers: MutableMap<Int, (String) -> OneTime> = HashMap())
    : ClientConnection(id, handlers) {
    override fun send(message: S2CWebsocketMessage) {
        respond(clientChannel, TextWebSocketFrame(gson.toJson(message)))
    }
}

private data class PreSetupClientConnection(override val id: String, override val handlers: MutableMap<Int, (String) -> OneTime> = HashMap()) : ClientConnection(id, handlers) {
    private @Volatile var active = true

    val toSend: StringBuffer = StringBuffer()

    override fun send(message: S2CWebsocketMessage) {
        if (!active) {
            throw IllegalStateException("PreSetupClientConnection should not be used after getPreMessages has been called")
        }
        toSend.appendln("handleMessage(${gson.toJson(message)});")
    }

    fun getPreMessages(): String {
        active = false
        return toSend.toString()
    }
}

data class S2CWebsocketMessage(
        val yourId: String,
        val execute: Execute? = null,
        val evaluate: Evaluate? = null
)

data class Execute(val js: String)

data class Evaluate(val js: String, val responseId: Int)

data class C2SWebsocketMessage(
        val id: String,
        val hello: Boolean? = true,
        val callback: C2SCallback?
)

data class C2SCallback(val callbackId: Int, val data: String)
