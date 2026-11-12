package com.dga.montra

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionCategories
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.copyTo
import com.dga.montra.data.Storage

class ProfileActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(Storage.PREFS_PROFILE, MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences(Storage.PREFS_SETTINGS, MODE_PRIVATE) }
    private val scope = MainScope()
    private lateinit var avatar: TextView
    private lateinit var avatarImage: ImageView
    private lateinit var photoChange: TextView
    private lateinit var photoRemove: TextView
    private lateinit var nameView: TextView
    private lateinit var themeSpinner: Spinner
    private lateinit var currencySpinner: Spinner

    override fun onCreate(state: Bundle?) {
        applyTheme()
        super.onCreate(state)
        setContentView(R.layout.activity_profile)
        avatar = findViewById(R.id.profile_avatar)
        avatarImage = findViewById(R.id.profile_avatar_image)
        photoChange = findViewById(R.id.profile_photo_change)
        photoRemove = findViewById(R.id.profile_photo_remove)
        nameView = findViewById(R.id.profile_name)
        findViewById<View>(R.id.profile_avatar_container).setOnClickListener { pickPhoto() }
        photoChange.setOnClickListener { pickPhoto() }
        photoRemove.setOnClickListener { confirmRemovePhoto() }
        themeSpinner = findViewById(R.id.profile_theme)
        currencySpinner = findViewById(R.id.profile_currency)
        findViewById<TextView>(R.id.profile_back).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.profile_edit).setOnClickListener { editName() }
        findViewById<TextView>(R.id.profile_export).setOnClickListener { exportTransactions() }
        findViewById<TextView>(R.id.profile_backup).setOnClickListener { backupDatabase() }
        findViewById<TextView>(R.id.profile_restore).setOnClickListener { restoreDatabase() }
        bindThemeSpinner()
        bindCurrencySpinner()
        render()
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return@onActivityResult
        val uri = data.data!!
        scope.launch {
            try {
                when (requestCode) {
                    REQUEST_BACKUP -> backupToUri(uri)
                    REQUEST_RESTORE -> restoreFromUri(uri)
                    REQUEST_AVATAR -> importPhoto(uri)
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.profile_error) }
            }
        }
    }

    private fun applyTheme() {
        val mode = settings.getInt("theme_mode", 0)
        val cur = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val target = when (mode) {
            1 -> Configuration.UI_MODE_NIGHT_NO
            2 -> Configuration.UI_MODE_NIGHT_YES
            else -> cur
        }
        if (target != cur) {
            val config = Configuration(resources.configuration)
            config.uiMode = target
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }

    private fun bindThemeSpinner() {
        val entries = arrayOf(R.string.profile_theme_system, R.string.profile_theme_light, R.string.profile_theme_dark)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries.map { getString(it) }.toTypedArray())
        themeSpinner.adapter = adapter
        val saved = settings.getInt("theme_mode", 0).coerceIn(0, 2)
        themeSpinner.setSelection(saved)
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val current = settings.getInt("theme_mode", 0)
                if (position != current) {
                    settings.edit().putInt("theme_mode", position).apply()
                    applyTheme()
                    recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun bindCurrencySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Money.currencies.toTypedArray())
        currencySpinner.adapter = adapter
        val savedCode = settings.getString("default_currency", "PHP")
        val savedIndex = Money.currencies.indexOf(savedCode).coerceAtLeast(0)
        currencySpinner.setSelection(savedIndex)
        currencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val code = Money.currencies[position]
                settings.edit().putString("default_currency", code).putString("last_currency", code).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun render() {
        val name = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        nameView.text = if (name.isEmpty()) getString(R.string.profile_default_name) else name
        avatar.text = initials(name)
        ProfileAvatar.bind(
            this,
            avatarImage,
            avatar,
            resources.getDimensionPixelSize(R.dimen.space_96)
        )
        val hasPhoto = ProfileAvatar.exists(this)
        photoChange.setText(if (hasPhoto) R.string.profile_photo_change else R.string.profile_photo_add)
        photoRemove.visibility = if (hasPhoto) View.VISIBLE else View.GONE
    }

    private fun pickPhoto() {
        startActivityForResult(ProfileAvatar.pickIntent(), REQUEST_AVATAR)
    }

    /**
     * Importing decodes and re-encodes the image, so it runs off the main thread; the photo is copied
     * into app storage rather than depending on a gallery file that may later move.
     */
    private suspend fun importPhoto(uri: Uri) {
        val imported = withContext(Dispatchers.IO) {
            runCatching { ProfileAvatar.import(this@ProfileActivity, uri) }.getOrDefault(false)
        }
        if (imported) {
            render()
            toast(R.string.profile_photo_updated)
        } else {
            toast(R.string.profile_photo_error)
        }
    }

    private fun confirmRemovePhoto() {
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_photo_remove_title)
            .setMessage(R.string.profile_photo_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ProfileAvatar.remove(this)
                render()
                toast(R.string.profile_photo_removed)
            }
            .show()
    }

    private fun editName() {
        val input = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val field = input.findViewById<EditText>(R.id.profile_name_input)
        field.setText(prefs.getString(KEY_NAME, ""))
        field.setSelection(field.length())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.profile_edit_title).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                when {
                    value.isEmpty() -> field.error = getString(R.string.profile_name_required)
                    value.length > 50 -> field.error = getString(R.string.profile_name_too_long)
                    value.any { it.isISOControl() } -> field.error = getString(R.string.profile_name_invalid)
                    !prefs.edit().putString(KEY_NAME, value).commit() -> field.error = getString(R.string.profile_save_error)
                    else -> { dialog.dismiss(); render() }
                }
            }
        }
        dialog.show()
    }

    private fun exportTransactions() {
        scope.launch {
            try {
                val repo = RoomFinanceRepository.get(applicationContext)
                val accounts = repo.allAccounts().associateBy { it.id }.mapValues { it.value.name }
                val txs = repo.allTransactions()
                val sb = StringBuilder()
                sb.append("date,type,category,account,amount,currency,note\n")
                val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                for (tx in txs) {
                    sb.append(escapeCsv(df.format(Date(tx.timestampMillis)))).append(',')
                    sb.append(tx.type.name).append(',')
                    sb.append(escapeCsv(categoryName(tx))).append(',')
                    sb.append(escapeCsv(accounts[tx.accountId] ?: "")).append(',')
                    sb.append(tx.amount.minorUnits).append(',')
                    sb.append(tx.amount.currencyCode).append(',')
                    sb.appendLine(escapeCsv(tx.note))
                }
                val file = File(cacheDir, "${Storage.EXPORT_PREFIX}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())}.csv")
                file.writeText(sb.toString())
                val uri = FileProvider.getUriForFile(this@ProfileActivity, FILE_PROVIDER_AUTHORITY, file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.profile_export_csv_share))
                }
                runOnUiThread {
                    startActivity(Intent.createChooser(intent, getString(R.string.profile_export_transactions)))
                    toast(R.string.profile_export_success)
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.profile_error) }
            }
        }
    }

    private fun categoryName(tx: Transaction): String {
        return TransactionCategories.expense.firstOrNull { it.id == tx.categoryId }?.name
            ?: TransactionCategories.income.firstOrNull { it.id == tx.categoryId }?.name
            ?: tx.categoryId
    }

    private fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    private fun backupDatabase() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, DATABASE_NAME)
        }
        startActivityForResult(intent, REQUEST_BACKUP)
    }

    private fun restoreDatabase() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_RESTORE)
    }

    private fun backupToUri(uri: Uri) {
        val dbFile = getDatabasePath(DATABASE_NAME)
        FileInputStream(dbFile).use { input ->
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Could not open backup destination")
        }
        runOnUiThread { toast(R.string.profile_backup_success) }
    }

    private fun restoreFromUri(uri: Uri) {
        val temp = File(cacheDir, Storage.TEMP_RESTORE)
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        temp.copyTo(getDatabasePath(DATABASE_NAME), overwrite = true)
        runOnUiThread { toast(R.string.profile_restore_success) }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private fun initials(name: String): String =
        ProfileAvatar.initialsFor(name, getString(R.string.app_name).take(1).uppercase())

    companion object {
        private const val REQUEST_BACKUP = 1001
        private const val REQUEST_RESTORE = 1002
        private const val REQUEST_AVATAR = 1003
        private const val FILE_PROVIDER_AUTHORITY = "com.dga.montra.fileprovider"
        private const val DATABASE_NAME = Storage.DATABASE
        private const val KEY_NAME = "display_name"
    }
}
