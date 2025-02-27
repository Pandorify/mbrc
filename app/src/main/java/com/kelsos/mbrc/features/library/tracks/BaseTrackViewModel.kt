package com.kelsos.mbrc.features.library.tracks

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.kelsos.mbrc.features.library.BaseLibraryViewModel
import com.kelsos.mbrc.features.queue.Queue
import com.kelsos.mbrc.features.queue.QueueHandler
import com.kelsos.mbrc.features.settings.BasicSettingsHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

abstract class BaseTrackViewModel(
  private val queueHandler: QueueHandler,
  settingsHelper: BasicSettingsHelper,
) : BaseLibraryViewModel<TrackUiMessage>(settingsHelper) {
  abstract val tracks: Flow<PagingData<Track>>

  fun queue(
    action: Queue,
    track: Track,
  ) {
    val queueAction = getQueueAction(action)

    viewModelScope.launch {
      val result =
        if (queueAction == Queue.Default) {
          queueHandler.queueTrack(track = track, queueAction)
        } else {
          queueHandler.queueTrack(track = track)
        }

      val message =
        if (result.success) {
          TrackUiMessage.QueueSuccess(result.tracks)
        } else {
          TrackUiMessage.QueueFailed
        }

      emit(message)
    }
  }
}
