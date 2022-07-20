package com.bluedragonmc.messagingsystem.channel

import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.messagingsystem.listener.RPCMessageListener
import com.bluedragonmc.messagingsystem.message.Message
import com.bluedragonmc.messagingsystem.message.RPCErrorMessage
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ArrayBlockingQueue

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
) : MessagingChannel<Message, RPCMessageListener<*>>(!writeOnly) {

    override fun consume() {
        logger.info("Declaring RPC queue")
        channel.queueDeclare(rpcQueueName, false, false, false, null)
        channel.queuePurge(rpcQueueName)
        channel.basicQos(1)

        channel.basicConsume(rpcQueueName, false, { consumerTag, delivery ->
            val decoded = json.decodeFromString<Message>(String(delivery.body, StandardCharsets.UTF_8))
            logger.debug("Received RPC Message: $decoded")
            // Check if any RPC listeners are subscribed to this message type.
            val response = handle(decoded)
            if (response != null) {
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
                client.getPubSubMessagingChannel().handle(response)
            } else {
                logger.error("No response was given for RPC message $decoded!")
                channel.basicPublish(
                    rpcExchangeName,
                    delivery.properties.replyTo,
                    AMQP.BasicProperties.Builder().correlationId(delivery.properties.correlationId).build(),
                    json.encodeToString(RPCErrorMessage("No response given for RPC message $decoded!"))
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                )
                channel.basicNack(delivery.envelope.deliveryTag, false, false)
            }
        }, { consumerTag ->
            logger.warn("[$consumerTag] Cancel callback executed")
        })
    }

    override fun send(message: Message): Message {
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
            if (delivery.properties.correlationId == correlationId) {
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
        if (decoded is RPCErrorMessage) {
            decoded.throwException()
        }
        logger.trace("Received RPC response: $decoded")
        return decoded
    }

    override fun close() {
        channel.close()
    }
}