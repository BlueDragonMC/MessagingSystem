package com.bluedragonmc.messagingsystem.channel

import com.bluedragonmc.messagingsystem.listener.MessageListener
import com.bluedragonmc.messagingsystem.message.Message
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

abstract class MessagingChannel<R, T : MessageListener<*, R>>(defaultConsume: Boolean) : AutoCloseable {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val listeners = mutableListOf<T>()

    internal fun subscribe(listener: T) {
        logger.debug("Subscribed to message type ${listener.type}")
        listeners.add(listener)
    }

    internal fun <T : Message> unsubscribe(messageType: KClass<T>) {
        logger.debug("Unsubscribed from all messages of type $messageType")
        listeners.removeAll { it.type == messageType }
    }

    internal fun handle(message: Message): R? {
        // Check for listeners that handle this message type
        listeners.filter {
            it.type == message::class
        }.forEach {
            try {
                @Suppress("UNCHECKED_CAST") // The listener will always accept a subclass of [Message]
                return (it as MessageListener<Message, R>).listener(message)
            } catch (e: Throwable) {
                logger.warn("Error handling message: ${e.message}:")
                e.printStackTrace()
            }
        }
        return null
    }

    internal abstract fun send(message: Message): R

    /**
     * Start to consume messages and pass them to the [handle] method when they are received.
     * This method should open a RabbitMQ connection/channel if necessary.
     * It will be executed by default in this class's constructor if the `defaultConsume` parameter is true.
     */
    protected abstract fun consume()
    abstract override fun close()

    init {
        if (defaultConsume) consume()
    }
}
