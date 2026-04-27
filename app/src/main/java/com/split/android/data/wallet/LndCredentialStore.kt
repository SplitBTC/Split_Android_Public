package com.split.android.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.split.android.data.security.openEncryptedPreferences
import org.json.JSONArray

class LndCredentialStore(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    fun loadNodes(): List<LndNodeCredentials> {
        val raw = runCatching {
            preferences().getString(NODES_KEY, null)
        }.getOrNull() ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(LndNodeCredentials.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeNode(): LndNodeCredentials? {
        val nodes = loadNodes()
        val activeId = activeNodeId()
        return nodes.firstOrNull { it.id == activeId } ?: nodes.firstOrNull()
    }

    fun activeNodeId(): String? {
        return runCatching {
            preferences().getString(ACTIVE_NODE_ID_KEY, null)?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    fun saveNode(node: LndNodeCredentials, makeActive: Boolean = true) {
        val nodes = loadNodes()
            .filterNot { existing ->
                existing.id == node.id ||
                    (existing.nodePubkey == null &&
                        existing.host.equals(node.host, ignoreCase = true) &&
                        existing.port == node.port)
            }
            .plus(node)
            .sortedByDescending { it.connectedAtMillis }

        saveNodes(nodes)

        if (makeActive) {
            setActiveNode(node.id)
        }
    }

    fun setActiveNode(id: String) {
        preferences().edit()
            .putString(ACTIVE_NODE_ID_KEY, id)
            .apply()
    }

    fun deleteNode(id: String) {
        val nodes = loadNodes().filterNot { it.id == id }
        saveNodes(nodes)

        if (activeNodeId() == id) {
            if (nodes.isNotEmpty()) {
                setActiveNode(nodes.first().id)
            } else {
                preferences().edit().remove(ACTIVE_NODE_ID_KEY).apply()
            }
        }
    }

    fun clear() {
        preferences().edit()
            .remove(NODES_KEY)
            .remove(ACTIVE_NODE_ID_KEY)
            .apply()
    }

    private fun saveNodes(nodes: List<LndNodeCredentials>) {
        val array = JSONArray()
        nodes.forEach { node -> array.put(node.toJson()) }
        preferences().edit()
            .putString(NODES_KEY, array.toString())
            .apply()
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "LndCredentialStore"
            ).also { preferences = it }
        }
    }

    private companion object {
        const val FILE_NAME = "split_lnd_credentials"
        const val NODES_KEY = "split.lnd.nodes.v1"
        const val ACTIVE_NODE_ID_KEY = "split.lnd.activeNodeId.v1"
    }
}
