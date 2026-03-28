package com.apidebug.inspector.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Environment
import com.apidebug.inspector.capture.VpnRoutingMode
import com.apidebug.inspector.models.AppSettings
import com.apidebug.inspector.models.RuleDefinition
import com.apidebug.inspector.models.TrafficOutcome
import com.apidebug.inspector.models.TrafficRecord
import com.apidebug.inspector.models.TrafficRecordDraft
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _settings.value = _settings.value.copy(darkTheme = enabled)
    }

    fun setSessionActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, active).apply()
        _settings.value = _settings.value.copy(sessionActive = active)
    }

    fun setRequestTimeout(timeoutMs: Int) {
        prefs.edit().putInt(KEY_TIMEOUT_MS, timeoutMs).apply()
        _settings.value = _settings.value.copy(requestTimeoutMs = timeoutMs)
    }

    fun setLastExportPath(path: String) {
        prefs.edit().putString(KEY_LAST_EXPORT_PATH, path).apply()
        _settings.value = _settings.value.copy(lastExportPath = path)
    }

    fun setVpnRoutingMode(mode: VpnRoutingMode) {
        prefs.edit().putString(KEY_VPN_ROUTING_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(vpnRoutingMode = mode)
    }

    private fun readSettings(): AppSettings = AppSettings(
        darkTheme = prefs.getBoolean(KEY_DARK_THEME, true),
        sessionActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false),
        requestTimeoutMs = prefs.getInt(KEY_TIMEOUT_MS, 20_000),
        lastExportPath = prefs.getString(KEY_LAST_EXPORT_PATH, "") ?: "",
        vpnRoutingMode = prefs.getString(KEY_VPN_ROUTING_MODE, VpnRoutingMode.OBSERVE_ONLY.name)
            ?.let { stored ->
                runCatching { VpnRoutingMode.valueOf(stored) }.getOrDefault(VpnRoutingMode.OBSERVE_ONLY)
            }
            ?: VpnRoutingMode.OBSERVE_ONLY
    )

    companion object {
        private const val PREFS_NAME = "api_debug_inspector_settings"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val KEY_LAST_EXPORT_PATH = "last_export_path"
        private const val KEY_VPN_ROUTING_MODE = "vpn_routing_mode"
    }
}

class RuleRepository(
    private val databaseHelper: InspectorDatabaseHelper,
    private val gson: Gson
) {
    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<List<RuleDefinition>> = _rules.asStateFlow()

    suspend fun upsert(rule: RuleDefinition): RuleDefinition = withContext(Dispatchers.IO) {
        val database = databaseHelper.writableDatabase
        if (rule.id == 0L) {
            val newId = database.insert(TABLE_RULES, null, ContentValues().apply {
                put(COL_RULE_JSON, gson.toJson(rule.copy(id = 0L)))
            })
            refresh()
            return@withContext rule.copy(id = newId)
        }

        database.update(
            TABLE_RULES,
            ContentValues().apply { put(COL_RULE_JSON, gson.toJson(rule.copy(id = 0L))) },
            "$COL_ID = ?",
            arrayOf(rule.id.toString())
        )
        refresh()
        rule
    }

    suspend fun delete(ruleId: Long) = withContext(Dispatchers.IO) {
        databaseHelper.writableDatabase.delete(TABLE_RULES, "$COL_ID = ?", arrayOf(ruleId.toString()))
        refresh()
    }

    suspend fun replaceAll(newRules: List<RuleDefinition>) = withContext(Dispatchers.IO) {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_RULES, null, null)
            newRules.forEach { rule ->
                database.insert(TABLE_RULES, null, ContentValues().apply {
                    put(COL_RULE_JSON, gson.toJson(rule.copy(id = 0L)))
                })
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        refresh()
    }

    fun snapshot(): List<RuleDefinition> = _rules.value

    private fun refresh() {
        _rules.value = loadRules()
    }

    private fun loadRules(): List<RuleDefinition> {
        val database = databaseHelper.readableDatabase
        val result = mutableListOf<RuleDefinition>()
        database.query(
            TABLE_RULES,
            arrayOf(COL_ID, COL_RULE_JSON),
            null,
            null,
            null,
            null,
            "$COL_ID ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
                val payload = cursor.getString(cursor.getColumnIndexOrThrow(COL_RULE_JSON))
                runCatching {
                    gson.fromJson(payload, RuleDefinition::class.java).copy(id = id)
                }.onSuccess(result::add)
            }
        }
        return result
    }

    companion object {
        private const val TABLE_RULES = "rules"
        private const val COL_ID = "id"
        private const val COL_RULE_JSON = "payload_json"
    }
}

