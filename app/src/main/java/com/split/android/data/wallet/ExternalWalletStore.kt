package com.split.android.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.split.android.data.security.openEncryptedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ExternalWalletKind(val rawValue: String) {
    LND("lnd"),
    NWC("nwc"),
    CORE_LIGHTNING("coreLightning"),
    ECLAIR("eclair"),
    SPARK_SUBWALLET("sparkSubwallet");

    val displayName: String
        get() = when (this) {
            LND -> "LND Node"
            NWC -> "NWC Wallet"
            CORE_LIGHTNING -> "Core Lightning Node"
            ECLAIR -> "Eclair Node"
            SPARK_SUBWALLET -> "Spark Wallet"
        }

    companion object {
        fun fromRawValue(value: String?): ExternalWalletKind? {
            return entries.firstOrNull { it.rawValue.equals(value?.trim(), ignoreCase = true) }
        }
    }
}

sealed interface SpendWalletSelection {
    data object Spark : SpendWalletSelection
    data class External(val kind: ExternalWalletKind, val id: String) : SpendWalletSelection

    val storageValue: String
        get() = when (this) {
            Spark -> "spark"
            is External -> "external:${kind.rawValue}:$id"
        }

    companion object {
        fun fromStorageValue(value: String?): SpendWalletSelection {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "spark") {
                return Spark
            }

            val parts = trimmed.split(":", limit = 3)
            val kind = parts.getOrNull(1)?.let(ExternalWalletKind::fromRawValue)
            val id = parts.getOrNull(2)?.trim().orEmpty()

            return if (parts.size == 3 && parts[0] == "external" && kind != null && id.isNotEmpty()) {
                External(kind = kind, id = id)
            } else {
                Spark
            }
        }
    }
}

data class ExternalWalletRecord(
    val id: String,
    val kind: ExternalWalletKind,
    val label: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
    val payload: JSONObject
) {
    val storageKey: String
        get() = storageKey(kind, id)

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("kind", kind.rawValue)
            .put("label", label)
            .put("createdAtMillis", createdAtMillis)
            .put("updatedAtMillis", updatedAtMillis)
            .put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
            .put("payload", payload)
    }

    companion object {
        fun storageKey(kind: ExternalWalletKind, id: String): String {
            return "external:${kind.rawValue}:$id"
        }

        fun fromJson(json: JSONObject): ExternalWalletRecord {
            val kind = ExternalWalletKind.fromRawValue(json.getString("kind"))
                ?: throw IllegalArgumentException("Unknown external wallet kind.")
            return ExternalWalletRecord(
                id = json.getString("id"),
                kind = kind,
                label = json.optString("label").trim().ifBlank { kind.displayName },
                createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                lastVerifiedAtMillis = json.externalWalletOptNullableLong("lastVerifiedAtMillis"),
                payload = json.getJSONObject("payload")
            )
        }
    }
}

