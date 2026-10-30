package com.accessible.audioplayer

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Вся логика книг и плеера.
 * Работает поверх VoiceService — использует его поля и методы.
 */
class BooksController(private val service: VoiceService) {

    private val handler get() = service.handler
    private val tts get() = service.tts
    private val vosk get() = service.vosk
    private val settings get() = service.settings
    private val onlineBooks get() = service.onlineBooks

    // ==================== СЦЕНАРИЙ "ПЛЕЕР" ====================

    fun startPlayerFlow() {
        Log.e("MYVOSK", "=== startPlayerFlow ===")
        service.resetAllModes()

        val hasProgress = settings.lastBookLinks.isNotEmpty() && settings.lastBookTitle.isNotEmpty()
        Log.e("MYVOSK", "startPlayerFlow: hasProgress=$hasProgress, title='${settings.lastBookTitle}', глав=${settings.lastBookIndex}")

        if (hasProgress) {
            service.awaitingPlaybackAnswer = true
            tts.beepListen()
            handler.postDelayed({
                tts.speakOutShort("Продолжить воспроизведение?")
                handler.postDelayed({
                    if (service.awaitingPlaybackAnswer) {
                        tts.beepListen()
                        vosk.startListeningForPlayback()
                        tts.setIgnoreUntil(System.currentTimeMillis() + 500L)
                        service.startPlaybackAnswerTimeout()
                        Log.e("MYVOSK", "startPlayerFlow: слушаю да/нет")
                    }
                }, 1800L)
            }, 200L)
        } else {
            askBookQuery()
        }
    }

    fun askBookQuery() {
        Log.e("MYVOSK", "=== askBookQuery ===")
        service.resetAllModes()
        service.awaitingBookQuery = true
        tts.beepListen()
        handler.postDelayed({
            tts.speakOutShort("Что искать? Скажите название или автора")
            handler.postDelayed({
                if (service.awaitingBookQuery) {
                    tts.beepListen()
                    vosk.startListeningFreeForm()
                    service.startBookQueryTimeout()
                    Log.e("MYVOSK", "askBookQuery: слушаю запрос")
                }
            }, 2600L)
        }, 200L)
    }

    fun handlePlaybackAnswer(text: String) {
        val hasDa = text.contains("да") || text.contains("конечно") || text == "д"
        val hasNet = text.contains("нет") || text.startsWith("не") || text.contains("отмена")

        if (hasNet && !hasDa) {
            service.awaitingPlaybackAnswer = false
            service.cancelPlaybackAnswerTimeout()
            tts.beepOk()
            tts.speakOutShort("Хорошо")
            handler.postDelayed({ askBookQuery() }, 1500L)
            return
        }
        if (hasDa) {
            service.awaitingPlaybackAnswer = false
            service.cancelPlaybackAnswerTimeout()
            tts.beepOk()
            tts.speakOutShort("Продолжаю")
            handler.postDelayed({ resumeLastBook() }, 1500L)
            return
        }
        tts.beepError()
        tts.speakOutShort("Скажите да или нет")
        handler.postDelayed({
            if (service.awaitingPlaybackAnswer) {
                tts.beepListen()
                vosk.startListeningForPlayback()
                service.startPlaybackAnswerTimeout()
            }
        }, 2500L)
    }

    private fun resumeLastBook() {
        val title = settings.lastBookTitle
        val links = settings.lastBookLinks
        val index = settings.lastBookIndex

        if (title.isEmpty() || links.isEmpty()) {
            Log.e("MYVOSK", "resumeLastBook: нет данных, спрашиваю заново")
            askBookQuery()
            return
        }

        Log.e("MYVOSK", "resumeLastBook: '$title', глава ${index + 1}/${links.size}")

        val intent = Intent(service, PlayerActivity::class.java).apply {
            putExtra("BOOK_TITLE", title)
            putStringArrayListExtra("MP3_LINKS", ArrayList(links))
            putExtra("START_INDEX", index)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
        tts.speakOut("Открываю плеер, глава ${index + 1}")
    }

    // ==================== КОМАНДА "ВКЛЮЧИ КНИГУ" ====================

    fun handleBookCommand(command: String) {
        val bookCmd = settings.bookCommandWord.lowercase()
        val query = command
            .replace(bookCmd, "")
            .replace("включи книгу", "")
            .replace("найди онлайн", "")
            .replace("найти онлайн", "")
            .replace("поиск онлайн", "")
            .trim()

        // Если есть прогресс — сначала "Продолжить?"
        if (settings.lastBookLinks.isNotEmpty() && settings.lastBookTitle.isNotEmpty()) {
            service.awaitingPlaybackAnswer = true
            tts.beepListen()
            handler.postDelayed({
                tts.speakOutShort("Продолжить воспроизведение?")
                handler.postDelayed({
                    if (service.awaitingPlaybackAnswer) {
                        tts.beepListen()
                        vosk.startListeningForPlayback()
                        tts.setIgnoreUntil(System.currentTimeMillis() + 500
                                                               }
                }, 1800L)
            }, 200L)
            return
        }

        if (query.isNotEmpty() && query.length >= 2) {
            startBookSearch(query)
            return
        }
        askBookQuery()
    }

    // ==================== ЖАНРЫ ====================

    fun handleGenres() {
        tts.speakOut("Смотрю жанры...")
        Thread {
            val genres = onlineBooks.getGenres()
            handler.post {
                if (genres.isEmpty()) {
                    tts.speakOut("Не удалось получить жанры")
                    handler.postDelayed({ service.startListening() }, 2000L)
                } else {
                    tts.speakOut("Доступные жанры: ${genres.joinToString(", ")}")
                    handler.postDelayed({ service.startListening() }, 3000L)
                }
            }
        }.start()
    }

    // ==================== ПОИСК КНИГ ====================

    fun handleBookQuery(text: String) {
        service.cancelBookQueryTimeout()
        service.awaitingBookQuery = false
        val query = text.trim()
        if (query.isEmpty() || query.length < 2) {
            tts.beepError()
            tts.speakOutShort("Не расслышал. Отменено")
            service.startListening()
            return
        }
        startBookSearch(query)
    }

    private fun startBookSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            tts.beepError()
            tts.speakOutShort("Пустой запрос")
            service.startListening()
            return
        }
        tts.speakOut("Ищу $q...")
        Thread {
            val books = onlineBooks.searchBooks(q)
            handler.post {
                service.foundBooks = books
                service.currentBookIndex = 0
                if (books.isEmpty()) {
                    tts.beepError()
                    tts.speakOut("Ничего не найдено")
                    handler.postDelayed({ service.startListening() }, 2000L)
                    return@post
                }
                tts.speakOut("Найдено ${books.size} книг. Слушайте.")
                speakBooksList(0)
            }
        }.start()
    }

