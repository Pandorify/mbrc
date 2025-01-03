package com.kelsos.mbrc.platform.mediasession

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getString
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kelsos.mbrc.R
import com.kelsos.mbrc.common.state.AppStateFlow
import com.kelsos.mbrc.common.state.Duration
import com.kelsos.mbrc.common.state.PlayerState
import com.kelsos.mbrc.common.state.PlayingTrack
import com.kelsos.mbrc.common.state.orEmpty
import com.kelsos.mbrc.common.state.toMediaItem
import com.kelsos.mbrc.common.utilities.AppCoroutineDispatchers
import com.kelsos.mbrc.common.utilities.RemoteUtils
import com.kelsos.mbrc.networking.protocol.UserActionUseCase
import com.kelsos.mbrc.networking.protocol.VolumeModifyUseCase
import com.kelsos.mbrc.platform.mediasession.RemoteViewIntentBuilder.getPendingIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

interface AppNotificationManager {
  fun initialize()

  fun destroy()

  fun cancel(notificationId: Int = MEDIA_SESSION_NOTIFICATION_ID)

  fun updatePlayingTrack(playingTrack: PlayingTrack)

  fun updateState(
    state: PlayerState,
    current: Duration,
  )

  fun connectionStateChanged(connected: Boolean)

  fun createPlaceholder(): Notification

  companion object {
    const val MEDIA_SESSION_NOTIFICATION_ID = 15613
    const val CHANNEL_ID = "mbrc_session_01"
  }
}

class AppNotificationManagerImpl(
  private val context: Application,
  private val dispatchers: AppCoroutineDispatchers,
  private val notificationManager: NotificationManager,
  private val appState: AppStateFlow,
  private val userActionUseCase: UserActionUseCase,
  private val volumeModifyUseCase: VolumeModifyUseCase,
) : AppNotificationManager {
  private val sessionJob: Job = Job()
  private val scope: CoroutineScope = CoroutineScope(sessionJob)

  private var notification: Notification? = null
  private var notificationData: NotificationData = NotificationData()
  private var mediaSession: MediaSession? = null

  init {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.createNotificationChannel(createChannel(context))
    }
  }

  private suspend fun update(notificationData: NotificationData) {
    notification = createBuilder(notificationData).build()

    withContext(dispatchers.main) {
      notificationManager.notify(AppNotificationManager.MEDIA_SESSION_NOTIFICATION_ID, notification)
    }
  }

  @OptIn(UnstableApi::class)
  private fun createBuilder(notificationData: NotificationData): NotificationCompat.Builder {
    val style = MediaStyleNotificationHelper.MediaStyle(requireNotNull(mediaSession))
    val builder = NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_ID)

    builder
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSmallIcon(R.drawable.ic_mbrc_status)
      .setStyle(style)

    builder.priority = NotificationCompat.PRIORITY_LOW
    builder.setOnlyAlertOnce(true)

    if (notificationData.cover != null) {
      builder.setLargeIcon(this.notificationData.cover)
    } else {
      val icon = BitmapFactory.decodeResource(context.resources, R.drawable.ic_image_no_cover)
      builder.setLargeIcon(icon)
    }

    with(notificationData.track) {
      builder
        .setContentTitle(title)
        .setContentText(artist)
        .setSubText(album)
    }

    builder.setContentIntent(getPendingIntent(RemoteIntentCode.Open, context))

    return builder
  }

  @OptIn(UnstableApi::class)
  override fun initialize() {
    if (mediaSession != null) {
      return
    }
    val player = RemotePlayer(context, userActionUseCase, volumeModifyUseCase, appState, dispatchers, scope)

    val mediaSessionCallback =
      object : MediaSession.Callback {
        override fun onPlaybackResumption(
          mediaSession: MediaSession,
          controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
          val item =
            runBlocking {
              val currentPlayingTrack = appState.playingTrack.firstOrNull()
              currentPlayingTrack
                .orEmpty()
                .toMediaItem()
            }
          return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0))
        }
      }
    mediaSession =
      MediaSession
        .Builder(context, player)
        .setCallback(mediaSessionCallback)
        .setId(AppNotificationManager.MEDIA_SESSION_NOTIFICATION_ID.toString())
        .build()
  }

  override fun destroy() {
    mediaSession?.run {
      player.release()
      release()
      mediaSession = null
    }
    scope.cancel()
  }

  override fun cancel(notificationId: Int) {
    notificationManager.cancel(notificationId)
  }

  override fun updatePlayingTrack(playingTrack: PlayingTrack) {
    scope.launch {
      val coverUrl = playingTrack.coverUrl
      val cover =
        if (coverUrl.isEmpty()) {
          null
        } else {
          val uri = coverUrl.toUri()
          RemoteUtils.loadBitmap(checkNotNull(uri.path)).getOrNull()
        }
      notificationData = notificationData.copy(track = playingTrack, cover = cover)
      update(notificationData)
    }
  }

  override fun connectionStateChanged(connected: Boolean) {
    if (connected) {
      cancel(AppNotificationManager.MEDIA_SESSION_NOTIFICATION_ID)
    } else {
      notification = createBuilder(this.notificationData).build()
    }
  }

  override fun updateState(
    state: PlayerState,
    current: Duration,
  ) {
    scope.launch {
      notificationData = notificationData.copy(playerState = state)
      update(notificationData)
    }
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createChannel(context: Context): NotificationChannel {
    val channelName = context.getString(R.string.notification__session_channel_name)
    val channelDescription = context.getString(R.string.notification__session_channel_description)

    val channel =
      NotificationChannel(
        AppNotificationManager.CHANNEL_ID,
        channelName,
        NotificationManager.IMPORTANCE_DEFAULT,
      )

    return channel.apply {
      this.description = channelDescription
      enableLights(false)
      enableVibration(false)
      setSound(null, null)
    }
  }

  override fun createPlaceholder(): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = createChannel(context)
      val manager = NotificationManagerCompat.from(context)
      manager.createNotificationChannel(channel)
    }

    val cancelIntent = getPendingIntent(RemoteIntentCode.Cancel, context)
    val action =
      Action
        .Builder(
          R.drawable.ic_close_black_24dp,
          getString(context, android.R.string.cancel),
          cancelIntent,
        ).build()

    return NotificationCompat
      .Builder(context, AppNotificationManager.CHANNEL_ID)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSmallIcon(R.drawable.ic_mbrc_status)
      .setContentTitle(getString(context, R.string.application_name))
      .addAction(action)
      .setContentText(getString(context, R.string.application_starting))
      .build()
  }
}
