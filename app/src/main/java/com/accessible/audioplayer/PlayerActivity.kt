package com.accessible.audioplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var debugText: TextView
    private lateinit var bookTitle: TextView
    private lateinit var chapterTitle: TextView
    private lateinit var btnPlayPause: TextView

    private var mp3Links: List<String> = emptyList()
    private var currentIndex = 0
    private var isPlaying = false
    private lateinit var settings: VoiceSettings

    private lateinit var gestureDetector: GestureDetector
    private lateinit var audioManager: AudioManager

    // Флаг: свайп распознан — ACTION_UP нужно поглотить, чтобы кнопка не нажалась
    private var swipeHandled = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "PLAYER_CMD_PLAY" -> { if (!isPlaying) togglePlayPause() }
                "PLAYER_CMD_NEXT" -> { nextChapter() }
                "PLAYER_CMD_PREV" -> { previousChapter() }
                "PLAYER_CMD_EXIT" -> { finish() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)
        settings = VoiceSettings(this)

        debugText = findViewById(R.id.debugText)
        bookTitle = findViewById(R.id.bookTitle)
        chapterTitle = findViewById(R.id.chapterTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val title = intent.getStringExtra("BOOK_TITLE") ?: "Неизвестная книга"
        val links = intent.getStringArrayListExtra("MP3_LINKS") ?: arrayListOf()
        val startIndex = intent.getIntExtra("START_INDEX", 0)

        bookTitle.text = title
        mp3Links = links
        currentIndex = startIndex

        // Сохраняем текущую книгу в настройки
        settings.lastBookTitle = title
        settings.lastBookLinks = links

        if (mp3Links.isEmpty()) {
            debugText.text = "Нет ссылок для воспроизведения"
            btnPlayPause.isEnabled = false
            return
        }

        player = ExoPlayer.Builder(this).build()

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    nextChapter()
                }
            }
        })

        playCurrent()

        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (diffX > 150) {
                        swipeHandled = true
                        previousChapter()
                        return true
                    } else if (diffX < -150) {
                        swipeHandled = true
                        nextChapter()
                        return true
                    }
                } else {
                    if (diffY < -150) {
                        swipeHandled = true
                        changeVolume(true)
                        return true
                    } else if (diffY > 150) {
                        swipeHandled = true
                        changeVolume(false)
                        return true
                    }
                }
                return false
            }
        })

        // Свайпы ловим на уровне Activity — до того, как кнопки их перехватят
        // (см. override fun dispatchTouchEvent ниже)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val filter = IntentFilter().apply {
            addAction("PLAYER_CMD_PLAY")
            addAction("PLAYER_CMD_NEXT")
            addAction("PLAYER_CMD_PREV")
            addAction("PLAYER_CMD_EXIT")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver, filter)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestureDetector.isInitialized) {
            gestureDetector.onTouchEvent(ev)
        }

        // Если свайп распознан — поглощаем ACTION_UP, чтобы кнопка не получила клик
        if (swipeHandled) {
            if (ev.action == MotionEvent.ACTION_UP ||
                ev.action == MotionEvent.ACTION_CANCEL) {
                swipeHandled = false
                return true
            }
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun playCurrent() {
        val link = mp3Links.getOrNull(currentIndex) ?: return
        chapterTitle.text = "Глава ${currentIndex + 1}"
        debugText.text = "Играю: ${link}\nГлава ${currentIndex + 1} из ${mp3Links.size}"

        settings.lastBookIndex = currentIndex

        player?.setMediaItem(MediaItem.fromUri(link))
        player?.prepare()
        player?.play()
        isPlaying = true
        btnPlayPause.text = "ПАУЗА"
        notifyService("PLAYING")
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            player?.pause()
            isPlaying = false
            btnPlayPause.text = "ИГРАТЬ"
            notifyService("PAUSED")
        } else {
            player?.play()
            isPlaying = true
            btnPlayPause.text = "ПАУЗА"
            notifyService("PLAYING")
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
        val stream = AudioManager.STREAM_MUSIC
        val current = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val next = if (up) (current + 1).coerceAtMost(max) else (current - 1).coerceAtLeast(0)
        audioManager.setStreamVolume(stream, next, 0)
        debugText.text = "Громкость: $next из $max"
    }

    private fun notifyService(state: String) {
        val intent = Intent("PLAYER_STATE_CHANGED").apply {
            putExtra("STATE", state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver)
        player?.release()
        player = null
        notifyService("CLOSED")
        super.onDestroy()
    }
}
