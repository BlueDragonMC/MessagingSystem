package com.bluedragonmc.messagingsystem.listener

import com.bluedragonmc.messagingsystem.message.Message
import com.bluedragonmc.messagingsystem.message.RPCErrorMessage
import kotlin.reflect.KClass

abstract class MessageListener<T : Message> {
    abstract val type: KClass<T>
}

class PubSubMessageListener<T : Message>(override val type: KClass<T>, listener: (T) -> Unit) : MessageListener<T>() {
    val listener: (T) -> Unit = { message ->
        kotlin.runCatching { listener(message) }.onFailure { e -> e.printStackTrace() }
    }
}

class RPCMessageListener<T : Message>(override val type: KClass<T>, listener: (T) -> Message) : MessageListener<T>() {
    val listener: (T) -> Message = { message ->
        kotlin.runCatching { listener(message) }
            .getOrElse { e -> RPCErrorMessage("Error in RPC handler: ${e::class.simpleName}: ${e.message}") }
    }
}