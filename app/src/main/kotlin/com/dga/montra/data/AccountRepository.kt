package com.dga.montra.data

import android.content.Context
import android.util.Base64
import com.dga.montra.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccountRepository(context: Context) {
    private val preferences = context.getSharedPreferences("lumen_accounts", Context.MODE_PRIVATE)
    private val txPreferences = context.getSharedPreferences("lumen_tx", Context.MODE_PRIVATE)
    private val keyAlias = "lumen_accounts_key"

    fun all(): List<Account> {
        val stored = preferences.getString("accounts_encrypted", null)
        val legacy = preferences.getString("accounts", null)
        val raw = when {
            stored != null -> decrypt(stored)
            legacy != null -> legacy
            else -> "[]"
        }
        val accounts = parse(raw)
        if (stored == null && legacy != null) saveEncrypted(raw)
        return accounts
    }

    fun add(account: Account) {
        val json = JSONArray()
        (all() + account).forEach { item ->
            json.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("currency", item.openingBalance.currencyCode)
                .put("minorUnits", item.openingBalance.minorUnits))
        }
        saveEncrypted(json.toString())
    }

    fun dashboard(): DashboardState {
        val accounts = all()
        val totals = mutableMapOf<String, Long>()
        accounts.forEach { account ->
            val code = account.openingBalance.currencyCode
            totals[code] = Math.addExact(totals[code] ?: 0L, account.openingBalance.minorUnits)
        }
        val accountById = accounts.associateBy { it.id }
        allTransactions().forEach { transaction ->
            val account = accountById[transaction.accountId]
                ?: throw IllegalStateException("Transaction references missing account")
            require(account.openingBalance.currencyCode == transaction.amount.currencyCode) {
                "Transaction currency mismatch"
            }
            val code = transaction.amount.currencyCode
            totals[code] = Math.addExact(totals[code] ?: 0L, transaction.signedMinorUnits())
        }
        return DashboardState(totals, accounts.size)
    }

    fun addTransaction(tx: Transaction) {
        val account = all().firstOrNull { it.id == tx.accountId }
            ?: throw IllegalArgumentException("Unknown account")
        require(account.openingBalance.currencyCode == tx.amount.currencyCode) { "Account currency mismatch" }
        val txs = allTransactions().toMutableList().apply { add(tx) }
        val json = JSONArray()
        txs.forEach { t ->
            json.put(JSONObject().put("id", t.id).put("accountId", t.accountId)
                .put("minorUnits", t.amount.minorUnits).put("currency", t.amount.currencyCode)
                .put("type", t.type.name).put("categoryId", t.categoryId)
                .put("note", t.note).put("time", t.timestampMillis))
        }
        saveTransactions(json.toString())
    }

    fun allTransactions(): List<Transaction> {
        val encrypted = txPreferences.getString("transactions_encrypted", null)
        val legacy = txPreferences.getString("transactions", null)
        val raw = when {
            encrypted != null -> decrypt(encrypted)
            legacy != null -> legacy
            else -> "[]"
        }
        val json = JSONArray(raw)
        val transactions = (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val currency = item.getString("currency")
            require(currency in Money.currencies) { "Unsupported transaction currency" }
            Transaction(item.getString("id"), item.getString("accountId"),
                Money(item.getLong("minorUnits"), currency),
                TransactionType.valueOf(item.getString("type")),
                item.getString("categoryId"), item.getString("note"), item.getLong("time"))
        }
        if (encrypted == null && legacy != null) saveTransactions(raw)
        return transactions
    }

    private fun saveTransactions(raw: String) {
        check(txPreferences.edit().putString("transactions_encrypted", encrypt(raw))
            .remove("transactions").commit()) { "Could not save transaction" }
    }

    private fun parse(raw: String): List<Account> {
        val json = JSONArray(raw)
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val currency = item.getString("currency")
            Account(item.getString("id"), item.getString("name"),
                Money(item.getLong("minorUnits"), currency))
        }
    }

    private fun saveEncrypted(raw: String) {
        preferences.edit().putString("accounts_encrypted", encrypt(raw))
            .remove("accounts").commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
}
