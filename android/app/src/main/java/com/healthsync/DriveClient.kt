package com.healthsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader

object DriveClient {

    private const val FILE_NAME = "health_data.json"
    private const val MAX_HISTORY_DAYS = 30L
    private const val PREFS = "health_sync"
    private const val KEY_FILE_URI = "drive_file_uri"

    fun hasFile(context: Context): Boolean {
        return fileUri(context) != null
    }

    fun saveFileUri(context: Context, uri: Uri, flags: Int) {
        val persistFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.contentResolver.takePersistableUriPermission(uri, persistFlags)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILE_URI, uri.toString())
            .apply()
    }

    fun syncSnapshot(context: Context, snapshot: HealthSnapshot) {
        val summaryEntry = snapshotToJson(snapshot, includeRawRecords = false)
        val fullEntry = snapshotToJson(snapshot, includeRawRecords = true)
        val uri = fileUri(context)
            ?: throw Exception("Google Drive file not connected. Tap 'Connect Google Drive' first.")

        val existing = readFile(context, uri)
        val updated = if (existing != null) {
            mergeEntry(existing, summaryEntry, snapshot)
        } else {
            JSONObject().apply {
                put("profile", JSONObject().apply {
                    put("device_id", snapshot.deviceId)
                    put("last_updated", snapshot.recordedAt)
                })
                put("snapshots", org.json.JSONArray().put(summaryEntry))
            }
        }
        updated.put("latest_full_export", fullEntry)

        writeFile(context, uri, updated)
    }

    private fun fileUri(context: Context): Uri? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FILE_URI, null)
        return uri?.let(Uri::parse)
    }

    private fun mergeEntry(existing: JSONObject, newEntry: JSONObject, snapshot: HealthSnapshot): JSONObject {
        val snapshots = existing.optJSONArray("snapshots") ?: org.json.JSONArray()
        val today = newEntry.getString("date")
        val cutoff = LocalDate.now().minusDays(MAX_HISTORY_DAYS).toString()

        val kept = org.json.JSONArray()
        for (i in 0 until snapshots.length()) {
            val entry = snapshots.getJSONObject(i)
            val date = entry.optString("date")
            // Drop today's old entry (will be replaced) and entries older than cutoff
            if (date != today && date >= cutoff) kept.put(entry)
        }
        kept.put(newEntry)

        existing.put("snapshots", kept)
        existing.optJSONObject("profile")?.put("last_updated", snapshot.recordedAt)
        return existing
    }

private fun readFile(context: Context, uri: Uri): JSONObject? {
    val input = context.contentResolver.openInputStream(uri) ?: return null

    return input.use { stream ->
        JsonReader(InputStreamReader(stream)).use { reader ->

            // A newly created Drive file may be completely empty.
            // Treat that as "no existing sync data".
            if (reader.peek() == JsonToken.END_DOCUMENT) {
                return@use null
            }

            val root = JSONObject()
            var foundUsefulData = false

            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "profile" -> {
                        root.put("profile", readJsonObject(reader))
                        foundUsefulData = true
                    }

                    "snapshots" -> {
                        root.put("snapshots", readJsonArray(reader))
                        foundUsefulData = true
                    }

                    "latest_full_export" -> {
                        // We are replacing this with the new full export,
                        // so skip the old large object without loading it.
                        reader.skipValue()
                    }

                    else -> {
                        reader.skipValue()
                    }
                }
            }

            reader.endObject()

            if (foundUsefulData) root else null
        }
    }
}

 private fun readJsonObject(reader: JsonReader): JSONObject {
    val obj = JSONObject()

    reader.beginObject()

    while (reader.hasNext()) {
        val name = reader.nextName()
        obj.put(name, readJsonValue(reader))
    }

    reader.endObject()
    return obj
}

private fun readJsonArray(reader: JsonReader): org.json.JSONArray {
    val array = org.json.JSONArray()

    reader.beginArray()

    while (reader.hasNext()) {
        array.put(readJsonValue(reader))
    }

    reader.endArray()
    return array
}

