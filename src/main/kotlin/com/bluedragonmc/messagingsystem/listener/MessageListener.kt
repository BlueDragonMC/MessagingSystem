package com.bluedragonmc.messagingsystem.listener

import com.bluedragonmc.messagingsystem.message.Message
import kotlin.reflect.KClass

abstract class MessageListener<T : Message, R> {
    abstract val type: KClass<T>
    abstract val listener: (T) -> R
}

class PubSubMessageListener<T : Message>(override val type: KClass<T>, override val listener: (T) -> Unit) : MessageListener<T, Unit>()

class RPCMessageListener<T : Message>(override val type: KClass<T>, override val listener: (T) -> Message) : MessageListener<T, Message>()