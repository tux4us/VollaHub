package com.volla.hub

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class ContentItem(
    val title: String,
    val url: String,
    val excerpt: String = "",
    val date: String = "",
    val level: Int = 0,
)

class VollaParser {
    private val baseUrl = "https://volla.online"
    private val wikiBaseUrl = "https://wiki.volla.online"

    suspend fun parseOnlinePages(lang: String = "de"): List<ContentItem> {
        val pages = mutableListOf<ContentItem>()

        try {
            Log.d("VollaParser", "Lade Volla Online Seiten ($lang)...")
            val doc = Jsoup.connect("$baseUrl/$lang/")
                .userAgent("Mozilla/5.0")
                .timeout(15000)
                .get()

            val menuLinks = doc.select("nav a, .menu a, header a")
            val seenUrls = mutableSetOf<String>()

            for (link in menuLinks) {
                val href = link.attr("abs:href")
                val title = link.text()

                if (href.startsWith("$baseUrl/$lang/") &&
                    !href.contains("/blog") &&
                    !href.contains("#") &&
                    title.length > 2 &&
                    href !in seenUrls
                ) {
                    seenUrls.add(href)
                    val level = calculateLevel(link)
                    pages.add(ContentItem(title, href, "", "", level))
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "Fehler: ${e.message}")
        }

        return pages.sortedBy { it.level }
    }

    suspend fun parseBlog(lang: String = "de"): List<ContentItem> {
        val posts = mutableListOf<ContentItem>()

        try {
            Log.d("VollaParser", "Lade Blog ($lang)...")
            val doc = Jsoup.connect("$baseUrl/$lang/blog/")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(30000)
                .followRedirects(true)
                .get()

            val blogEntries = doc.select("div.blog-entry")
            for (entry in blogEntries) {
                val title = entry.select("h1").firstOrNull()?.text() ?: continue
                val linkElem = entry.select("a[href]").firstOrNull()
                val url = linkElem?.attr("abs:href") ?: ""

                val finalUrl = if (url.isEmpty() || !url.contains("/$lang/blog/")) {
                    val slug = title.lowercase()
                        .replace(Regex("[^a-z0-9äöüß\\s-]"), "")
                        .replace(Regex("\\s+"), "-")
                        .replace("ä", "ae")
                        .replace("ö", "oe")
                        .replace("ü", "ue")
                        .replace("ß", "ss")
                    "$baseUrl/$lang/blog/$slug/"
                } else {
                    url
                }

                val date = entry.select(".blog-entry-date").firstOrNull()?.text() ?: ""
                val excerpt = entry.select(".blog-entry-body p").firstOrNull()?.text()?.take(150) ?: ""

                posts.add(ContentItem(title, finalUrl, excerpt, date))
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "Fehler beim Blog-Laden: ${e.message}")
        }

        return posts
    }

    private fun extractKeywords(query: String): List<String> {
        val stopWords = setOf(
            "wie", "kann", "ich", "einen", "machen", "der", "die", "das", "ein", "eine", "und", "ist", "sind", "mit", "für", "von", "auf", "zu", "mir", "mich", "dir", "dich", "habe", "hast", "hat", "hatte", "wird", "werden",
            "how", "can", "i", "a", "an", "the", "and", "is", "are", "with", "for", "from", "on", "to", "do", "does", "did", "my", "me", "you", "your", "have", "has", "had", "will", "be",
            "bitte", "gerne", "hallo", "servus", "moin", "frage", "antwort", "suche", "hilfe", "info", "information", "man", "jemand", "könnte", "würde",
            "richte", "einrichten", "geht", "wurde", "worden", "habe", "hat", "hast", "hatte", "bin", "bist", "war", "waren", "gemacht", "getan"
        )
        return query.lowercase()
            .replace(Regex("[^a-z0-9äöüß\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
    }

    private fun calculateScore(title: String, text: String, keywords: List<String>): Int {
        var score = 0
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        
        for (kw in keywords) {
            // Exakter Treffer als ganzes Wort im Titel (höchste Gewichtung)
            if (lowerTitle.contains(Regex("\\b$kw\\b"))) {
                score += 50
            } else if (lowerTitle.contains(kw)) {
                score += 20
            }
            
            // Exakter Treffer als ganzes Wort im Text (mittlere Gewichtung)
            if (lowerText.contains(Regex("\\b$kw\\b"))) {
                score += 10
            } else if (lowerText.contains(kw)) {
                score += 2
            }
        }
        return score
    }

    suspend fun searchWiki(query: String, lang: String = "de"): List<Pair<ContentItem, Int>> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()
        val searchQuery = keywords.joinToString(" ")
        
        val results = mutableListOf<Pair<ContentItem, Int>>()
        val url = "$wikiBaseUrl/index.php?search=${URLEncoder.encode(searchQuery, "UTF-8")}&title=Spezial:Suche&fulltext=1"
        val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
        
        val searchResults = doc.select(".mw-search-result")
        for (result in searchResults) {
            val link = result.select("a").first()
            val title = link?.text() ?: ""
            val href = link?.attr("abs:href") ?: ""
            
            if (href.isEmpty() || title.isEmpty() || title.startsWith("Spezial:") || title.startsWith("Datei:")) continue
            
            // Sprachfilter für Wiki
            if (lang == "de" && (href.contains("/en/") || title.contains("(en)", ignoreCase = true) || title.startsWith("En/"))) continue

            val excerpt = result.select(".searchresult").text()
            val score = calculateScore(title, excerpt, keywords)
            
            if (score > 0) results.add(ContentItem(title, href, excerpt) to score)
        }
        return results
    }

    suspend fun searchForum(query: String, lang: String = "de"): List<Pair<ContentItem, Int>> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()
        val searchQuery = keywords.joinToString(" ")

        val results = mutableListOf<Pair<ContentItem, Int>>()
        try {
            val fid = when(lang) {
                "en" -> 26
                "es" -> 119
                else -> 94
            }
            val url = "https://forum.volla.online/search.php?keywords=${URLEncoder.encode(searchQuery, "UTF-8")}&fid[]=$fid"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            
            val topics = doc.select(".search.post") 
            for (topic in topics) {
                val links = topic.select("a[href]")
                val bestLink = links.find { it.hasClass("topictitle") } ?: links.firstOrNull()
                val title = bestLink?.text() ?: ""
                val href = bestLink?.attr("abs:href") ?: ""
                val excerpt = topic.select(".postbody").text().take(200)
                
                if (href.isNotEmpty() && title.isNotEmpty()) {
                    val score = calculateScore(title, excerpt, keywords)
                    if (score > 0) results.add(ContentItem(title, href, excerpt) to score)
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "Forum Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchOnline(query: String, lang: String = "de"): List<Pair<ContentItem, Int>> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()
        val searchQuery = keywords.joinToString(" ")

        val results = mutableListOf<Pair<ContentItem, Int>>()
        try {
            val url = "$baseUrl/$lang/?s=${URLEncoder.encode(searchQuery, "UTF-8")}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            
            val articles = doc.select("article, .post, .entry")
            for (article in articles) {
                val link = article.select("a").first()
                val title = article.select("h1, h2, h3").first()?.text() ?: link?.text() ?: ""
                val href = link?.attr("abs:href") ?: ""
                val excerpt = article.select("p").first()?.text()?.take(200) ?: ""
                
                if (href.isNotEmpty() && title.isNotEmpty()) {
                    val score = calculateScore(title, excerpt, keywords)
                    if (score > 0) results.add(ContentItem(title, href, excerpt) to score)
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "Online Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchFaqs(query: String, lang: String = "de"): List<Pair<ContentItem, Int>> {
        val results = mutableListOf<Pair<ContentItem, Int>>()
        try {
            val url = "$baseUrl/$lang/faqs/"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            val faqItems = doc.select("li[id^=FAQ-item]")
            
            val keywords = extractKeywords(query)
            if (keywords.isEmpty()) return emptyList()
            
            for (item in faqItems) {
                val question = item.select(".faq-question-text").text()
                val answer = item.select(".faq-answer").text()
                val score = calculateScore(question, answer, keywords)
                
                if (score > 0) {
                    val itemId = item.attr("id")
                    val link = "$baseUrl/$lang/faqs/#$itemId" 
                    results.add(ContentItem("FAQ: $question", link, answer.take(200)) to score)
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "FAQ Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchDownloads(query: String, lang: String = "de"): List<Pair<ContentItem, Int>> {
        val results = mutableListOf<Pair<ContentItem, Int>>()
        try {
            val url = "$baseUrl/$lang/faqs/downloads/"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            val downloadItems = doc.select(".filesharing-item")
            
            val keywords = extractKeywords(query)
            if (keywords.isEmpty()) return emptyList()
            
            for (item in downloadItems) {
                val linkElem = item.select(".filesharing-item-title a")
                val title = linkElem.text()
                val href = linkElem.attr("abs:href")
                val description = item.select(".filesharing-item-description").text()
                val score = calculateScore(title, description, keywords)
                
                if (score > 0) {
                    results.add(ContentItem("Download: $title", href, description.take(200)) to score)
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "Download Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchUbports(query: String): List<Pair<ContentItem, Int>> {
        val results = mutableListOf<Pair<ContentItem, Int>>()
        try {
            val url = "https://docs.ubports.com/en/latest/"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            val links = doc.select("a[href]")
            
            val keywords = extractKeywords(query)
            if (keywords.isEmpty()) return emptyList()
            
            val seenUrls = mutableSetOf<String>()

            for (link in links) {
                val title = link.text()
                val href = link.attr("abs:href")
                
                if (href.startsWith("https://docs.ubports.com/") && href !in seenUrls) {
                    val score = calculateScore(title, "", keywords)
                    
                    if (score > 0) {
                        seenUrls.add(href)
                        results.add(ContentItem("UBports: $title", href, "Dokumentation für Ubuntu Touch") to score)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VollaParser", "UBports Search Error: ${e.message}")
        }
        return results
    }

    private fun calculateLevel(link: Element): Int {
        var level = 0
        var parent = link.parent()
        while (parent != null) {
            if (parent.tagName() == "ul" || parent.tagName() == "ol") level++
            parent = parent.parent()
        }
        return level.coerceAtMost(3)
    }
}
