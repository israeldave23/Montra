package com.dga.montra

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.dga.montra.ads.Ads
import com.dga.montra.data.BackupBundle
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.data.Storage
import com.dga.montra.databinding.ActivityProfileBinding
import com.dga.montra.databinding.DialogEditProfileBinding
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.domain.TransactionCategories
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileActivity : AppActivity() {
    private lateinit var binding: ActivityProfileBinding

    private lateinit var avatar: TextView
    private lateinit var avatarImage: ImageView
    private lateinit var photoChange: TextView
    private lateinit var photoRemove: TextView
    private lateinit var nameView: TextView
    private lateinit var themeSpinner: Spinner
    private lateinit var currencySpinner: Spinner

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        avatar = binding.profileAvatar
        avatarImage = binding.profileAvatarImage
        photoChange = binding.profilePhotoChange
        photoRemove = binding.profilePhotoRemove
        nameView = binding.profileName
        binding.profileAvatarContainer.setOnClickListener { pickPhoto() }
        photoChange.setOnClickListener { pickPhoto() }
        photoRemove.setOnClickListener { confirmRemovePhoto() }
        themeSpinner = binding.profileTheme
        currencySpinner = binding.profileCurrency
        binding.profileBack.setOnClickListener { finish() }
        binding.profileEdit.setOnClickListener { editName() }
        binding.profileExport.setOnClickListener { exportTransactions() }
        binding.profileBackup.setOnClickListener { backupDatabase() }
        binding.profileRestore.setOnClickListener { restoreDatabase() }
        // Only offered where a consent form exists; a setting that does nothing is a lie.
        binding.profilePrivacy.apply {
            setOnClickListener { Ads.showPrivacyOptions(this@ProfileActivity) }
            visibility = if (Ads.privacyOptionsAvailable(this@ProfileActivity)) View.VISIBLE else View.GONE
        }
        bindThemeSpinner()
        bindCurrencySpinner()
        render()
        bindRewardCard()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return@onActivityResult
        val uri = data.data!!
        lifecycleScope.launch {
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

    /**
     * The rewarded card. It states the reward before the tap, hides itself while the reward is
     * running, and never pretends a video was watched when no ad was available.
     */
    private fun bindRewardCard() {
        val card = binding.profileRewardCard
        val action = binding.profileRewardButton
        val body = binding.profileRewardBody

        if (!Ads.rewardsEnabled(this)) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        val until = Ads.adFreeUntil(this)
        if (until > System.currentTimeMillis()) {
            action.visibility = View.GONE
            val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            body.text = getString(R.string.profile_reward_active, clock.format(Date(until)))
            return
        }

        action.visibility = View.VISIBLE
        action.setText(R.string.profile_reward_action)
        body.setText(R.string.profile_reward_body)
        action.setOnClickListener {
            action.isEnabled = false
            Ads.showRewarded(
                this,
                onReward = {
                    action.isEnabled = true
                    toast(R.string.profile_reward_earned, short = true)
                    bindRewardCard()
                },
                onUnavailable = {
                    action.isEnabled = true
                    toast(R.string.profile_reward_unavailable)
                }
            )
        }
    }

    private fun bindThemeSpinner() {
        val entries = arrayOf(R.string.profile_theme_system, R.string.profile_theme_light, R.string.profile_theme_dark)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries.map { getString(it) }.toTypedArray())
        themeSpinner.adapter = adapter
        val saved = settings.themeMode
        themeSpinner.setSelection(saved)
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val current = settings.themeMode
                if (position != current) {
                    settings.themeMode = position
                    // The delegate owns the mode for the process and recreates the visible screen
                    // itself; calling recreate() here would restart the screen twice.
                    AppActivity.applyThemeMode(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun bindCurrencySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Money.currencies.toTypedArray())
        currencySpinner.adapter = adapter
        val savedCode = settings.defaultCurrency
        val savedIndex = Money.currencies.indexOf(savedCode).coerceAtLeast(0)
        currencySpinner.setSelection(savedIndex)
        currencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val code = Money.currencies[position]
                settings.rememberCurrency(code)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun render() {
        val name = settings.displayName
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
        Ads.suppressNextAppOpen()
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
        MaterialAlertDialogBuilder(this)
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
        val input = DialogEditProfileBinding.inflate(layoutInflater)
        val field = input.profileNameInput
        field.setText(settings.displayName)
        field.setSelection(field.length())
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.profile_edit_title).setView(input.root)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                when {
                    value.isEmpty() -> field.error = getString(R.string.profile_name_required)
                    value.length > 50 -> field.error = getString(R.string.profile_name_too_long)
                    value.any { it.isISOControl() } -> field.error = getString(R.string.profile_name_invalid)
                    !settings.tryDisplayName(value) -> field.error = getString(R.string.profile_save_error)
                    else -> { dialog.dismiss(); render() }
                }
            }
        }
        dialog.show()
    }

    private fun exportTransactions() {
        lifecycleScope.launch {
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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${Storage.BACKUP_PREFIX}_$stamp.zip")
        }
        Ads.suppressNextAppOpen()
        startActivityForResult(intent, REQUEST_BACKUP)
    }

    private fun restoreDatabase() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        Ads.suppressNextAppOpen()
        startActivityForResult(intent, REQUEST_RESTORE)
    }

    /**
     * Writes one zip holding the database and a manifest.
     *
     * The checkpoint is what makes the copy complete: without it the newest transactions are still
     * in the `-wal` file and the backup is quietly stale.
     */
    private suspend fun backupToUri(uri: Uri) {
        val archive = withContext(Dispatchers.IO) {
            RoomFinanceRepository.get(this@ProfileActivity).checkpointForBackup()
            val file = File(cacheDir, "${Storage.BACKUP_PREFIX}.zip")
            BackupBundle.write(
                target = file,
                database = getDatabasePath(DATABASE_NAME),
                manifest = BackupBundle.Manifest(
                    formatVersion = BackupBundle.FORMAT_VERSION,
                    schemaVersion = FinanceDatabase.VERSION,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
            file
        }
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            } ?: throw IllegalStateException("Could not open backup destination")
        }
        archive.delete()
        runOnUiThread { toast(R.string.profile_backup_success) }
    }

    /**
     * Validates the picked file before offering to replace anything.
     *
     * Nothing is written over the database here: the archive is checked (manifest, format version,
     * schema version, SQLite header) and the user is asked to confirm. Only then does
     * [applyRestore] touch the live file.
     */
    private suspend fun restoreFromUri(uri: Uri) {
        val staged = withContext(Dispatchers.IO) {
            val file = File(cacheDir, Storage.TEMP_RESTORE_ARCHIVE)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            file
        }
        if (staged == null) {
            toast(R.string.profile_error)
            return
        }

        val manifest = withContext(Dispatchers.IO) { BackupBundle.readManifest(staged) }
        val problem = when {
            manifest == null || manifest.formatVersion > BackupBundle.FORMAT_VERSION ->
                R.string.profile_restore_invalid
            manifest.schemaVersion > FinanceDatabase.VERSION -> R.string.profile_restore_newer
            else -> 0
        }
        if (problem != 0) {
            staged.delete()
            toast(problem)
            return
        }

        val database = withContext(Dispatchers.IO) {
            val file = File(cacheDir, Storage.TEMP_RESTORE_DB)
            if (BackupBundle.extractDatabase(staged, file)) file else null
        }
        staged.delete()
        if (database == null) {
            toast(R.string.profile_restore_invalid)
            return
        }
        confirmRestore(database)
    }

    private fun confirmRestore(database: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_restore_confirm_title)
            .setMessage(R.string.profile_restore_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.profile_restore_confirm_action) { _, _ -> applyRestore(database) }
            .show()
    }

    /**
     * Replaces the database and restarts the process.
     *
     * The connection has to be closed first: overwriting a file SQLite still has open leaves the app
     * reading the old pages, and the stale `-wal`/`-shm` beside the new file is how a database ends
     * up "malformed". The restart is part of the fix, not a convenience — every screen in memory is
     * holding flows over the connection that was just closed.
     */
    private fun applyRestore(database: File) {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    RoomFinanceRepository.closeForRestore()
                    val target = getDatabasePath(DATABASE_NAME)
                    File("${target.path}-wal").delete()
                    File("${target.path}-shm").delete()
                    target.parentFile?.mkdirs()
                    database.copyTo(target, overwrite = true)
                    database.delete()
                    target
                }
            }
            if (applied.isSuccess) restartIntoApp() else toast(R.string.profile_restore_failed)
        }
    }

    private fun restartIntoApp() {
        toast(R.string.profile_restore_success)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun initials(name: String): String =
        ProfileAvatar.initialsFor(name, getString(R.string.app_name).take(1).uppercase())

    companion object {
        private const val REQUEST_BACKUP = 1001
        private const val REQUEST_RESTORE = 1002
        private const val REQUEST_AVATAR = 1003
        private const val FILE_PROVIDER_AUTHORITY = "com.dga.montra.fileprovider"
        private const val DATABASE_NAME = Storage.DATABASE
    }
}
