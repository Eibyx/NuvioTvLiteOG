package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.data.anilist.AniListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AniListSettingsViewModel @Inject constructor(private val repository: AniListRepository) : ViewModel() {
    val state = repository.state
    fun connect() = repository.connect()
    fun disconnect() = repository.disconnect()
    fun sync() = repository.syncNow()
    fun cancel() = repository.cancelConnection()
    override fun onCleared() { repository.cancelConnection() }
}
