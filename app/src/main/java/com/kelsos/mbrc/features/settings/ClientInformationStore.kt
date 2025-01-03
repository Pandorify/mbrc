package com.kelsos.mbrc.features.settings

import android.content.SharedPreferences
import java.util.UUID

fun interface ClientInformationStore {
  suspend fun getClientId(): String
}

class ClientInformationStoreImpl(
  private val sharedPreference: SharedPreferences,
) : ClientInformationStore {
  override suspend fun getClientId(): String {
    val uuid = sharedPreference.getString(UUID_KEY, "").orEmpty()

    return uuid.ifBlank {
      val newUuid = UUID.randomUUID().toString()
      sharedPreference
        .edit()
        .putString(UUID_KEY, newUuid)
        .apply()
      newUuid
    }
  }

  companion object {
    const val UUID_KEY = "uuid"
  }
}
