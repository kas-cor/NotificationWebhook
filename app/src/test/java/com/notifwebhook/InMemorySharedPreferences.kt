package com.notifwebhook

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Простая in-memory реализация [SharedPreferences] для unit-тестов.
 * Не использует Android framework-классы, поэтому работает без stubs.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val map = ConcurrentHashMap<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = ConcurrentHashMap(map)

    override fun getString(key: String, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    override fun getStringSet(key: String, defValue: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        (map[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (map[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (map[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private inner class InMemoryEditor : SharedPreferences.Editor {

        private val pending = mutableMapOf<String, Any?>()
        private val pendingRemovals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pendingRemovals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            if (clearAll) map.clear()
            pendingRemovals.forEach { map.remove(it) }
            map.putAll(pending)
            pending.clear()
            pendingRemovals.clear()
            clearAll = false
            return true
        }
    }
}