class TrafficRepository(
    context: Context,
    private val databaseHelper: InspectorDatabaseHelper
) {
    private val appContext = context.applicationContext
    private val bodyDir = File(appContext.filesDir, "captured_bodies").apply { mkdirs() }
    private val listGson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<TrafficRecord>> = _records.asStateFlow()

    suspend fun append(record: TrafficRecordDraft): TrafficRecord = withContext(Dispatchers.IO) {
        val id = databaseHelper.writableDatabase.insert(
            "traffic_records",
            null,
            toContentValues(record)
        )
        refresh()
        _records.value.first { it.id == id }
    }

    suspend fun importRecords(records: List<TrafficRecordDraft>) = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext

        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            records.forEach { record ->
                database.insert("traffic_records", null, toContentValues(record))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        databaseHelper.writableDatabase.delete("traffic_records", null, null)
        bodyDir.listFiles().orEmpty().forEach(File::delete)
        refresh()
    }

    fun loadBodyText(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val file = File(path)
        return if (file.exists()) file.readText() else ""
    }

    fun snapshot(): List<TrafficRecord> = _records.value

    fun exportDirectory(): File {
        val externalRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(externalRoot ?: appContext.filesDir, "exports").apply { mkdirs() }
    }

    private fun refresh() {
        _records.value = loadRecords()
    }

    private fun loadRecords(): List<TrafficRecord> {
        val database = databaseHelper.readableDatabase
        val result = mutableListOf<TrafficRecord>()
        database.query(
            "traffic_records",
            null,
            null,
            null,
            null,
            null,
            "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.toTrafficRecord())
            }
        }
        return result
    }

    private fun Cursor.toTrafficRecord(): TrafficRecord = TrafficRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        requestUrl = getString(getColumnIndexOrThrow("request_url")),
        requestMethod = getString(getColumnIndexOrThrow("request_method")),
        requestHeaders = getString(getColumnIndexOrThrow("request_headers")),
        requestBodyPreview = getString(getColumnIndexOrThrow("request_body_preview")),
        requestBodyPath = getStringOrNull("request_body_path"),
        responseStatus = getIntOrNull("response_status"),
        responseHeaders = getString(getColumnIndexOrThrow("response_headers")),
        responseBodyPreview = getString(getColumnIndexOrThrow("response_body_preview")),
        responseBodyPath = getStringOrNull("response_body_path"),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        outcome = runCatching { TrafficOutcome.valueOf(getString(getColumnIndexOrThrow("outcome"))) }
            .getOrDefault(TrafficOutcome.FAILED),
        errorMessage = getStringOrNull("error_message"),
        matchedRules = runCatching {
            listGson.fromJson<List<String>>(getString(getColumnIndexOrThrow("matched_rules")), stringListType)
        }.getOrDefault(emptyList()),
        source = getString(getColumnIndexOrThrow("source")),
        contentType = getString(getColumnIndexOrThrow("content_type"))
    )

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getInt(index)
    }

    private fun persistBody(timestamp: Long, label: String, bodyText: String): String? {
        if (bodyText.isBlank()) return null
        val file = File(bodyDir, "${timestamp}_${label}.txt")
        file.writeText(bodyText)
        return file.absolutePath
    }

    private fun toContentValues(record: TrafficRecordDraft): ContentValues {
        val requestBodyPath = persistBody(record.timestamp, "request", record.requestBody)
        val responseBodyPath = persistBody(record.timestamp, "response", record.responseBody)

        return ContentValues().apply {
            put("timestamp", record.timestamp)
            put("request_url", record.requestUrl)
            put("request_method", record.requestMethod)
            put("request_headers", record.requestHeaders)
            put("request_body_preview", preview(record.requestBody))
            put("request_body_path", requestBodyPath)
            put("response_status", record.responseStatus)
            put("response_headers", record.responseHeaders)
            put("response_body_preview", preview(record.responseBody))
            put("response_body_path", responseBodyPath)
            put("duration_ms", record.durationMs)
            put("outcome", record.outcome.name)
            put("error_message", record.errorMessage)
            put("matched_rules", listGson.toJson(record.matchedRules))
            put("source", record.source)
            put("content_type", record.contentType)
        }
    }

    private fun preview(body: String): String {
        if (body.isBlank()) return ""
        val compact = body.replace('\r', ' ').replace('\n', ' ').trim()
        return if (compact.length > 240) compact.take(240) + "..." else compact
    }
}
