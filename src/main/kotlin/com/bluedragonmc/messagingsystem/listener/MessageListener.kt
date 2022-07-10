package com.bluedragonmc.messagingsystem.listener

import com.bluedragonmc.messagingsystem.message.Message
import kotlin.reflect.KClass

abstract class MessageListener<T : Message> {
    abstract val type: KClass<T>
}

data class PubSubMessageListener<T : Message>(override val type: KClass<T>, val listener: (T) -> Unit) :
    MessageListener<T>()

data class RPCMessageListener<T : Message>(override val type: KClass<T>, val listener: (T) -> Message) :
    MessageListener<T>()