package com.androidharness.app.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.cloudStore by preferencesDataStore(name = "cloud_claude")

@Serializable
data class CloudPrefs(
    /** Optional override; builds normally carry GITHUB_CLIENT_ID. */
    val clientId: String = "",
    val repositoryName: String = "cloud-workspace",
    val idleTimeoutMinutes: Int = 30,
    val machine: String = "",
    /** `providerId/model` so a renamed provider still resolves. */
    val mainModel: String = "",
    val fastModel: String = "",
    val permission: CloudPermissionMode = CloudPermissionMode.ASK,
    /** Stop the Codespace when a task finishes, so idle minutes are not billed. */
    val autoStop: Boolean = false,
    val codespaceName: String = "",
) {
    companion object {
        const val DEFAULT_REPOSITORY = "cloud-workspace"
    }
}

/** Device-local preferences for the cloud section. No secrets live here. */
class CloudSettings(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("prefs")

    val prefs: Flow<CloudPrefs> = context.cloudStore.data.map { values ->
        values[key]?.let { runCatching { json.decodeFromString<CloudPrefs>(it) }.getOrNull() } ?: CloudPrefs()
    }

    suspend fun current(): CloudPrefs = prefs.first()

    suspend fun update(transform: (CloudPrefs) -> CloudPrefs) {
        context.cloudStore.edit { values ->
            val existing = values[key]?.let { runCatching { json.decodeFromString<CloudPrefs>(it) }.getOrNull() }
                ?: CloudPrefs()
            values[key] = json.encodeToString(CloudPrefs.serializer(), transform(existing))
        }
    }
}
