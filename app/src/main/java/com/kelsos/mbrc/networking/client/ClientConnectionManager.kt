package com.kelsos.mbrc.networking.client

import com.kelsos.mbrc.common.state.ConnectionStatePublisher
import com.kelsos.mbrc.common.state.ConnectionStatus
import com.kelsos.mbrc.common.utilities.AppCoroutineDispatchers
import com.kelsos.mbrc.common.utilities.ScopeBase
import com.kelsos.mbrc.features.settings.ConnectionRepository
import com.kelsos.mbrc.networking.SocketActivityChecker
import com.kelsos.mbrc.networking.connections.toSocketAddress
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okio.buffer
import okio.sink
import okio.source
import timber.log.Timber
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface ClientConnectionManager {
  fun start()

  fun stop()
}

class ClientConnectionManagerImpl(
  private val activityChecker: SocketActivityChecker,
  private val messageHandler: MessageHandler,
  private val moshi: Moshi,
  private val connectionRepository: ConnectionRepository,
  private val connectionState: ConnectionStatePublisher,
  private val dispatchers: AppCoroutineDispatchers,
) : ScopeBase(dispatchers.io),
  ClientConnectionManager {
  private var connection: Connection? = null
  private var executor: ExecutorService? = null

  override fun start() {
    stop()
    launch {
      delay(DELAY_MS)
      val currentStatus = connectionState.connection.firstOrNull()
      if (currentStatus != ConnectionStatus.Connected) {
        attemptConnection()
      }
    }
  }

  private fun attemptConnection() {
    val default = connectionRepository.getDefault()

    if (default == null) {
      Timber.v("Skipping connection because of default missing")
      return
    }

    Timber.v("Attempting connection on $default")
    val onConnection: (Boolean) -> Unit = { connected ->
      launch {
        if (!connected) {
          activityChecker.stop()
          connectionState.updateConnection(ConnectionStatus.Offline)
        } else {
          connectionState.updateConnection(ConnectionStatus.Authenticating)
          messageHandler.startHandshake()
        }
      }
    }

    runCatching { Connection.connect(default.toSocketAddress()) }.fold(
      onSuccess = { socket ->
        val connection =
          Connection(socket, moshi, dispatchers).also { connection ->
            this.connection = connection
          }

        launch {
          connection.messages.collect { message ->
            messageHandler.processIncoming(message)
          }
        }

        launch {
          messageHandler.processOutgoing { message ->
            connection.send(message).fold(
              onSuccess = { true },
              onFailure = { exception ->
                Timber.e(exception, "Send failed")
                if (!connection.isConnected) {
                  connection.cleanup()
                  stop()
                }
              },
            )
          }
        }

        executor =
          Executors
            .newSingleThreadExecutor { runnable ->
              Thread(runnable, "SocketWorker")
            }.also {
              it.execute {
                Timber.v("Socket connection is running")
                onConnection(connection.isConnected)
                connection.listen()
                onConnection(connection.isConnected)
              }
            }

        activityChecker.start()
        activityChecker.setPingTimeoutListener {
          Timber.v("Timeout received resetting socket")
          connection.cleanup()
          activityChecker.stop()
        }
      },
      onFailure = { exception ->
        Timber.e(exception, "Connection failed")
        return
      },
    )
  }

  override fun stop() {
    executor?.shutdownNow()
    activityChecker.stop()
    connection?.cleanup()
  }

  companion object {
    private const val DELAY_MS = 2000L
  }
}

class Connection(
  private val socket: Socket,
  moshi: Moshi,
  dispatchers: AppCoroutineDispatchers,
) {
  private val sink = socket.sink().buffer()
  private val source = socket.source().buffer()
  private val adapter = moshi.adapter(SocketMessage::class.java)
  private val job = SupervisorJob()
  private val scope = CoroutineScope(job + dispatchers.io)
  private val _messages = MutableSharedFlow<SocketMessage>()
  val messages: Flow<SocketMessage> get() = _messages

  val isConnected get() = socket.isConnected

  fun cleanup() {
    job.cancel()
    if (sink.isOpen) {
      sink.flush()
      sink.close()
    }
    if (source.isOpen) {
      source.close()
    }

    if (socket.isConnected) {
      socket.close()
    }
  }

  fun send(message: SocketMessage) =
    runCatching {
      if (!isConnected) {
        Timber.d("Socket was not connected: skipping $message")
        return@runCatching
      }
      val address = socket.remoteSocketAddress
      Timber.v("Sending to mbrc:/$address (connected: $isConnected)::$message")
      adapter.toJson(sink, message)
      sink.writeUtf8(NEWLINE)
      sink.flush()
    }

  private fun emitMessages(rawMessage: String) {
    val replies =
      rawMessage
        .split("\r\n".toRegex())
        .dropLastWhile(String::isEmpty)

    for (reply in replies) {
      val result =
        runCatching {
          val message = checkNotNull(adapter.fromJson(reply))
          scope.launch {
            _messages.emit(message)
          }
        }

      if (result.isFailure) {
        val throwable = result.exceptionOrNull()
        Timber.e(throwable, "Failed processing $reply")
      }
    }
  }

  fun listen() {
    while (isConnected) {
      val result =
        runCatching {
          val rawMessage = checkNotNull(source.readUtf8Line()) { "no data" }
          if (rawMessage.isNotEmpty()) {
            emitMessages(rawMessage)
          }
        }
      if (result.isFailure) {
        Timber.e(checkNotNull(result.exceptionOrNull()), "Listener terminated")
        cleanup()
        return
      }
    }
  }

  companion object {
    private const val SO_TIMEOUT = 30_000
    private const val NEWLINE = "\r\n"

    fun connect(address: SocketAddress): Socket {
      val socket = Socket()
      socket.soTimeout = SO_TIMEOUT
      socket.connect(address)
      return socket
    }
  }
}
