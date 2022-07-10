package com.bluedragonmc.messagingsystem

import com.bluedragonmc.messagingsystem.message.Message
import kotlin.reflect.KClass

data class MessageListener<T: Message>(val type: KClass<T>, val listener: (T) -> Unit)
data class RPCMessageListener<T: Message>(val type: KClass<T>, val listener: (T) -> Message)