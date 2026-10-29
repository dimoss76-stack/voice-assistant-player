package com.accessible.audioplayer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: VoiceSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = VoiceSettings(this)

        val editActivation = findViewById<EditText>(R.id.editActivation)
        val editConfirm = findViewById<EditText>(R.id.editConfirm)
        val editBookCommand = findViewById<EditText>(R.id.editBookCommand)
        val switchShutdown = findViewById<Switch>(R.id.switchShutdown)
        
        // Заполняем поля текущими значениями
        editActivation.setText(settings.activationWord)
        editConfirm.setText(settings.confirmWord)
        editBookCommand.setText(settings.bookCommandWord)
        switchShutdown.isChecked = settings.shutdownEnabled

        // Сохранение при нажатии кнопки "Назад"
        findViewById<Button>(R.id.btnBackSettings).setOnClickListener {
            saveSettings(editActivation, editConfirm, editBookCommand, switchShutdown)
            finish()
        }
    }

    private fun saveSettings(
        editActivation: EditText,
        editConfirm: EditText,
        editBookCommand: EditText,
        switchShutdown: Switch
    ) {
        settings.activationWord = editActivation.text.toString().ifBlank { "слушай" }
        settings.confirmWord = editConfirm.text.toString().ifBlank { "говорите" }
        settings.bookCommandWord = editBookCommand.text.toString().ifBlank { "включи книгу" }
        settings.shutdownEnabled = switchShutdown.isChecked
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
    }
}
