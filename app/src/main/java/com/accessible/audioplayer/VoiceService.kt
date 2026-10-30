package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VoiceService : Service() {

    companion object {
        @Volatile
        var instance: VoiceService? = null
    }

    // === Поля, к которым обращается BooksController ===
    lateinit var settings: VoiceSettings
        private set
    lateinit var tts: TtsSpeaker
        private set
    lateinit var vosk: VoskEngine
        private set
    lateinit var onlineBooks: OnlineBookEngine
        private set
    lateinit var contactCaller: ContactCaller
        private set

    val handler = Handler(Looper.getMainLooper())

    var isActivated = false
    var lastActivationTime = 0L

    var recordingMode = false
    var recordingFieldName: String = ""
    var resultReceiver: ResultReceiver? = null

    var awaitingAnswer = false
    var choosingContact = false
    var contactList: List<ContactCaller.Contact> = emptyList()
    var contactIndex = 0
    var awaitingName = false
    var inCallMonitoring = false

    var readingContacts = false
    var contactsToRead: List<ContactCaller.Contact> = emptyList()
    var contactsReadIndex = 0
    var stopListeningActive = false

    var awaitingBookNumber = false
    var awaitingBookQuery = false
    var awaitingPlaybackAnswer = false
    var foundBooks: List<OnlineBookEngine.Book> = emptyList()
    var currentBookIndex = 0

    var cachedNames: List<String> = emptyList()
    var cacheTime = 0L

    var silenceTimeout: Runnable? = null
    var choosingTimeout: Runnable? = null
    var bookQueryTimeout: Runnable? = null
    var playbackAnswerTimeout: Runnable? = null

    var playerCommandsMode = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val resetActivation = Runnable { isActivated = false }

    // Контроллер книг/плеера (вынесен в отдельный файл)
    private lateinit var books: BooksController

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PLAYER_STATE_CHANGED") {
                val state = intent.getStringExtra("STATE")
                Log.e("MYVOSK", "Получено состояние плеера: $state")
                when (state) {
                    "PLAYING", "CLOSED" -> {
                        playerCommandsMode = false
                        if (state == "CLOSED") {
                            startListening()
                        } else {
                            vosk.stop()
                        }
                    }
                    "PAUSED" -> {
                        playerCommandsMode = true
                        vosk.startListeningForPlayer()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.e("MYVOSK", "0. onCreate сервиса")
        settings = VoiceSettings(this)
        contactCaller = ContactCaller(this)
        onlineBooks = OnlineBookEngine()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant::WakeLock")
        wakeLock?.acquire()

        tts = TtsSpeaker(this)
        tts.init()

        vosk = VoskEngine(
            context = this,
            onResultCallback = { text -> handleRecognizedText(text) },
            onErrorCallback = { e -> Log.e("MYVOSK", "Vosk error: ${e.message}") }
        )
        vosk.onModelReady = {
            startListening()
            tts.speakOut("Помощник готов")
        }
        vosk.init()

        books = BooksController(this)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            playerStateReceiver,
            IntentFilter("PLAYER_STATE_CHANGED")
        )

        showNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "RECORD_WORD" -> {
                val fieldName = intent.getStringExtra("FIELD") ?: ""
                resultReceiver = intent.getParcelableExtra("RECEIVER")
                startWordRecording(fieldName)
            }
            "OPEN_PLAYER_FLOW" -> {
                Log.e("MYVOSK", "onStartCommand: OPEN_PLAYER_FLOW")
                books.startPlayerFlow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playerStateReceiver)
        handler.removeCallbacks(resetActivation)
        cancelSilenceTimeout()
        cancelChoosingTimeout()
        cancelBookQueryTimeout()
        cancelPlaybackAnswerTimeout()
        tts.cancelConfirmBeep()
        readingContacts = false
        wakeLock?.let { if (it.isHeld) it.release() }
        vosk.release()
        tts.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== ВХОДЯЩИЕ ЗВОНКИ ====================

    fun onIncomingCall(callerName: String) {
        Log.e("MYVOSK", "onIncomingCall: $callerName")
        resetAllModes()
        awaitingAnswer = true
        tts.speakOutShort("Звонит $callerName. Ответить?")
        tts.scheduleConfirmBeep()
    }

    fun onCallAnswered() {
        if (inCallMonitoring) return
        Log.e("MYVOSK", "=== РЕЖИМ МОНИТОРИНГА ВКЛЮЧЁН ===")
        resetAllModes()
        tts.cancelConfirmBeep()
        inCallMonitoring = true
        tts.setIgnoreUntil(System.currentTimeMillis() + 1500L)
        vosk.startListeningForHangup()
    }

    fun onCallEnded() {
        if (!inCallMonitoring) return
        Log.e("MYVOSK", "=== РЕЖИМ МОНИТОРИНГА ВЫКЛЮЧЕН ===")
        resetAllModes()
        inCallMonitoring = false
        tts.setIgnoreUntil(System.currentTimeMillis() + 2000L)
        startListening()
    }

    // ==================== СБРОС ВСЕХ РЕЖИМОВ ====================

    fun resetAllModes() {
        isActivated = false
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
        awaitingBookNumber = false
        awaitingBookQuery = false
        awaitingPlaybackAnswer = false
        readingContacts = false
        playerCommandsMode = false
        stopListeningActive = false
        contactList = emptyList()
        contactIndex = 0
        cancelSilenceTimeout()
        cancelChoosingTimeout()
        cancelBookQueryTimeout()
        cancelPlaybackAnswerTimeout()
        tts.cancelConfirmBeep()
    }

    // ==================== БАЗОВОЕ СЛУШАНИЕ ====================

    fun startListening() {
        cancelSilenceTimeout()
        cancelBookQueryTimeout()
        stopListeningActive = false
        awaitingBookQuery = false
        playerCommandsMode = false
        vosk.startListening(
            activation = settings.activationWord,
            confirm = settings.confirmWord,
            shutdown = settings.shutdownWord
        )
    }

    fun startListeningForName() {
        cancelSilenceTimeout()
        val now = System.currentTimeMillis()
        if (now - cacheTime > 60000L || cachedNames.isEmpty()) {
            cachedNames = contactCaller.getAllContactNames()
            cacheTime = now
        }
        Log.e("MYVOSK", "Контактов в словаре: ${cachedNames.size}")

        vosk.prepareListeningForNames(cachedNames)
        tts.beepName()
        tts.setIgnoreUntil(System.currentTimeMillis() + 800L)
        handler.postDelayed({
            vosk.startListeningNow()
            Log.e("MYVOSK", "Распознавание имён запущено (${cachedNames.size} имён)")
            startSilenceTimeout()
        }, 1000L)
    }

    // ==================== ТАЙМАУТЫ ====================

    fun startSilenceTimeout() {
        cancelSilenceTimeout()
        silenceTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут молчания (ожидание имени)")
            if (awaitingName) {
                tts.speakOutShort("Не слышу. Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3000L)
            }
        }
        handler.postDelayed(silenceTimeout!!, 10000L)
    }

    fun cancelSilenceTimeout() {
        silenceTimeout?.let { handler.removeCallbacks(it) }
        silenceTimeout = null
    }

    fun startChoosingTimeout() {
        cancelChoosingTimeout()
        choosingTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут выбора контакта — сброс")
            choosingContact = false
            contactList = emptyList()
            contactIndex = 0
            tts.beepError()
            tts.speakOutShort("Отменено")
            startListening()
        }
        handler.postDelayed(choosingTimeout!!, 15000L)
    }

    fun cancelChoosingTimeout() {
        choosingTimeout?.let { handler.removeCallbacks(it) }
        choosingTimeout = null
    }

    fun startBookQueryTimeout() {
        cancelBookQueryTimeout()
        bookQueryTimeout = Runnable {
            if (awaitingBookQuery) {
                Log.e("MYVOSK", "Таймаут поискового запроса")
                awaitingBookQuery = false
                tts.beepError()
                tts.speakOutShort("Не слышу запрос. Отменено")
                startListening()
            }
        }
        handler.postDelayed(bookQueryTimeout!!, 8000L)
    }

    fun cancelBookQueryTimeout() {
        bookQueryTimeout?.let { handler.removeCallbacks(it) }
        bookQueryTimeout = null
    }

    fun startPlaybackAnswerTimeout() {
        cancelPlaybackAnswerTimeout()
        playbackAnswerTimeout = Runnable {
            if (awaitingPlaybackAnswer) {
                Log.e("MYVOSK", "Таймаут ответа на 'Продолжить?'")
                awaitingPlaybackAnswer = false
                tts.beepError()
                tts.speakOutShort("Не слышу. Отменено")
                startListening()
            }
        }
        handler.postDelayed(playbackAnswerTimeout!!, 10000L)
    }

    fun cancelPlaybackAnswerTimeout() {
        playbackAnswerTimeout?.let { handler.removeCallbacks(it) }
        playbackAnswerTimeout = null
    }

    // ==================== ЧТЕНИЕ КОНТАКТОВ ====================

    fun startReadingContacts() {
        val contacts = contactCaller.getAllContactsSorted()
        if (contacts.isEmpty()) {
            tts.beepError()
            tts.speakOutShort("Контакты не найдены")
            return
        }
        readingContacts = true
        contactsToRead = contacts
        contactsReadIndex = 0
        val count = contacts.size
        val countWord = when {
            count % 10 == 1 && count % 100 != 11 -> "$count контакт"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count контакта"
            else -> "$count контактов"
        }
        tts.speakOut("Всего $countWord. Слушайте.")
        val delayAfterIntro = tts.ignoreUntil - System.currentTimeMillis() + 300L
        handler.postDelayed({
            vosk.startListeningForStop()
            stopListeningActive = true
            tts.beepListen()
            readNextContact()
        }, delayAfterIntro.coerceAtLeast(3000L))
    }

    private fun readNextContact() {
        if (!readingContacts) return
        if (contactsReadIndex >= contactsToRead.size) {
            readingContacts = false
            stopListeningActive = false
            tts.beepOk()
            tts.speakOutShort("Конец списка")
            handler.postDelayed({ startListening() }, 3000L)
            return
        }
        val contact = contactsToRead[contactsReadIndex]
        contactsReadIndex++
        tts.speakOutShort(contact.name)
        val delayAfterTts = (tts.ignoreUntil - System.currentTimeMillis()) + 500L
        handler.postDelayed({
            if (!readingContacts) return@postDelayed
            tts.beepListen()
            handler.postDelayed({
                if (!readingContacts) return@postDelayed
                readNextContact()
            }, 2000L)
        }, delayAfterTts.coerceAtLeast(2000L))
    }

    fun stopReadingContacts() {
        readingContacts = false
        stopListeningActive = false
        tts.beepOk()
        tts.speakOutShort("Остановлено")
        handler.postDelayed({ startListening() }, 3000L)
    }

    // ==================== ЗВОНКИ ====================

    fun startContactCall(query: String) {
        val contacts = contactCaller.findContacts(query)
        if (contacts.isEmpty()) {
            tts.beepError()
            tts.speakOut("Контакт не найден. Назовите другое имя")
            awaitingName = true
            handler.postDelayed({ startListeningForName() }, 6000L)
            return
        }
        if (contacts.size == 1) {
            val c = contacts[0]
            choosingContact = true
            contactList = contacts
            contactIndex = 0
            tts.speakOutShort("${c.name}. Звонить?")
            handler.postDelayed({ startListening() }, 100L)
            tts.scheduleConfirmBeep()
            startChoosingTimeout()
            return
        }
        contactList = contacts
        contactIndex = 0
        choosingContact = true
        tts.speakOutShort("Нашёл ${contacts.size}. ${ordinalWord(1)} ${contacts[0].name}. Звонить?")
        handler.postDelayed({ startListening() }, 100L)
        tts.scheduleConfirmBeep()
        startChoosingTimeout()
    }

    fun ordinalWord(n: Int): String {
        return when (n) {
            1 -> "Первый"; 2 -> "Второй"; 3 -> "Третий"
            4 -> "Четвёртый"; 5 -> "Пятый"; 6 -> "Шестой"
            7 -> "Седьмой"; 8 -> "Восьмой"; 9 -> "Девятый"
            10 -> "Десятый"; else -> "$n-й"
        }
    }

    fun acceptCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
            }
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка приёма: ${e.message}", e) }
    }

    fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.endCall()
            }
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка отклонения: ${e.message}", e) }
    }

    // ==================== ЗАПИСЬ СЛОВА ====================

    private fun startWordRecording(fieldName: String) {
        recordingFieldName = fieldName
        recordingMode = true
        tts.speakOut("Говорите")
        handler.postDelayed({
            Thread {
                val word = vosk.recordSingleWord(5000L)
                sendWordResult(word)
            }.start()
        }, 1500L)
    }

    private fun sendWordResult(text: String) {
        handler.post {
            val bundle = Bundle().apply {
                putString("FIELD", recordingFieldName)
                putString("WORD", text)
            }
            try { resultReceiver?.send(0, bundle) } catch (_: Exception) {}
            recordingMode = false
            recordingFieldName = ""
        }
    }

    // ==================== УВЕДОМЛЕНИЕ ====================

    private fun showNotification() {
        val channelId = "voice_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Голосовое управление", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Помощник активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    // ==================== ГЛАВНЫЙ ОБРАБОТЧИК ТЕКСТА ====================
    // (вынесен в BooksController для книг, здесь — звонки/контакты/команды)

    fun handleRecognizedText(text: String) {
        Log.e("MYVOSK", "Распознано: '$text' (awaitingAnswer=$awaitingAnswer, awaitingName=$awaitingName, reading=$readingContacts, awaitingBookNumber=$awaitingBookNumber, awaitingBookQuery=$awaitingBookQuery, awaitingPlaybackAnswer=$awaitingPlaybackAnswer, playerMode=$playerCommandsMode)")

        if (recordingMode) return
        if (System.currentTimeMillis() < tts.ignoreUntil) return

        // 1. Режим плеера (пауза)
        if (playerCommandsMode) {
            val lower = text.lowercase()
            when {
                lower.contains("продолжить") || lower.contains("продолжай") || lower.contains("играть") -> {
                    tts.beepOk()
                    books.sendPlayerCommand("PLAYER_CMD_PLAY")
                }
                lower.contains("вперед") || lower.contains("вперёд") || lower.contains("следующая") -> {
                    tts.beepOk()
                    books.sendPlayerCommand("PLAYER_CMD_NEXT")
                }
                lower.contains("назад") || lower.contains("предыдущая") -> {
                    tts.beepOk()
                    books.sendPlayerCommand("PLAYER_CMD_PREV")
                }
                lower.contains("отмена") || lower.contains("выйти") || lower.contains("выход") || lower.contains("стоп") -> {
                    tts.beepOk()
                    books.sendPlayerCommand("PLAYER_CMD_EXIT")
                    playerCommandsMode = false
                    handler.postDelayed({ startListening() }, 1000L)
                }
            }
            return
        }

        // 2. Ответ на "Продолжить воспроизведение?"
        if (awaitingPlaybackAnswer) {
            books.handlePlaybackAnswer(text)
            return
        }

        // 3. Входящий звонок: Ответить?
        if (awaitingAnswer) {
            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")
            if (hasNet && !hasDa) {
                tts.beepOk()
                tts.speakOutShort("Отклоняю")
                rejectCall()
                awaitingAnswer = false
                return
            }
            if (hasDa) {
                tts.beepOk()
                tts.speakOutShort("Отвечаю")
                acceptCall()
                awaitingAnswer = false
                return
            }
            return
        }

        // 4. Ожидание поискового запроса книг
        if (awaitingBookQuery) {
            books.handleBookQuery(text)
            return
        }

        // 5. Ожидание номера книги
        if (awaitingBookNumber) {
            books.handleBookNumber(text)
            return
        }

        // 6. Чтение контактов (стоп)
        if (readingContacts || stopListeningActive) {
            val words = text.split(" ").toSet()
            val isStop = words.contains("стоп") || words.contains("отмена") ||
                         words.contains("отменить") || words.contains("хватит") ||
                         words.contains("остановись") || words.contains("прекрати")
            if (isStop) {
                stopReadingContacts()
            }
            return
        }

        // 7. Завершение звонка
        if (inCallMonitoring) {
            val words = text.split(" ").toSet()
            val isHangupPhrase = (words.contains("завершить") && words.contains("звонок")) ||
                                 (words.contains("положить") && words.contains("трубку"))
            if (isHangupPhrase) {
                tts.beepOk()
                tts.speakOutShort("Завершаю")
                handler.postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                            tm.endCall()
                        }
                    } catch (e: Exception) {}
                }, 1500L)
            }
            return
        }

        // 8. Отмена в любом активном режиме
        if (isAnyActiveMode()) {
            if (text.contains("отмена") || text.contains("отменить") ||
                text.contains("стоп") || text.contains("хватит")) {
                resetAllModes()
                tts.beepOk()
                tts.speakOutShort("Отменено")
                return
            }
        }

        // 9. Ожидание имени контакта
        if (awaitingName) {
            handleAwaitingName(text)
            return
        }

        // 10. Выбор контакта
        if (choosingContact) {
            handleChoosingContact(text)
            return
        }

        // 11. Активация
        val activation = settings.activationWord
        val shutdownWord = settings.shutdownWord

        if (settings.shutdownEnabled && text.contains(shutdownWord)) {
            tts.speakOut("Выключаюсь")
            handler.postDelayed({ stopSelf(); System.exit(0) }, 1500)
            return
        }

        if (!isActivated) {
            if (text.contains(activation)) {
                if (System.currentTimeMillis() - lastActivationTime < 3000) return
                lastActivationTime = System.currentTimeMillis()
                isActivated = true
                val cmd = text.replace(activation, "").trim()
                if (cmd.isNotEmpty()) {
                    executeCommand(cmd)
                    isActivated = false
                } else {
                    tts.beepListen()
                    handler.postDelayed({ tts.speakOut(settings.confirmWord) }, 200)
                    handler.postDelayed({ tts.beepListen() }, 2500L)
                    handler.removeCallbacks(resetActivation)
                    handler.postDelayed(resetActivation, 15000L)
                }
            }
        } else {
            if (text == activation) return
            if (text.length < 3) return
            executeCommand(text)
            handler.removeCallbacks(resetActivation)
            handler.postDelayed(resetActivation, 10000L)
        }
    }

    private fun isAnyActiveMode(): Boolean {
        return choosingContact || awaitingName || awaitingAnswer ||
               inCallMonitoring || isActivated || readingContacts ||
               awaitingBookNumber || awaitingBookQuery || awaitingPlaybackAnswer
    }

    private fun handleAwaitingName(text: String) {
        cancelSilenceTimeout()
        val nameOnly = text.split(" ").firstOrNull()?.trim() ?: text
        val commandWords = listOf(
            "нет", "да", "отмена", "отменить", "позвони", "позвонить",
            "звонок", "трубку", "дата", "время", "день", "число",
            "завершить", "положить", "набери", "набрать",
            "контакты", "список", "покажи"
        )
        if (nameOnly in commandWords || nameOnly.length < 2) {
            tts.beepError()
            tts.speakOutShort("Назовите имя")
            handler.postDelayed({ startListeningForName() }, 3500L)
            return
        }
        awaitingName = false
        tts.beepOk()
        tts.setIgnoreUntil(System.currentTimeMillis() + 500L)
        handler.postDelayed({ startContactCall(nameOnly) }, 400)
    }

    private fun handleChoosingContact(text: String) {
        cancelChoosingTimeout()
        val hasDa = text.contains("да") || text.contains("возьми") ||
                    text.contains("взять") || text.contains("поднять") || text == "д"
        val hasNet = text.contains("нет") || text.startsWith("не") ||
                     text.contains("сброс") || text.contains("отклон")

        if (hasNet && !hasDa) {
            contactIndex++
            if (contactIndex < contactList.size) {
                val c = contactList[contactIndex]
                tts.speakOutShort("${ordinalWord(contactIndex + 1)} ${c.name}. Звонить?")
                handler.postDelayed({ startListening() }, 100L)
                tts.scheduleConfirmBeep()
                startChoosingTimeout()
            } else {
                choosingContact = false
                contactList = emptyList()
                contactIndex = 0
                awaitingName = true
                tts.beepError()
                tts.speakOutShort("Больше нет. Назовите другое имя")
                handler.postDelayed({ startListeningForName() }, 6000L)
            }
            return
        }
        if (hasDa) {
            val c = contactList.getOrNull(contactIndex)
            if (c != null) {
                tts.beepOk()
                tts.speakOutShort("Звоню")
                choosingContact = false
                contactList = emptyList()
                contactIndex = 0
                tts.setIgnoreUntil(System.currentTimeMillis() + 10000L)
                handler.postDelayed({
                    contactCaller.callNumber(c.number)
                }, 1500L)
            } else {
                choosingContact = false
                contactList = emptyList()
                contactIndex = 0
            }
            return
        }
        startChoosingTimeout()
    }

    // ==================== КОМАНДЫ ====================

    fun executeCommand(command: String) {
        Log.e("MYVOSK", "executeCommand: '$command'")
        val calendar = Calendar.getInstance()
        val bookCmd = settings.bookCommandWord.lowercase()

        // Книги
        if (command.contains(bookCmd) || command.contains("включи книгу") || command.contains("найди онлайн")) {
            books.handleBookCommand(command)
            return
        }

        if (command.contains("жанры") || command.contains("какие жанры") || command.contains("жанр")) {
            books.handleGenres()
            return
        }

        if (command.contains("контакты") || command.contains("список")) {
            startReadingContacts()
            return
        }

        if (command.contains("позвони") || command.contains("позвонить") ||
            command.contains("набери") || command.contains("набрать")) {
            val name = command
                .replace("позвонить", "").replace("позвони", "")
                .replace("набрать", "").replace("набери", "").trim()
            if (name.isEmpty() || name.length < 3 ||
                name == "день" || name == "дата" || name == "время" ||
                name == "часы" || name == "час") {
                awaitingName = true
                tts.speakOut("Каму звонить?")
                handler.postDelayed({ startListeningForName() }, 3000L)
                return
            }
            startContactCall(name)
            return
        }

        if (command.contains("дата") || command.contains("день") || command.contains("число")) {
            val f = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
            tts.speakOut("Сегодня ${f.format(calendar.time)}")
            return
        }

        if (command.contains("время") || command.contains("час") || command.contains("сколько")) {
            val f = SimpleDateFormat("HH:mm", Locale("ru"))
            tts.speakOut("Сейчас ${f.format(calendar.time)}")
            return
        }

        tts.beepError()
        tts.speakOut("Не понял")
    }
}
