package com.nuvio.tv.core.cloudstorage

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/**
 * Everything a profile keeps about its cloud accounts, secrets included. Stored as one value
 * encrypted with the Android Keystore, in a store of its own: it is not part of the settings
 * export, the Nuvio cloud sync or anything plugins can read.
 */
@Serializable
internal data class StoredCloud(
    val accounts: List<CloudAccount> = emptyList(),
    val credentials: Map<String, CloudCredentials> = emptyMap()
) {
    override fun toString(): String = "StoredCloud(accounts=${accounts.size})"
}

internal interface CloudPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

/** The file index of each account: names, folders and sizes, never a secret. */
internal interface CloudIndexStore {
    fun read(profileId: Int, accountId: String): String?
    fun write(profileId: Int, accountId: String, value: String)
    fun delete(profileId: Int, accountId: String)
    fun deleteProfile(profileId: Int)
    fun clear()
}

@Singleton
internal class AndroidCloudPersistence @Inject constructor(
    @ApplicationContext context: Context
) : CloudPersistence {
    private val preferences = ProtectedProfilePreferences(context, "cloud_storage", "com.nuvio.tv.cloudstorage.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

/**
 * `noBackupFilesDir/cloud_index/<profile>/<account>.json`: on disk, so nothing stays in memory
 * between stream searches, and outside Android backups.
 */
@Singleton
internal class AndroidCloudIndexStore @Inject constructor(
    @ApplicationContext context: Context
) : CloudIndexStore {
    private val root = File(context.noBackupFilesDir, "cloud_index")

    private fun file(profileId: Int, accountId: String): File {
        require(accountId.matches(SAFE_ID)) { "Invalid account id" }
        return File(File(root, profileId.toString()), "$accountId.json")
    }

    override fun read(profileId: Int, accountId: String): String? =
        file(profileId, accountId).takeIf { it.isFile }?.readText()

    override fun write(profileId: Int, accountId: String, value: String) {
        val target = file(profileId, accountId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(value)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    override fun delete(profileId: Int, accountId: String) {
        file(profileId, accountId).delete()
    }

    override fun deleteProfile(profileId: Int) {
        File(root, profileId.toString()).deleteRecursively()
    }

    override fun clear() {
        root.deleteRecursively()
    }

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