class ExternalWalletStore(
    context: Context
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null
    @Volatile
    private var activePreferences: SharedPreferences? = null

    init {
        migrateActiveSelectionToDedicatedStoreIfNeeded()
    }

    fun loadWallets(): List<ExternalWalletRecord> {
        return walletKeys()
            .mapNotNull { key -> readRecord(key) }
            .sortedWith(compareBy<ExternalWalletRecord> { it.createdAtMillis }.thenBy { it.id })
    }

    fun loadWallet(kind: ExternalWalletKind, id: String): ExternalWalletRecord? {
        return readRecord(ExternalWalletRecord.storageKey(kind, id))
    }

    fun wallets(kind: ExternalWalletKind): List<ExternalWalletRecord> {
        return loadWallets().filter { it.kind == kind }
    }

    fun saveLndNode(node: LndNodeCredentials, makeActive: Boolean = true): LndNodeCredentials {
        migrateLegacyLndNodesIfNeeded()
        return saveLndNodeRecord(node, makeActive)
    }

    fun saveNwcWallet(wallet: NwcWalletCredentials, makeActive: Boolean = true): NwcWalletCredentials {
        val existing = loadWallet(ExternalWalletKind.NWC, wallet.id)
        val label = existing?.label ?: wallet.label?.trim()?.ifBlank { null } ?: nextDefaultWalletLabel()
        val now = System.currentTimeMillis()
        val labeledWallet = wallet.copy(label = label)
        val record = ExternalWalletRecord(
            id = labeledWallet.id,
            kind = ExternalWalletKind.NWC,
            label = label,
            createdAtMillis = existing?.createdAtMillis ?: labeledWallet.connectedAtMillis,
            updatedAtMillis = now,
            lastVerifiedAtMillis = labeledWallet.lastVerifiedAtMillis,
            payload = labeledWallet.toJson()
        )

        saveRecord(record)

        if (makeActive) {
            setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.NWC, labeledWallet.id))
        }

        return labeledWallet
    }

    fun saveCoreLightningNode(
        node: CoreLightningNodeCredentials,
        makeActive: Boolean = true
    ): CoreLightningNodeCredentials {
        val existing = loadWallet(ExternalWalletKind.CORE_LIGHTNING, node.id)
        val label = existing?.label ?: node.label?.trim()?.ifBlank { null } ?: nextDefaultWalletLabel()
        val now = System.currentTimeMillis()
        val labeledNode = node.withLabel(label)
        val record = ExternalWalletRecord(
            id = labeledNode.id,
            kind = ExternalWalletKind.CORE_LIGHTNING,
            label = label,
            createdAtMillis = existing?.createdAtMillis ?: labeledNode.connectedAtMillis,
            updatedAtMillis = now,
            lastVerifiedAtMillis = labeledNode.lastVerifiedAtMillis,
            payload = labeledNode.toJson()
        )

        saveRecord(record)

        if (makeActive) {
            setActiveSelection(
                SpendWalletSelection.External(ExternalWalletKind.CORE_LIGHTNING, labeledNode.id)
            )
        }

        return labeledNode
    }

    fun saveEclairNode(
        node: EclairNodeCredentials,
        makeActive: Boolean = true
    ): EclairNodeCredentials {
        val existing = loadWallet(ExternalWalletKind.ECLAIR, node.id)
        val label = existing?.label ?: node.label?.trim()?.ifBlank { null } ?: nextDefaultWalletLabel()
        val now = System.currentTimeMillis()
        val labeledNode = node.withLabel(label)
        val record = ExternalWalletRecord(
            id = labeledNode.id,
            kind = ExternalWalletKind.ECLAIR,
            label = label,
            createdAtMillis = existing?.createdAtMillis ?: labeledNode.connectedAtMillis,
            updatedAtMillis = now,
            lastVerifiedAtMillis = labeledNode.lastVerifiedAtMillis,
            payload = labeledNode.toJson()
        )

        saveRecord(record)

        if (makeActive) {
            setActiveSelection(
                SpendWalletSelection.External(ExternalWalletKind.ECLAIR, labeledNode.id)
            )
        }

        return labeledNode
    }

    fun saveSparkSubwallet(
        wallet: SparkSubwalletCredentials,
        makeActive: Boolean = true
    ): SparkSubwalletCredentials {
        val existing = loadWallet(ExternalWalletKind.SPARK_SUBWALLET, wallet.id)
        val label = existing?.label ?: wallet.label?.trim()?.ifBlank { null } ?: nextDefaultWalletLabel()
        val now = System.currentTimeMillis()
        val labeledWallet = wallet.withLabel(label)
        val record = ExternalWalletRecord(
            id = labeledWallet.id,
            kind = ExternalWalletKind.SPARK_SUBWALLET,
            label = label,
            createdAtMillis = existing?.createdAtMillis ?: labeledWallet.connectedAtMillis,
            updatedAtMillis = now,
            lastVerifiedAtMillis = labeledWallet.lastVerifiedAtMillis,
            payload = labeledWallet.toJson()
        )

        saveRecord(record)

        if (makeActive) {
            setActiveSelection(
                SpendWalletSelection.External(ExternalWalletKind.SPARK_SUBWALLET, labeledWallet.id)
            )
        }

        return labeledWallet
    }

    private fun saveLndNodeRecord(node: LndNodeCredentials, makeActive: Boolean): LndNodeCredentials {
        val existing = loadWallet(ExternalWalletKind.LND, node.id)
        val label = existing?.label ?: node.label?.trim()?.ifBlank { null } ?: nextDefaultWalletLabel()
        val now = System.currentTimeMillis()
        val labeledNode = node.copy(label = label)
        val record = ExternalWalletRecord(
            id = labeledNode.id,
            kind = ExternalWalletKind.LND,
            label = label,
            createdAtMillis = existing?.createdAtMillis ?: labeledNode.connectedAtMillis,
            updatedAtMillis = now,
            lastVerifiedAtMillis = labeledNode.lastVerifiedAtMillis,
            payload = labeledNode.toJson()
        )

        saveRecord(record)

        if (makeActive) {
            setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.LND, labeledNode.id))
        }

        return labeledNode
    }

    fun renameWallet(kind: ExternalWalletKind, id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return

        val record = loadWallet(kind, id) ?: return
        val updatedPayload = when (kind) {
            ExternalWalletKind.LND -> LndNodeCredentials.fromJson(record.payload)
                .copy(label = trimmed)
                .toJson()
            ExternalWalletKind.NWC -> NwcWalletCredentials.fromJson(record.payload)
                .copy(label = trimmed)
                .toJson()
            ExternalWalletKind.CORE_LIGHTNING -> CoreLightningNodeCredentials.fromJson(record.payload)
                .withLabel(trimmed)
                .toJson()
            ExternalWalletKind.ECLAIR -> EclairNodeCredentials.fromJson(record.payload)
                .withLabel(trimmed)
                .toJson()
            ExternalWalletKind.SPARK_SUBWALLET -> SparkSubwalletCredentials.fromJson(record.payload)
                .withLabel(trimmed)
                .toJson()
        }
        val updated = record.copy(
            label = trimmed,
            updatedAtMillis = System.currentTimeMillis(),
            payload = updatedPayload
        )
        saveRecord(updated)
    }

    fun deleteWallet(kind: ExternalWalletKind, id: String) {
        val key = ExternalWalletRecord.storageKey(kind, id)
        preferences().edit()
            .remove(key)
            .putStringSet(INDEX_KEY, walletKeys().filterNot { it == key }.toSet())
            .apply()

        if (activeSelection == SpendWalletSelection.External(kind, id)) {
            setActiveSelection(SpendWalletSelection.Spark)
        }
    }

    val activeSelection: SpendWalletSelection
        get() = SpendWalletSelection.fromStorageValue(activePreferences().getString(ACTIVE_SELECTION_KEY, null))

    fun setActiveSelection(selection: SpendWalletSelection) {
        activePreferences().edit()
            .putString(ACTIVE_SELECTION_KEY, selection.storageValue)
            .apply()
    }

    fun reconcileActiveSelection(): SpendWalletSelection {
        return when (val selection = activeSelection) {
            SpendWalletSelection.Spark -> selection
            is SpendWalletSelection.External -> {
                if (loadWallet(selection.kind, selection.id) != null) {
                    selection
                } else {
                    setActiveSelection(SpendWalletSelection.Spark)
                    SpendWalletSelection.Spark
                }
            }
        }
    }

    fun migrateLegacyLndNodesIfNeeded() {
        if (preferences().getBoolean(LEGACY_LND_MIGRATION_KEY, false)) return

        val legacyPrefs = runCatching {
            openEncryptedPreferences(
                context = appContext,
                fileName = LEGACY_LND_FILE_NAME,
                logTag = "ExternalWalletStore"
            )
        }.getOrNull()

        val legacyRaw = legacyPrefs?.getString(LEGACY_LND_NODES_KEY, null)
        val legacyActiveId = legacyPrefs?.getString(LEGACY_LND_ACTIVE_NODE_ID_KEY, null)
            ?.trim()
            ?.ifBlank { null }

        if (!legacyRaw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(legacyRaw)
                for (index in 0 until array.length()) {
                    val node = LndNodeCredentials.fromJson(array.getJSONObject(index))
                    saveLndNodeRecord(
                        node = node,
                        makeActive = legacyActiveId != null && node.id == legacyActiveId
                    )
                }
            }
        }

        if (legacyActiveId != null && activeSelection == SpendWalletSelection.Spark) {
            loadWallet(ExternalWalletKind.LND, legacyActiveId)?.let {
                setActiveSelection(SpendWalletSelection.External(ExternalWalletKind.LND, legacyActiveId))
            }
        }

        migrateActiveSelectionToDedicatedStoreIfNeeded()

        preferences().edit()
            .putBoolean(LEGACY_LND_MIGRATION_KEY, true)
            .apply()
    }

    private fun readRecord(key: String): ExternalWalletRecord? {
        val raw = preferences().getString(key, null) ?: return null
        return runCatching { ExternalWalletRecord.fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun saveRecord(record: ExternalWalletRecord) {
        val keys = walletKeys().plus(record.storageKey)
        preferences().edit()
            .putString(record.storageKey, record.toJson().toString())
            .putStringSet(INDEX_KEY, keys)
            .apply()
    }

    private fun walletKeys(): Set<String> {
        return preferences().getStringSet(INDEX_KEY, emptySet()).orEmpty()
    }

    private fun nextDefaultWalletLabel(): String {
        val next = preferences().getInt(DEFAULT_WALLET_COUNTER_KEY, 0) + 1
        preferences().edit()
            .putInt(DEFAULT_WALLET_COUNTER_KEY, next)
            .apply()
        return "Wallet #$next"
    }

    private fun preferences(): SharedPreferences {
        preferences?.let { return it }

        return synchronized(this) {
            preferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = FILE_NAME,
                logTag = "ExternalWalletStore"
            ).also { preferences = it }
        }
    }

    private fun activePreferences(): SharedPreferences {
        activePreferences?.let { return it }

        return synchronized(this) {
            activePreferences ?: openEncryptedPreferences(
                context = appContext,
                fileName = ACTIVE_FILE_NAME,
                logTag = "ExternalWalletStoreActive"
            ).also { activePreferences = it }
        }
    }

    private fun migrateActiveSelectionToDedicatedStoreIfNeeded() {
        if (activePreferences().getBoolean(ACTIVE_SELECTION_MIGRATION_KEY, false)) return

        val legacySelection = preferences().getString(ACTIVE_SELECTION_KEY, null)
            ?.let(SpendWalletSelection::fromStorageValue)
            ?: SpendWalletSelection.Spark

        if (activePreferences().getString(ACTIVE_SELECTION_KEY, null).isNullOrBlank() &&
            legacySelection != SpendWalletSelection.Spark
        ) {
            setActiveSelection(legacySelection)
        }

        activePreferences().edit()
            .putBoolean(ACTIVE_SELECTION_MIGRATION_KEY, true)
            .apply()
        preferences().edit()
            .remove(ACTIVE_SELECTION_KEY)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "split_external_wallets"
        const val ACTIVE_FILE_NAME = "split_active_spend_wallet"
        const val INDEX_KEY = "split.externalWallet.index.v1"
        const val ACTIVE_SELECTION_KEY = "split.externalWallet.activeSelection.v1"
        const val ACTIVE_SELECTION_MIGRATION_KEY = "split.externalWallet.activeSelectionDedicatedStoreMigration.v1"
        const val DEFAULT_WALLET_COUNTER_KEY = "split.externalWallet.defaultLabelCounter.v1"
        const val LEGACY_LND_MIGRATION_KEY = "split.externalWallet.legacyLndMigrationCompleted.v1"
        const val LEGACY_LND_FILE_NAME = "split_lnd_credentials"
        const val LEGACY_LND_NODES_KEY = "split.lnd.nodes.v1"
        const val LEGACY_LND_ACTIVE_NODE_ID_KEY = "split.lnd.activeNodeId.v1"
    }
}

internal fun JSONObject.externalWalletOptNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}