private fun readJsonValue(reader: JsonReader): Any {
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readJsonObject(reader)

        JsonToken.BEGIN_ARRAY -> readJsonArray(reader)

        JsonToken.STRING -> reader.nextString()

        JsonToken.NUMBER -> {
            val value = reader.nextString()

            value.toLongOrNull()
                ?: value.toDoubleOrNull()
                ?: value
        }

        JsonToken.BOOLEAN -> reader.nextBoolean()

        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }

        else -> {
            reader.skipValue()
            JSONObject.NULL
        }
    }
}

    private fun writeFile(context: Context, uri: Uri, content: JSONObject) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.writer().use { writer ->
                writer.write(content.toString(2))
            }
        } ?: throw Exception("Could not open $FILE_NAME for writing.")
    }

    private fun snapshotToJson(snapshot: HealthSnapshot, includeRawRecords: Boolean): JSONObject {
        return JSONObject().apply {
            put("date", LocalDate.now().toString())
            put("recorded_at", snapshot.recordedAt)
            put("export_window", JSONObject().apply {
                put("start", snapshot.exportStart)
                put("end", snapshot.exportEnd)
            })
            put("granted_permissions", toJsonValue(snapshot.grantedPermissions))
            put("requested_record_types", toJsonValue(snapshot.requestedRecordTypes))
            snapshot.selectedSummaryOrigin?.let { put("selected_summary_origin", it) }
            put("summary_data_origins", toJsonValue(snapshot.summaryDataOrigins))
            put("summary_method", "Health Connect aggregate API filtered to the preferred wearable/app source when available; raw records remain typed by source record.")
            put("analysis_guidance", JSONObject().apply {
                put("daily_totals_authoritative_source", "Use the summary fields in this object for daily totals.")
                put("raw_records_warning", "Do not sum raw_records to answer daily totals unless explicitly doing raw-record auditing; raw records can overlap, use UTC timestamps, and may not match Health/Fitbit local-day cards.")
                put("timezone_rule", "For user-facing sleep and day-level answers, prefer local fields and local-day summaries over UTC timestamps ending in Z.")
                put("source_rule", "Daily totals prefer selected_summary_origin, usually the wearable app source, so steps match the visible Fitbit/Google Health card instead of mixing phone and wearable origins.")
            })
            put("all_sources_audit", JSONObject().apply {
                snapshot.allSourcesSteps?.let { put("steps", it) }
                snapshot.allSourcesDistanceMeters?.let {
                    put("distance_health_connect_km", Math.round(it / 1000.0 * 100) / 100.0)
                }
                snapshot.allSourcesCaloriesTotal?.let { put("calories_total_kcal", it) }
                put("note", "Audit-only total across all Health Connect origins. Do not use this for card comparison when selected_summary_origin is present.")
            })
            snapshot.steps?.let { put("steps", it) }
            snapshot.caloriesActive?.let { put("calories_active_kcal", it) }
            snapshot.caloriesTotal?.let { put("calories_total_kcal", it) }
            snapshot.heartRateAvg?.let { put("heart_rate_sample_avg_bpm", it) }
            snapshot.heartRateResting?.let { put("heart_rate_resting_bpm", it) }
            snapshot.distanceMeters?.let {
                put("distance_health_connect_km", Math.round(it / 1000.0 * 100) / 100.0)
            }
            snapshot.activeMinutes?.let { put("exercise_session_minutes", it) }
            if (snapshot.sleepDurationMinutes != null) {
                put("sleep", JSONObject().apply {
                    put("duration_hours", Math.round(snapshot.sleepDurationMinutes / 60.0 * 10) / 10.0)
                    snapshot.sleepDate?.let { put("sleep_date_local", it) }
                    snapshot.sleepStart?.let { put("start_local", it) }
                    snapshot.sleepEnd?.let { put("end_local", it) }
                    snapshot.sleepStartUtc?.let { put("start_utc", it) }
                    snapshot.sleepEndUtc?.let { put("end_utc", it) }
                    snapshot.sleepStages?.let { put("stages_minutes", JSONObject(it as Map<*, *>)) }
                })
            }
            if (snapshot.hrvRmssdSampleCount > 0) {
                put("hrv_rmssd", JSONObject().apply {
                    put("sample_count", snapshot.hrvRmssdSampleCount)
                    snapshot.hrvRmssdMedianMs?.let { put("median_ms", it) }
                    snapshot.hrvRmssdAvgMs?.let { put("average_ms", it) }
                    snapshot.hrvRmssdMinMs?.let { put("min_ms", it) }
                    snapshot.hrvRmssdMaxMs?.let { put("max_ms", it) }
                    put("display_guidance", "Use median_ms for Fitbit/Health-style HRV card comparison; average_ms is a separate arithmetic average.")
                })
            }
            if (includeRawRecords) {
                put("raw_records", toJsonValue(snapshot.rawRecords))
                put("raw_record_counts", JSONObject().apply {
                    snapshot.rawRecords.forEach { (type, records) ->
                        put(type, records.size)
                    }
                })
                put("extraction_errors", toJsonValue(snapshot.extractionErrors))
            }
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is org.json.JSONArray -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, entryValue) ->
                    put(key.toString(), toJsonValue(entryValue))
                }
            }
            is Iterable<*> -> org.json.JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            is Array<*> -> org.json.JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            is Boolean, is Number, is String -> value
            else -> value.toString()
        }
    }
}
