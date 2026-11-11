// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

/** How a folder on the TV's own storage or a USB drive is read. Stored with each local folder. */
enum class LocalAccessMethod(val id: String) {
    /** Storage Access Framework: a tree URI with a persisted read grant. */
    SAF("saf"),
    /** API 24–29 without a document picker: `READ_EXTERNAL_STORAGE` and plain files. */
    LEGACY_FILES("legacy_files"),
    /** API 30+ without a document picker: "All files access" (`MANAGE_EXTERNAL_STORAGE`). */
    ALL_FILES("all_files"),
    /** Files sent from a phone over the local network into the app's own storage. */
    PHONE_UPLOAD("phone_upload");

    companion object {
        fun fromId(id: String?): LocalAccessMethod? = entries.firstOrNull { it.id == id }
    }
}

/** What the TV offers, found at run time (never by model). */
internal data class LocalAccessCapabilities(
    val sdkInt: Int,
    /** `ACTION_OPEN_DOCUMENT_TREE` resolves to an activity. */
    val documentTreeResolves: Boolean,
    /** `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` resolves to an activity. */
    val allFilesSettingsResolves: Boolean
)

internal data class LocalAccessOption(
    val method: LocalAccessMethod,
    /** For [LocalAccessMethod.ALL_FILES] without a settings screen: the grant is made with adb. */
    val needsAdb: Boolean = false
)

internal object LocalAccessPlanner {
    /**
     * The options in the order they are offered: the document picker when the TV has one; without
     * it, plain files with the storage permission up to Android 10, or "All files access" from
     * Android 11 on (subtitle and font files are not media, so the read permission does not reach
     * them there). Sending from a phone is always there.
     */
    fun options(capabilities: LocalAccessCapabilities): List<LocalAccessOption> = buildList {
        if (capabilities.documentTreeResolves) {
            add(LocalAccessOption(LocalAccessMethod.SAF))
        } else if (capabilities.sdkInt in 24..29) {
            add(LocalAccessOption(LocalAccessMethod.LEGACY_FILES))
        } else if (capabilities.sdkInt >= 30) {
            add(LocalAccessOption(LocalAccessMethod.ALL_FILES, needsAdb = !capabilities.allFilesSettingsResolves))
        }
        add(LocalAccessOption(LocalAccessMethod.PHONE_UPLOAD))
    }

    const val ADB_GRANT_COMMAND = "adb shell appops set com.eibyx.nuvioarckai MANAGE_EXTERNAL_STORAGE allow"
}
