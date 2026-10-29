package com.nuvio.tv.data.anilist

/** Keeps each profile's pending AniList sends, so they survive a closed app, a crash or no network. */
internal interface AniListQueueStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)

    /** The profile whose queue was opened last, for the background job of a freshly started app. */
    fun lastProfile(): Int? = null
    fun saveLastProfile(profileId: Int) = Unit
}
