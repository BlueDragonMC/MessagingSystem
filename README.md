# MessagingSystem
A tiny wrapper on top of RabbitMQ allowing for pub/sub and RPC messages.

Depends on: 
[`kotlinx.serialization`](https://kotlinlang.org/docs/serialization.html#example-json-serialization),
[`kotlinx.coroutines`](https://kotlinlang.org/docs/multiplatform-mobile-concurrency-and-coroutines.html#coroutines),
and [`amqp-client`](https://www.rabbitmq.com/java-client.html)

It is recommended to use any SLF4J implementation, like
[`logback`](https://github.com/qos-ch/logback),
to enable logging for this project. To enable Kotlin reflection in logs, add
[`kotlin-reflect`](https://kotlinlang.org/docs/reflection.html#jvm-dependency)
to your project.

## Installation
Install with Gradle:
```kotlin
repositories {
    maven(url = "https://jitpack.io")
}
dependencies {
    implementation("com.github.bluedragonmc:messagingsystem:$version")
}
```

## Usage
### Creating Messages
All message classes must be serializable with
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization#kotlin-multiplatform--multi-format-reflectionless-serialization=)
and <u>must implement `Message`</u>.
UUIDs have a contextual serializer, so they can be serialized by adding the `@Contextual` annotation to their declarations.
```kotlin
@Serializable // Make sure this is `kotlinx.serialization.Serializable` and not `java.io.Serializable`
data class MyMessage(val greeting: String) : Message

@Serializable
data class MyResponse(val welcome: String) : Message

@Serializable
data class UUIDExample(val username: String, @Contextual val uuid: UUID) : Message

/*
Due to a security restriction of kotlinx.serialization, ALL messages must be registered as subclasses using a polymorphic module builder.
This will be passed to the AMQPClient in the next step.
 */
val polymorphicModuleBuilder: PolymorphicModuleBuilder<Message>.() -> Unit = {
    subclass(MyMessage::class)
    subclass(MyResponse::class)
    subclass(UUIDExample::class)
}
```
### Connect
```kotlin
// RabbitMQ's default port is 5672
val client = AMQPClient(hostname = "127.0.0.1", port = 5672, polymorphicModuleBuilder = polymorphicModuleBuilder)
// Every client will only make one connection and open two channels: one for pub/sub and one for RPC.
// This instance should be kept and used for every method call.
```
#### Closing the connection
Resources should be closed when you are done using them:
```kotlin
client.close()
// This will close the two created channels and the connection.
// `AMQPClient`s cannot be used after they are closed.
```
`AMQPClient` also implements the `Closeable` interface, so it can be used in a `use` block. It will be closed automatically after the block has finished executing.
### `AMQPClient` properties:
System properties will be used if no value is provided to the parameter.
If a system property value was not found, the default is used. See [AMQPClient](https://github.com/BlueDragonMC/MessagingSystem/blob/main/src/main/kotlin/com/bluedragonmc/messagingsystem/AMQPClient.kt) for descriptions of each of these properties.

| Property                                                              | System Property              | Default                        |
|-----------------------------------------------------------------------|------------------------------|--------------------------------|
| hostname: String                                                      | `rabbitmq_host`              | "rabbitmq"                     |
| port: Int                                                             | `rabbitmq_port`              | 5672                           |
| serializersModuleBuilder: SerializersModuleBuilder.() -> Unit         |                              | {}                             |
| polymorphicModuleBuilder: PolymorphicModuleBuilder.() -> Unit         |                              |                                |
| exchangeName: String                                                  | `rabbitmq_exchange_name`     | "bluedragon"                   |
| rpcExchangeName: String                                               | `rabbitmq_rpc_exchange_name` | ""                             |
| routingKey: String                                                    | `rabbitmq_routing_key`       | ""                             |
| rpcQueueName: String                                                  | `rabbitmq_rpc_queue_name`    | "rpc_queue"                    |
| connectionName: String?                                               |                              | Value of `AMQPClient#toString` |
| writeOnly: Boolean                                                    |                              | false                          |

ℹ️ ️`polymorphicModuleBuilder`, `exchangeName`, `rpcExchangeName`, `routingKey`, `rpcQueueName` should use the same values for all instances of this program. If not, some messages may not be received properly.

### Pub/Sub
#### Subscribe
```kotlin
client.subscribe(MyMessage::class) { message ->
    // `message` is guaranteed to be of type MyMessage
    logger.info("Greeting received: ${message.greeting}")
}
// Unsubscribe
client.unsubscribe(MyMessage::class)
```
#### Publish
```kotlin
client.publish(MyMessage("Hello, world!"))
// This message will be passed to all subscribers of MyMessage.
```
### RPC
RPC stands for [remote-procedure call](https://en.wikipedia.org/wiki/Remote_procedure_call), and it is used to perform operations on a remote server and return a response.
The usage is very similar to pub/sub messaging, but all listeners must return a `Message` as a response.

⚠️  Keep in mind: this library is not designed for multiple RPC consumers and has not been tested in this kind of environment.
#### Await messages and reply
```kotlin
client.subscribeRPC(MyMessage::class) { message ->
    // Just like in the `subscribe()` example, `message` is guaranteed to be MyMessage
    return MyResponse("Welcome!") // This message will be returned to the sender. 
                                  // It can be any subclass of `Message`.
}
// Unsubscribe
client.unsubscribeRPC(MyMessage::class)
```
#### Send and await response
ℹ️ This method is a `suspend fun`, so it must be called from a coroutine or another suspend function.
```kotlin
val response = client.publishAndReceive(MyMessage("Hello, world!"))
// `response` can be any subclass of `Message`, so manual type checking is required
```
### Exception Handling
If an uncaught exception occurs in an RPC handler, an `RPCErrorMessage` is sent back to the receiver, which will cause an `RPCMessagingException` to be thrown on the receiving end.
This signifies the exception occurred on the **server**, not the client.

### Initialization
The RabbitMQ connection and channels are not initialized until they are first used. If you want to pre-initialize them, use `client.preInitialize()`

## 🚧 Disclaimer
This project is far from production-ready and should not be trusted for mission-critical data.
It was built to meet the needs of BlueDragon, with brevity and simplicity in mind over reliability.