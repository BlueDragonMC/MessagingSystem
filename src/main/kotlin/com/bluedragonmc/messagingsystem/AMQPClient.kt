package com.bluedragonmc.messagingsystem

import com.bluedragonmc.messagingsystem.message.Message
import com.bluedragonmc.messagingsystem.serializer.UUIDSerializer
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.util.*
import kotlin.reflect.KClass

class AMQPClient(
    private val hostname: String = System.getProperty("rabbitmq_host", "rabbitmq"),
    private val port: Int = System.getProperty("rabbitmq_port", "5672").toInt(),
    json: Json = Json {
        prettyPrint = false
        serializersModule = SerializersModule {
            include(serializersModule)
            contextual(UUID::class, UUIDSerializer)
        }
    },
    exchangeName: String = System.getProperty("rabbitmq_exchange_name", "bluedragon"),
    rpcExchangeName: String = System.getProperty("rabbitmq_rpc_exchange_name", ""),
    routingKey: String = System.getProperty("rabbitmq_routing_key", ""),
    rpcQueueName: String = System.getProperty("rabbitmq_rpc_queue_name", "rpc_queue"),
    connectionName: String? = null
) {

    private val connectionFactory by lazy {
        ConnectionFactory().apply {
            host = hostname
            port = this@AMQPClient.port
        }
    }

    private val connection by lazy {
        connectionFactory.newConnection(connectionName ?: this.toString())
    }

    private val channel by lazy {
        MessagingChannel(
            json,
            connection,
            exchangeName,
            rpcExchangeName,
            routingKey,
            rpcQueueName
        )
    }

    fun publish(message: Message) = channel.send(message)
    suspend fun publishAndReceive(message: Message): Message = runBlocking {
        withContext(Dispatchers.Default) { channel.sendRPC(message) }
    }

    fun <T : Message> subscribe(messageType: KClass<T>, listener: (T) -> Unit) = channel.listen(messageType, listener)
    fun <T : Message> subscribeRPC(messageType: KClass<T>, listener: (T) -> Message) = channel.listenRPC(messageType, listener)

    fun close() {
        channel.close()
        connection.close()
    }
}