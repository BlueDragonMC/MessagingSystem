package com.bluedragonmc.messagingsystem

import com.bluedragonmc.messagingsystem.channel.PubSubMessagingChannel
import com.bluedragonmc.messagingsystem.channel.RPCMessagingChannel
import com.bluedragonmc.messagingsystem.message.Message
import com.bluedragonmc.messagingsystem.serializer.UUIDSerializer
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.io.Closeable
import java.util.*
import kotlin.reflect.KClass

/**
 * A tiny wrapper on top of RabbitMQ allowing for pub/sub and RPC messages.
 * All operations which require a connection to a RabbitMQ server are done within instances of this class.
 * @author FluxCapacitor2
 */
class AMQPClient(
    /**
     * The hostname of the RabbitMQ server. Defaults to the system property `rabbitmq_host`, or if the property is not present, "rabbitmq".
     */
    private val hostname: String = System.getProperty("rabbitmq_host", "rabbitmq"),
    /**
     * The port of the RabbitMQ server. Defaults to the system property `rabbitmq_port`, or if the property is not present, 5672.
     * The default port of RabbitMQ servers is 5672.
     */
    private val port: Int = System.getProperty("rabbitmq_port", "5672").toInt(),
    /**
     * The [Json] instance used for serializing and deserializing messages.
     * All messages are converted to JSON before they are sent, and converted back into objects after they are received.
     * By default, it comes with the capability of serializing [UUID]s and does not pretty print when encoding.
     */
    json: Json = Json {
        prettyPrint = false
        serializersModule = SerializersModule {
            include(serializersModule)
            contextual(UUID::class, UUIDSerializer)
        }
    },
    /**
     * The name of the RabbitMQ exchange for pub/sub messaging.
     * Defaults to the system property `rabbitmq_exchange_name`, or if the property is not present, "bluedragon".
     */
    exchangeName: String = System.getProperty("rabbitmq_exchange_name", "bluedragon"),
    /**
     * The name of the RabbitMQ exchange for RPC messaging.
     * Defaults to the system property `rabbitmq_rpc_exchange_name`, or if the property is not present, "" (an empty string).
     */
    rpcExchangeName: String = System.getProperty("rabbitmq_rpc_exchange_name", ""),
    /**
     * The routing key used for sending and receiving pub/sub messages.
     * Defaults to the system property `rabbitmq_routing_key`, or if the property is not present, "" (an empty string).
     */
    routingKey: String = System.getProperty("rabbitmq_routing_key", ""),
    /**
     * The name of the RabbitMQ queue which all RPC messages are published to.
     * Defaults to the system property `rabbitmq_rpc_queue_name`, or if the property is not present, "rpc_queue".
     */
    rpcQueueName: String = System.getProperty("rabbitmq_rpc_queue_name", "rpc_queue"),
    /**
     * The connection name, which is supplied to RabbitMQ when a connection is made and displayed in the RabbitMQ server's logs.
     * Defaults to the value of this class's [toString] method.
     */
    connectionName: String? = null,
    /**
     * Determines whether the channels should be consumed.
     * Enabling [writeOnly] will prevent all listeners from working, but may use slightly less resources. When disabled, messages can be received by this [AMQPClient].
     * If [writeOnly] is enabled, RPC messages can still be sent and receive a response.
     * Defaults to `false`.
     */
    private val writeOnly: Boolean = false
): Closeable {

    private val connectionFactory by lazy {
        ConnectionFactory().apply {
            host = hostname
            port = this@AMQPClient.port
        }
    }

    private val connection by lazy {
        connectionFactory.newConnection(connectionName ?: this.toString())
    }

    private val pubSub by lazy {
        PubSubMessagingChannel(
            json, connection.createChannel(), exchangeName, routingKey, writeOnly
        )
    }

    private val rpc by lazy {
        RPCMessagingChannel(
            this, json, connection.createChannel(), rpcExchangeName, rpcQueueName, writeOnly
        )
    }

    internal fun getPubSubMessagingChannel() = pubSub

    /**
     * Send a pub/sub message to all subscribers. Subscribers will receive a copy of this [message] object. No response is expected.
     * @param message The message to send to all subscribers. Its class must be [kotlinx.serialization.Serializable] and a subclass of [Message].
     */
    fun publish(message: Message) = pubSub.send(message)

    /**
     * Send an RPC message to all subscribers and return the response. Consumers will receive a copy of this [message] object and must respond with another [Message].
     * @param message The message to send to all subscribers. Its class must be [kotlinx.serialization.Serializable] and a subclass of [Message].
     * @return A [Message] that was returned by the consumer (the application that used the [subscribeRPC] method to listen for RPC messages and respond to them)
     */
    suspend fun publishAndReceive(message: Message): Message = runBlocking {
        withContext(Dispatchers.Default) { rpc.publishAndReceive(message) }
    }

    /**
     * Subscribe to all pub/sub messages of a certain type.
     * @param messageType The type of message to subscribe to
     * @param listener A block that is executed when messages of type [messageType] are received.
     *                 Accepts the message as a parameter.
     */
    fun <T : Message> subscribe(messageType: KClass<T>, listener: (T) -> Unit) {
        require(!writeOnly) { "Cannot subscribe with writeOnly enabled" }
        pubSub.listen(messageType, listener)
    }

    /**
     * Remove all pub/sub subscriptions for a given message type.
     * @param messageType The type of message to remove all subscriptions from
     */
    fun <T : Message> unsubscribe(messageType: KClass<T>) = pubSub.unsubscribeAll(messageType)

    /**
     * Subscribe to all RPC messages of a certain type.
     * @param messageType The type of message to subscribe to
     * @param listener A block that is executed when messages of type [messageType] are received.
     *                 Accepts the message as a parameter and must return a [Message] as a response.
     */
    fun <T : Message> subscribeRPC(messageType: KClass<T>, listener: (T) -> Message) {
        require(!writeOnly) { "Cannot subscribe with writeOnly enabled" }
        rpc.listen(messageType, listener)
    }

    /**
     * Remove all RPC subscriptions for a given message type.
     * After this method is called and before any new consumers are registered,
     * all received RPC messages of this type will throw an `RPCMessagingError` because no handler is found.
     * @param messageType The type of message to unsubscribe from
     */
    fun <T : Message> unsubscribeRPC(messageType: KClass<T>) = rpc.unsubscribeAll(messageType)

    /**
     * Force the connection to be initialized before it is used for the first time.
     * This method will create a connection and two channels if they have not been created already.
     */
    fun preInitialize() {
        pubSub
        rpc
    }

    /**
     * Close the two created channels and the connection.
     * This [AMQPClient] cannot be used after it is closed.
     */
    override fun close() {
        pubSub.close()
        rpc.close()
        connection.close()
    }
}