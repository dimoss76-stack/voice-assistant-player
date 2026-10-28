package com.accessible.audioplayer

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var debugText: TextView
    private lateinit var bookTitle: TextView
    private lateinit var chapterTitle: TextView
    private lateinit var btnPlayPause: Button

    private var mp3Links: List<String> = emptyList()
    private var currentIndex = 0
    private var isPlaying = false

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        debugText = findViewById(R.id.debugText)
        bookTitle = findViewById(R.id.bookTitle)
        chapterTitle = findViewById(R.id.chapterTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        // Получаем данные из Intent
        val title = intent.getStringExtra("BOOK_TITLE") ?: "Неизвестная книга"
        val links = intent.getStringArrayListExtra("MP3_LINKS") ?: arrayListOf()

        bookTitle.text = title
        mp3Links = links
        currentIndex = 0

        debugText.text = "Ссылок: ${mp3Links.size}\nТекущая: 1"

        if (mp3Links.isEmpty()) {
            debugText.text = "Нет ссылок для воспроизведения"
            btnPlayPause.isEnabled = false
            return
        }

        // Инициализация плеера
        player = ExoPlayer.Builder(this).build()
        playCurrent()

        // Кнопка Плей/Пауза
        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        // Жесты
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    // Горизонтальный свайп
                    if (diffX > 100) {
                        // Вправо — предыдущая глава
                        previousChapter()
                        return true
                    } else if (diffX < -100) {
                        // Влево — следующая глава
                        nextChapter()
                        return true
                    }
                } else {
                    // Вертикальный свайп — громкость
                    if (diffY < -100) {
                        changeVolume(true)
                        return true
                    } else if (diffY > 100) {
                        changeVolume(false)
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent?): Boolean = true
        })

        // Кнопка Назад
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    private fun playCurrent() {
        val link = mp3Links.getOrNull(currentIndex) ?: return
        chapterTitle.text = "Глава ${currentIndex + 1}"
        debugText.text = "Играю: ${link}\nГлава ${currentIndex + 1} из ${mp3Links.size}"

        player?.setMediaItem(MediaItem.fromUri(link))
        player?.prepare()
        player?.play()
        isPlaying = true
        btnPlayPause.text = "ПАУЗА"
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            player?.pause()
            isPlaying = false
            btnPlayPause.text = "ИГРАТЬ"
            // TODO: включить микрофон для голосовых команд ("Выйти")
        } else {
            player?.play()
            isPlaying = true
            btnPlayPause.text = "ПАУЗА"
            // TODO: выключить микрофон
        }
    }

    private fun nextChapter() {
        if (currentIndex < mp3Links.size - 1) {
            currentIndex++
            playCurrent()
        } else {
            debugText.text = "Это последняя глава"
        }
    }

    private fun previousChapter() {
        if (currentIndex > 0) {
            currentIndex--
            playCurrent()
        } else {
            debugText.text = "Это первая глава"
        }
    }

    private fun changeVolume(up: Boolean) {
        // TODO: изменить громкость
        debugText.text = if (up) "Громкость: +" else "Громкость: -"
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
