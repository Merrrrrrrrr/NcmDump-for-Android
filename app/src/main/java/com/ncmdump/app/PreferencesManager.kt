package com.ncmdump.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Manages persistent settings using SharedPreferences.
 * Remembers the last-used NCM folder and output folder URIs.
 */
class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("ncmdump_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NCM_FOLDER_URI = "ncm_folder_uri"
        private const val KEY_OUTPUT_FOLDER_URI = "output_folder_uri"
        private const val KEY_REMOVE_SOURCE = "remove_source"
        private const val KEY_SHIZUKU_SOURCE_PATH = "shizuku_source_path"

        /** Default location of honor-music NCM files under Android/data. */
        const val DEFAULT_SHIZUKU_PATH =
            "/storage/emulated/0/Android/data/com.hihonor.cloudmusic/files/Documents/Music"
    }

    var ncmFolderUri: String?
        get() = prefs.getString(KEY_NCM_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_NCM_FOLDER_URI, value).apply()

    /** Last-used shell path for the Shizuku source (defaults to the honor-music dir). */
    var shizukuSourcePath: String
        get() = prefs.getString(KEY_SHIZUKU_SOURCE_PATH, null) ?: DEFAULT_SHIZUKU_PATH
        set(value) = prefs.edit().putString(KEY_SHIZUKU_SOURCE_PATH, value).apply()

    var outputFolderUri: String?
        get() = prefs.getString(KEY_OUTPUT_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_OUTPUT_FOLDER_URI, value).apply()

    var removeSourceAfterConvert: Boolean
        get() = prefs.getBoolean(KEY_REMOVE_SOURCE, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOVE_SOURCE, value).apply()

    /** Check whether the stored URI is still accessible */
    fun isUriStillValid(context: Context, uriString: String?): Boolean {
        if (uriString == null) return false
        return try {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.exists()
        } catch (_: Exception) {
            false
        }
    }
}
