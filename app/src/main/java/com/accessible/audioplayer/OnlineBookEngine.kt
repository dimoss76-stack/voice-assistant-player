package com.accessible.audioplayer

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class OnlineBookEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Book(
        val title: String,
        val author: String,
        val cycle: String?,
        val description: String,
        val url: String
    )

    fun searchBooks(query: String): List<Book> {
        Log.e("MYVOSK", "Поиск онлайн книг: '$query'")
        val books = mutableListOf<Book>()
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://knigavuhe.org/search/?q=$encodedQuery"
            Log.e("MYVOSK", "URL поиска: $url")
            val doc = fetchHtml(url) ?: run {
                Log.e("MYVOSK", "fetchHtml вернул null")
                return emptyList()
            }

            // Реальная вёрстка knigavuhe.org: карточки в div.bookkitem внутри #books_list
            val items = doc.select("#books_list div.bookkitem")
            Log.e("MYVOSK", "Найдено карточек: ${items.size}")

            for (item in items) {
                val titleEl = item.selectFirst("a.bookkitem_name")
                if (titleEl == null) {
                    Log.e("MYVOSK", "Пропуск: нет a.bookkitem_name")
                    continue
                }
                val title = titleEl.text().trim()
                if (title.isEmpty()) continue

                val bookUrl = titleEl.absUrl("href")
                if (bookUrl.isEmpty()) {
                    Log.e("MYVOSK", "Пропуск '$title': пустой href")
                    continue
                }

                val author = item.selectFirst(".bookkitem_author a")?.text()?.trim() ?: "Неизвестен"
                val description = item.selectFirst(".bookkitem_about")?.text()?.trim()?.take(200) ?: ""
                val cycle = item.selectFirst(".bookkitem_meta a[href*='/series/']")?.text()?.trim()

                books.add(Book(title, author, cycle, description, bookUrl))
                Log.e("MYVOSK", "Книга: '$title' / $author / $bookUrl")
                if (books.size >= 10) break
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка поиска книг: ${e.message}", e)
        }
        Log.e("MYVOSK", "Итого книг: ${books.size}")
        return books
    }

    fun getGenres(): List<String> {
        Log.e("MYVOSK", "Получение списка жанров")
        val genres = mutableListOf<String>()
        try {
            val doc = fetchHtml("https://knigavuhe.org/genres/") ?: return emptyList()
            // На странице жанров ссылки ведут на /genre/...
            val items = doc.select("a[href^='/genre/']")
            for (item in items) {
                val name = item.text().trim()
                if (name.isNotEmpty() && name.length < 40 && !genres.contains(name)) {
                    genres.add(name)
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка жанров: ${e.message}", e)
        }
        Log.e("MYVOSK", "Жанров найдено: ${genres.size}")
        return genres
    }

    fun getBooksByGenre(genre: String): List<Book> {
        Log.e("MYVOSK", "Книги по жанру: '$genre'")
        return searchBooks(genre)
    }

    fun getBooksByCycle(cycle: String): List<Book> {
        Log.e("MYVOSK", "Книги цикла: '$cycle'")
        return searchBooks(cycle)
    }

    fun getMp3Links(bookUrl: String): List<String> {
        Log.e("MYVOSK", "Получение ссылок MP3: $bookUrl")
        val links = mutableListOf<String>()
        try {
            val doc = fetchHtml(bookUrl) ?: return emptyList()

            // 1) Прямые <audio> теги
            val audioTags = doc.select("audio source[src], audio[src]")
            for (tag in audioTags) {
                val src = tag.attr("src")
                if (src.isNotEmpty()) {
                    val abs = if (src.startsWith("http")) src else "https:$src"
                    if (abs.endsWith(".mp3", ignoreCase = true) && !links.contains(abs)) {
                        links.add(abs)
                    }
                }
            }

            // 2) JS-плеер: ищем плейлист в скриптах (bookPlayer / playlist / mp3)
            if (links.isEmpty()) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val html = script.html()
                    val matches = Regex("""https?://[^\s"'<>]+\.mp3""").findAll(html)
                    for (match in matches) {
                        val url = match.value
                        if (!links.contains(url)) links.add(url)
                    }
                }
            }

            // 3) Иногда ссылки лежат в data-атрибутах
            if (links.isEmpty()) {
                val elements = doc.select("[data-mp3], [data-src], [data-url]")
                for (el in elements) {
                    for (attr in listOf("data-mp3", "data-src", "data-url")) {
                        val v = el.attr(attr)
                        if (v.contains(".mp3")) {
                            val abs = if (v.startsWith("http")) v else "https:$v"
                            if (!links.contains(abs)) links.add(abs)
                        }
                    }
                }
            }

            Log.e("MYVOSK", "MP3-ссылок найдено: ${links.size}")
            for (l in links.take(5)) Log.e("MYVOSK", "  mp3: $l")
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка MP3: ${e.message}", e)
        }
        return links
    }

    private fun fetchHtml(url: String): Document? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()
            val response = client.newCall(request).execute()
            Log.e("MYVOSK", "HTTP ${response.code} для $url")
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.string()
            response.close()
            if (body == null) {
                Log.e("MYVOSK", "Пустой body")
                return null
            }
            Log.e("MYVOSK", "HTML размер: ${body.length}")
            Jsoup.parse(body, url)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка загрузки HTML: ${e.message}", e)
            null
        }
    }
}
