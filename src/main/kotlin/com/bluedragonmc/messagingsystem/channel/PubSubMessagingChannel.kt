package com.bluedragonmc.messagingsystem.channel

import com.bluedragonmc.messagingsystem.listener.PubSubMessageListener
import com.bluedragonmc.messagingsystem.message.Message
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

/**
 * Represents a pub/sub messaging channel as a wrapper around a RabbitMQ [Channel].
 * Can send messages and subscribe to received messages of a certain type.
 *
 * **Note**: This class is internal API and should not be used outside this library.
 * @author FluxCapacitor2
 */
internal class PubSubMessagingChannel(
    private val json: Json,
    private val channel: Channel,
    private val exchangeName: String,
    private val routingKey: String,
    writeOnly: Boolean
): Closeable {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private lateinit var queueName: String
    internal val listeners = mutableListOf<PubSubMessageListener<out Message>>()

    init {
        if(!writeOnly) {
            consume()
        } else logger.debug("Not listening for pub/sub messages because writeOnly is enabled.")
    }

    private fun consume() {
        logger.debug("Initializing exchange")
        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT)
        logger.debug("Declaring queue")
        queueName = channel.queueDeclare().queue
        channel.queueBind(queueName, exchangeName, routingKey)
        logger.debug("Created queue with name: $queueName")

        // Listen for pub/sub messages
        logger.debug("Starting to consume pub/sub messages")
        channel.basicConsume(queueName, { consumerTag, delivery ->
            val messageString = String(delivery.body ?: return@basicConsume, StandardCharsets.UTF_8)
            val decoded = json.decodeFromString<Message>(messageString)
            logger.debug("[$consumerTag] Message received: $decoded")

            // Check for non-RPC listeners that handle this message type
            listeners.filter {
                it.type == decoded::class
            }.forEach {
                @Suppress("UNCHECKED_CAST") // The listener will always accept a subclass of [Message]
                (it as PubSubMessageListener<Message>).listener(decoded)
            }
        }, { consumerTag ->
            logger.warn("[$consumerTag] Cancel callback executed")
        }) { consumerTag, sig ->
            logger.warn("[$consumerTag] Shutdown: ${sig.message}")
        }
    }

    fun send(message: Message) {
        logger.debug("Sending Message: $message")
        val messageString = json.encodeToString(message)
        channel.basicPublish(exchangeName, routingKey, null, messageString.toByteArray(StandardCharsets.UTF_8))
    }

    fun <T : Message> listen(message: KClass<T>, listener: (T) -> Unit) {
        logger.debug("Subscribed to message type $message")
        listeners.add(PubSubMessageListener(message, listener))
    }

    fun <T: Message> unsubscribeAll(messageType: KClass<T>) {
        logger.debug("Unsubscribed from all pub/sub messages of type $messageType")
        listeners.removeAll { it.type == messageType }
    }

    override fun close() {
        channel.close()
    }
}