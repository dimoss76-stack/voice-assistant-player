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

            var items = doc.select("#books_list div.bookkitem")
            if (items.isEmpty()) items = doc.select("div.bookkitem")
            if (items.isEmpty()) items = doc.select("div.book-item")

            if (items.isEmpty()) {
                val bookLinks = doc.select("a[href^='/book/']")
                val seen = mutableSetOf<String>()
                for (a in bookLinks) {
                    val href = a.absUrl("href")
                    if (href.isEmpty() || seen.contains(href)) continue
                    seen.add(href)
                    val title = a.text().trim()
                    if (title.isEmpty() || title.length < 2) continue
                    books.add(Book(title, "Неизвестен", null, "", href))
                    if (books.size >= 10) break
                }
            } else {
                for (item in items) {
                    val titleEl = item.selectFirst("a.bookkitem_name") ?: item.selectFirst("a[href^='/book/']") ?: continue
                    val title = titleEl.text().trim()
                    val bookUrl = titleEl.absUrl("href")
                    if (title.isEmpty() || bookUrl.isEmpty()) continue

                    val author = item.selectFirst(".bookkitem_author a")?.text()?.trim() ?: "Неизвестен"
                    val description = item.selectFirst(".bookkitem_about")?.text()?.trim()?.take(200) ?: ""
                    val cycle = item.selectFirst(".bookkitem_meta a[href*='/series/']")?.text()?.trim()

                    books.add(Book(title, author, cycle, description, bookUrl))
                    if (books.size >= 10) break
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка поиска книг: ${e.message}", e)
        }
        Log.e("MYVOSK", "Итого книг: ${books.size}")
        return books
    }

    fun getGenres(): List<String> {
        val genres = mutableListOf<String>()
        try {
            val doc = fetchHtml("https://knigavuhe.org/genres/") ?: return emptyList()
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
        return genres
    }

    fun getMp3Links(bookUrl: String): List<String> {
        Log.e("MYVOSK", "Получение ссылок MP3: $bookUrl")
        val links = mutableListOf<String>()
        try {
            val doc = fetchHtml(bookUrl) ?: return emptyList()

            // 1. Ищем скрипт с инициализацией плеера: new BookPlayer(ID, [{...}])
            val scripts = doc.select("script")
            for (script in scripts) {
                val html = script.html()
                if (html.contains("BookPlayer")) {
                    Log.e("MYVOSK", "Найден скрипт BookPlayer, парсим...")
                    // Ищем все ссылки на mp3 внутри этого скрипта
                    val matches = Regex("""https?://[^\s"'<>]+\.mp3""").findAll(html)
                    for (match in matches) {
                        val url = match.value.replace("\\/", "/") // убираем экранирование слэшей
                        if (!links.contains(url)) {
                            links.add(url)
                        }
                    }
                    if (links.isNotEmpty()) break
                }
            }

            // 2. Если вдруг не нашли в BookPlayer, ищем вообще везде (на всякий случай)
            if (links.isEmpty()) {
                Log.e("MYVOSK", "BookPlayer не найден, ищем по всему HTML...")
                val bodyHtml = doc.body()?.html() ?: ""
                val matches = Regex("""https?://[^\s"'<>]+\.mp3""").findAll(bodyHtml)
                for (match in matches) {
                    val url = match.value.replace("\\/", "/")
                    if (!links.contains(url)) links.add(url)
                }
            }

            Log.e("MYVOSK", "✅ MP3-ссылок найдено: ${links.size}")
            for (l in links.take(3)) Log.e("MYVOSK", "  mp3: $l")
            
            if (links.isEmpty()) {
                val snippet = doc.body()?.html()?.take(500)?.replace("\n", " ")
                Log.e("MYVOSK", "DEBUG (нет mp3) body[0..500]: $snippet")
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.string()
            response.close()
            if (body == null) return null
            Jsoup.parse(body, url)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка загрузки HTML: ${e.message}", e)
            null
        }
    }
}