    private fun speakBooksList(startIndex: Int) {
        val books = service.foundBooks
        if (startIndex >= books.size) {
            service.awaitingBookNumber = false
            tts.speakOut("Конец списка")
            handler.postDelayed({ service.startListening() }, 2000L)
            return
        }
        service.currentBookIndex = startIndex
        val book = books[startIndex]
        val ordinal = service.ordinalWord(startIndex + 1)
        val cycleInfo = if (!book.cycle.isNullOrEmpty()) " Цикл: ${book.cycle}." else ""
        val desc = if (book.description.isNotEmpty()) " ${book.description.take(100)}." else ""

        service.awaitingBookNumber = true
        tts.speakOut("$ordinal — ${book.title}, автор ${book.author}.$cycleInfo$desc Скажите номер книги, или дальше.")
    }

    // ==================== ОЖИДАНИЕ НОМЕРА КНИГИ ====================

    fun handleBookNumber(text: String) {
        val lower = text.lowercase()
        val books = service.foundBooks

        if (lower.contains("отмена") || lower.contains("стоп") || lower.contains("хватит")) {
            service.awaitingBookNumber = false
            tts.beepOk()
            tts.speakOutShort("Отменено")
            service.startListening()
            return
        }

        if (lower.contains("дальше") || lower.contains("следующ") || lower.contains("далее")) {
            val nextIndex = service.currentBookIndex + 1
            if (nextIndex < books.size) {
                speakBooksList(nextIndex)
            } else {
                service.awaitingBookNumber = false
                tts.beepError()
                tts.speakOutShort("Это была последняя книга. Конец списка")
                handler.postDelayed({ service.startListening() }, 2000L)
            }
            return
        }

        val bookCmd = settings.bookCommandWord.lowercase()
        if (text.contains(bookCmd) || text.contains("включи") || text.contains("найди онлайн")) {
            service.awaitingBookNumber = false
            service.executeCommand(text)
            return
        }

        service.awaitingBookNumber = false
        val number = parseNumber(text)
        if (number != null && number in 1..books.size) {
            val book = books[number - 1]
            tts.beepOk()
            tts.speakOutShort("Запускаю ${book.title}")
            launchPlayer(book)
        } else {
            tts.beepError()
            tts.speakOutShort("Не понял. Скажите номер книги, или дальше")
            service.awaitingBookNumber = true
        }
    }

    private fun parseNumber(text: String): Int? {
        return when {
            text.contains("перв") || text.contains("один") || text.contains("1") -> 1
            text.contains("втор") || text.contains("два") || text.contains("2") -> 2
            text.contains("трет") || text.contains("три") || text.contains("3") -> 3
            text.contains("четв") || text.contains("четыр") || text.contains("4") -> 4
            text.contains("пят") || text.contains("5") -> 5
            text.contains("шест") || text.contains("6") -> 6
            text.contains("седьм") || text.contains("7") -> 7
            text.contains("восьм") || text.contains("8") -> 8
            text.contains("девят") || text.contains("9") -> 9
            text.contains("десят") || text.contains("10") -> 10
            else -> null
        }
    }

    // ==================== ЗАПУСК ПЛЕЕРА ====================

    private fun launchPlayer(book: OnlineBookEngine.Book) {
        service.awaitingBookNumber = false
        Thread {
            val links = onlineBooks.getMp3Links(book.url)
            handler.post {
                if (links.isEmpty()) {
                    tts.beepError()
                    tts.speakOut("Не удалось найти аудиофайлы")
                    handler.postDelayed({ service.startListening() }, 2000L)
                    return@post
                }
                val intent = Intent(service, PlayerActivity::class.java).apply {
                    putExtra("BOOK_TITLE", book.title)
                    putStringArrayListExtra("MP3_LINKS", ArrayList(links))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                tts.speakOut("Открываю плеер")
            }
        }.start()
    }

    // ==================== КОМАНДЫ ПЛЕЕРУ ====================

    fun sendPlayerCommand(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(service).sendBroadcast(intent)
        Log.e("MYVOSK", "Отправлена команда плееру: $action")
    }
}
