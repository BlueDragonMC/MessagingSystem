package com.bluedragonmc.messagingsystem

import com.bluedragonmc.messagingsystem.message.RPCErrorMessage
import com.bluedragonmc.messagingsystem.message.Message
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.reflect.KClass

class MessagingChannel(
    private val json: Json,
    connection: Connection,
    private val exchangeName: String = "bluedragon",
    private val rpcExchangeName: String = "",
    private val routingKey: String = "",
    private val rpcQueueName: String = "rpc_queue",
) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private lateinit var queueName: String
    private val channel: Channel = connection.createChannel()
    private val rpcChannel: Channel = connection.createChannel()
    private val listeners = mutableListOf<MessageListener<out Message>>()
    private val rpcListeners = mutableListOf<RPCMessageListener<out Message>>()

    private fun createPubSubReceiver() {
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
                (it as MessageListener<Message>).listener(decoded)
            }
        }, { consumerTag ->
            logger.warn("[$consumerTag] Cancel callback executed")
        }) { consumerTag, sig ->
            logger.warn("[$consumerTag] Shutdown: ${sig.message}")
        }
    }

    private fun createRPCReceiver() {
        logger.info("Declaring RPC queue")
        rpcChannel.queueDeclare(rpcQueueName, false, false, false, null)
        rpcChannel.queuePurge(rpcQueueName)
        rpcChannel.basicQos(1)

        rpcChannel.basicConsume(rpcQueueName, false, { consumerTag, delivery ->
            val decoded = json.decodeFromString<Message>(String(delivery.body, StandardCharsets.UTF_8))
            logger.debug("Received RPC Message: $decoded")
            // Check if any RPC listeners are subscribed to this message type.
            val rpcListener = rpcListeners.firstOrNull { it.type == decoded::class }
            if (rpcListener != null) {
                @Suppress("UNCHECKED_CAST") val response =
                    (rpcListener as RPCMessageListener<Message>).listener(decoded)

                val responseString = json.encodeToString(response)
                val bytes = responseString.toByteArray(StandardCharsets.UTF_8)

                logger.debug("[$consumerTag] Responding to RPC message:\n-> $decoded\n<- $response")

                rpcChannel.basicPublish(
                    rpcExchangeName,
                    delivery.properties.replyTo,
                    AMQP.BasicProperties.Builder().correlationId(delivery.properties.correlationId).build(),
                    bytes
                )
                logger.trace("Manually acknowledged RPC message $decoded")
                rpcChannel.basicAck(delivery.envelope.deliveryTag, false)

                // After the response has been handled, check if there are any non-responding listeners for this message type.
                listeners.filter {
                    it.type == response::class
                }.forEach {
                    logger.trace("Processing listener $it")
                    @Suppress("UNCHECKED_CAST") // The listener will always accept a subclass of [Message]
                    (it as MessageListener<Message>).listener(response)
                }
            } else {
                logger.error("No listener configured for RPC message type ${decoded::class}!")
                rpcChannel.basicPublish(
                    rpcExchangeName,
                    delivery.properties.replyTo,
                    AMQP.BasicProperties.Builder().correlationId(delivery.properties.correlationId).build(),
                    json.encodeToString(RPCErrorMessage("No listener configured for RPC message type ${decoded::class}")).toByteArray(StandardCharsets.UTF_8)
                )
                rpcChannel.basicNack(delivery.envelope.deliveryTag, false, false)
            }
        }, { consumerTag ->
            logger.warn("[$consumerTag] Cancel callback executed")
        })
    }

    init {
        createPubSubReceiver()
        createRPCReceiver()
        logger.debug("MessagingChannel fully initialized")
    }

    fun send(message: Message) {
        logger.debug("Sending Message: $message")
        val messageString = json.encodeToString(message)
        channel.basicPublish(exchangeName, routingKey, null, messageString.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendRPC(message: Message): Message {
        logger.debug("Sending RPC Message: $message")

        val bytes = json.encodeToString(message).toByteArray(StandardCharsets.UTF_8)
        val replyQueue = rpcChannel.queueDeclare().queue
        val correlationId = UUID.randomUUID().toString()

        logger.trace("Publishing to $rpcQueueName")
        rpcChannel.basicPublish(
            rpcExchangeName,
            rpcQueueName,
            AMQP.BasicProperties.Builder().correlationId(correlationId).replyTo(replyQueue).build(),
            bytes
        )

        val response = ArrayBlockingQueue<String>(1)
        val ctag = rpcChannel.basicConsume(replyQueue, true, { consumerTag, delivery ->
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
        rpcChannel.basicCancel(ctag)

        val decoded: Message = json.decodeFromString(result)
        if(decoded is RPCErrorMessage) {
            decoded.throwException()
        }
        return decoded
    }

    fun <T : Message> listenRPC(message: KClass<T>, listener: (T) -> Message) {
        logger.debug("Subscribed to RPC message type $message")
        rpcListeners.add(RPCMessageListener(message, listener))
    }

    fun <T : Message> listen(message: KClass<T>, listener: (T) -> Unit) {
        logger.debug("Subscribed to message type $message")
        listeners.add(MessageListener(message, listener))
    }

    fun close() {
        channel.close()
        rpcChannel.close()
    }
}