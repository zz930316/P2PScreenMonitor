package com.p2p.monitor.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ConnectionHistoryManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY_SIZE = 10
    }

    fun getHistory(): List<ConnectionRecord> {
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ConnectionRecord(
                    ip = obj.getString("ip"),
                    lastConnected = obj.getLong("lastConnected"),
                    connectCount = obj.getInt("connectCount")
                )
            }.sortedByDescending { it.lastConnected }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecord(ip: String) {
        val records = getHistory().toMutableList()
        val existingIndex = records.indexOfFirst { it.ip == ip }

        if (existingIndex >= 0) {
            val existing = records[existingIndex]
            records[existingIndex] = existing.copy(
                lastConnected = System.currentTimeMillis(),
                connectCount = existing.connectCount + 1
            )
        } else {
            records.add(
                ConnectionRecord(
                    ip = ip,
                    lastConnected = System.currentTimeMillis(),
                    connectCount = 1
                )
            )
        }

        val sorted = records.sortedByDescending { it.lastConnected }
            .take(MAX_HISTORY_SIZE)

        val array = JSONArray()
        sorted.forEach { record ->
            val obj = JSONObject().apply {
                put("ip", record.ip)
                put("lastConnected", record.lastConnected)
                put("connectCount", record.connectCount)
            }
            array.put(obj)
        }

        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun removeRecord(ip: String) {
        val records = getHistory().filter { it.ip != ip }
        val array = JSONArray()
        records.forEach { record ->
            val obj = JSONObject().apply {
                put("ip", record.ip)
                put("lastConnected", record.lastConnected)
                put("connectCount", record.connectCount)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}

data class ConnectionRecord(
    val ip: String,
    val lastConnected: Long,
    val connectCount: Int
)
