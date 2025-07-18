package com.kelsos.mbrc.platform.mediasession

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import timber.log.Timber

class MediaButtonReceiver :
  BroadcastReceiver(),
  KoinComponent {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    Timber.v("Incoming %s", intent)
    if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
      return
    }
    getKoin().get<MediaIntentHandler>().handleMediaIntent(intent)
  }
}
