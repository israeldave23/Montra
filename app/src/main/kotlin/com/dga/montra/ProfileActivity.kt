package com.dga.montra

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView

class ProfileActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lumen_profile", MODE_PRIVATE) }
    private lateinit var avatar: TextView
    private lateinit var nameView: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_profile)
        avatar = findViewById(R.id.profile_avatar)
        nameView = findViewById(R.id.profile_name)
        findViewById<TextView>(R.id.profile_back).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.profile_edit).setOnClickListener { editName() }
        render()
    }

    private fun render() {
        val name = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        nameView.text = if (name.isEmpty()) getString(R.string.profile_default_name) else name
        avatar.text = initials(name)
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

    private fun initials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "L"
        val count = minOf(2, trimmed.codePointCount(0, trimmed.length))
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, count)).uppercase()
    }

    companion object { private const val KEY_NAME = "display_name" }
}
