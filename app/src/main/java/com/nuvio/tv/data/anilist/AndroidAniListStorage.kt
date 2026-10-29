package com.nuvio.tv.data.anilist

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AndroidAniListStorage @Inject constructor(@ApplicationContext context: Context) : AniListQueueStorage {
    private val root = File(context.noBackupFilesDir, "anilist").apply { mkdirs() }
    private val lock = Any()
    private fun file(profileId: Int, kind: String): AtomicFile {
        require(profileId in 1..6)
        return AtomicFile(File(root, "$profileId.$kind.json"))
    }
    private fun read(profileId: Int, kind: String): String? = synchronized(lock) {
        try { file(profileId, kind).openRead().bufferedReader().use { it.readText() } }
        catch (_: FileNotFoundException) { null }
    }
    private fun write(profileId: Int, kind: String, payload: String) = synchronized(lock) {
        val f = file(profileId, kind)
        val stream = f.startWrite()
        try { stream.write(payload.toByteArray()); f.finishWrite(stream) }
        catch (e: Throwable) { f.failWrite(stream); throw e }
    }
    override fun load(profileId: Int) = read(profileId, "queue")
    override fun save(profileId: Int, payload: String) = write(profileId, "queue", payload)
    fun snapshot(profileId: Int): AniListSyncSnapshot? = read(profileId, "snapshot")?.let {
        aniListJson.decodeFromString(AniListSyncSnapshot.serializer(), it)
    }
    fun saveSnapshot(profileId: Int, value: AniListSyncSnapshot) =
        write(profileId, "snapshot", aniListJson.encodeToString(AniListSyncSnapshot.serializer(), value))
    fun remove(profileId: Int) = synchronized(lock) {
        file(profileId, "queue").delete(); file(profileId, "snapshot").delete()
    }
}
