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
            val doc = fetchHtml(url) ?: return emptyList()

            // Ищем карточки книг в результатах поиска
            val items = doc.select("div.book-item, div.search-result, article")
            for (item in items) {
                val title = item.selectFirst("a.book-title, h3 a, h2 a")?.text()?.trim() ?: continue
                val author = item.selectFirst("span.book-author, .author")?.text()?.trim() ?: "Неизвестен"
                val bookUrl = item.selectFirst("a.book-title, h3 a, h2 a")?.absUrl("href") ?: continue
                val description = item.selectFirst(".book-description, .description")?.text()?.trim()?.take(200) ?: ""
                val cycle = item.selectFirst(".book-cycle")?.text()?.trim()

                books.add(Book(title, author, cycle, description, bookUrl))
                if (books.size >= 10) break
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка поиска книг: ${e.message}", e)
        }
        return books
    }

    fun getGenres(): List<String> {
        Log.e("MYVOSK", "Получение списка жанров")
        val genres = mutableListOf<String>()
        try {
            val doc = fetchHtml("https://knigavuhe.org/") ?: return emptyList()
            val items = doc.select("a[href*='/genre/']")
            for (item in items) {
                val name = item.text().trim()
                if (name.isNotEmpty() && !genres.contains(name)) {
                    genres.add(name)
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка жанров: ${e.message}", e)
        }
        return genres
    }

    fun getBooksByGenre(genre: String): List<Book> {
        Log.e("MYVOSK", "Книги по жанру: '$genre'")
        return searchBooks("жанр $genre")
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
            val audioTags = doc.select("audio source[src], audio[src]")
            for (tag in audioTags) {
                val src = tag.attr("src")
                if (src.endsWith(".mp3", ignoreCase = true)) {
                    links.add(src)
                }
            }
            // Если не нашли через audio, ищем в JavaScript
            if (links.isEmpty()) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val matches = Regex("""https?://[^\s"']+\.mp3""").findAll(script.html())
                    for (match in matches) {
                        links.add(match.value)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка MP3: ${e.message}", e)
        }
        return links
    }

    private fun fetchHtml(url: String): Document? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            Jsoup.parse(body, url)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка загрузки HTML: ${e.message}", e)
            null
        }
    }
}
