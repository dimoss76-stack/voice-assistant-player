package com.accessible.audioplayer

import android.os.Bundle
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

        player = ExoPlayer.Builder(this).build()
        playCurrent()

        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        // Инициализация жестов (ИСПРАВЛЕНО: правильные типы)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (diffX > 100) {
                        previousChapter()
                        return true
                    } else if (diffX < -100) {
                        nextChapter()
                        return true
                    }
                } else {
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
        })

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    // ИСПРАВЛЕНО: правильный тип MotionEvent (не nullable)
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        } else {
            player?.play()
            isPlaying = true
            btnPlayPause.text = "ПАУЗА"
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
        debugText.text = if (up) "Громкость: +" else "Громкость: -"
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
