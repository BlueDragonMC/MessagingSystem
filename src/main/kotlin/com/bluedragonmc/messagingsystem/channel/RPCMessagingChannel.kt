package com.bluedragonmc.messagingsystem.channel

import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.messagingsystem.listener.PubSubMessageListener
import com.bluedragonmc.messagingsystem.listener.RPCMessageListener
import com.bluedragonmc.messagingsystem.message.Message
import com.bluedragonmc.messagingsystem.message.RPCErrorMessage
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.reflect.KClass

/**
 * Represents an RPC messaging channel as a wrapper around a RabbitMQ [Channel].
 * Can send messages and receive responses, and subscribe to those messages to return responses.
 *
 * **Note**: This class is internal API and should not be used outside this library.
 * @author FluxCapacitor2
 */
internal class RPCMessagingChannel(
    private val client: AMQPClient,
    private val json: Json,
    private val channel: Channel,
    private val rpcExchangeName: String,
    private val rpcQueueName: String,
    writeOnly: Boolean
) : Closeable {
    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val listeners = mutableListOf<RPCMessageListener<out Message>>()
    
    init {
        if(!writeOnly) {
            consume()
        } else logger.debug("Not listening for RPC messages because writeOnly is enabled.")
    }

    private fun consume() {
        logger.info("Declaring RPC queue")
        channel.queueDeclare(rpcQueueName, false, false, false, null)
        channel.queuePurge(rpcQueueName)
        channel.basicQos(1)

        channel.basicConsume(rpcQueueName, false, { consumerTag, delivery ->
            val decoded = json.decodeFromString<Message>(String(delivery.body, StandardCharsets.UTF_8))
            logger.debug("Received RPC Message: $decoded")
            // Check if any RPC listeners are subscribed to this message type.
            val rpcListener = listeners.firstOrNull { it.type == decoded::class }
            if (rpcListener != null) {
                @Suppress("UNCHECKED_CAST") val response =
                    (rpcListener as RPCMessageListener<Message>).listener(decoded)

                val responseString = json.encodeToString(response)
                val bytes = responseString.toByteArray(StandardCharsets.UTF_8)

                logger.debug("[$consumerTag] Responding to RPC message:\n-> $decoded\n<- $response")

                channel.basicPublish(
                    rpcExchangeName,
                    delivery.properties.replyTo,
                    AMQP.BasicProperties.Builder().correlationId(delivery.properties.correlationId).build(),
                    bytes
                )
                logger.trace("Manually acknowledged RPC message $decoded")
                channel.basicAck(delivery.envelope.deliveryTag, false)

                // After the response has been handled, check if there are any pub/sub listeners for this message type.
                client.getPubSubMessagingChannel().listeners.filter {
                    it.type == response::class
                }.forEach {
                    logger.trace("Processing listener $it")
                    @Suppress("UNCHECKED_CAST") // The listener will always accept a subclass of [Message]
                    (it as PubSubMessageListener<Message>).listener(response)
                }
            } else {
                logger.error("No listener configured for RPC message type ${decoded::class}!")
                channel.basicPublish(
                    rpcExchangeName,
                    delivery.properties.replyTo,
                    AMQP.BasicProperties.Builder().correlationId(delivery.properties.correlationId).build(),
                    json.encodeToString(RPCErrorMessage("No listener configured for RPC message type ${decoded::class}")).toByteArray(
                        StandardCharsets.UTF_8)
                )
                channel.basicNack(delivery.envelope.deliveryTag, false, false)
            }
        }, { consumerTag ->
            logger.warn("[$consumerTag] Cancel callback executed")
        })
    }

    fun publishAndReceive(message: Message): Message {
        logger.debug("Sending RPC Message: $message")

        val bytes = json.encodeToString(message).toByteArray(StandardCharsets.UTF_8)
        val replyQueue = channel.queueDeclare().queue
        val correlationId = UUID.randomUUID().toString()

        logger.trace("Publishing to $rpcQueueName")
        channel.basicPublish(
            rpcExchangeName,
            rpcQueueName,
            AMQP.BasicProperties.Builder().correlationId(correlationId).replyTo(replyQueue).build(),
            bytes
        )

        val response = ArrayBlockingQueue<String>(1)
        val ctag = channel.basicConsume(replyQueue, true, { consumerTag, delivery ->
            // Deliver callback
            if(delivery.properties.correlationId == correlationId) {
                val body = String(delivery.body, StandardCharsets.UTF_8)
                logger.debug("[$consumerTag] RPC Response Received: $body")
                response.offer(body)
            }
        }, { consumerTag ->
            // Cancel callback
            logger.warn("[$consumerTag] Cancel callback executed")
        })

        val result = response.take()
        channel.basicCancel(ctag)

        val decoded: Message = json.decodeFromString(result)
        if(decoded is RPCErrorMessage) {
            decoded.throwException()
        }
        logger.trace("Received RPC response: $decoded")
        return decoded
    }

    fun <T : Message> listen(message: KClass<T>, listener: (T) -> Message) {
        logger.debug("Subscribed to RPC message type $message")
        listeners.add(RPCMessageListener(message, listener))
    }

    fun <T: Message> unsubscribeAll(messageType: KClass<T>) {
        logger.debug("Unsubscribed from all RPC messages of type $messageType")
        listeners.removeAll { it.type == messageType }
    }

    override fun close() {
        channel.close()
    }
}