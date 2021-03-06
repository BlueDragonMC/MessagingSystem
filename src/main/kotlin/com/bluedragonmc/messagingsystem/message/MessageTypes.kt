package com.bluedragonmc.messagingsystem.message

import com.bluedragonmc.messagingsystem.exception.RPCMessagingException
import kotlinx.serialization.Serializable

interface Message

@Serializable
data class RPCErrorMessage(val message: String) : Message {
    fun throwException() {
        throw RPCMessagingException("RPC Error Received: $message")
    }
}
