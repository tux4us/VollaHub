package com.volla.hub

import org.jsoup.Jsoup

data class ContentItem(
    val title: String,
    val url: String,
    val excerpt: String = "",
    val date: String = "",
    val level: Int = 0,
)

class VollaParser {
    private val baseUrl = "https://volla.online"
    private val wikiBaseUrl = "http://wiki.volla.online"

    suspend fun parseOnlinePages(): List<ContentItem> {
        val pages = mutableListOf<ContentItem>()

        try {
            android.util.Log.d("VollaParser", "Lade Volla Online Seiten...")
            val doc = Jsoup.connect("$baseUrl/de/")
                .userAgent("Mozilla/5.0")
                .timeout(15000)
                .get()

            val menuLinks = doc.select("nav a, .menu a, header a")
            val seenUrls = mutableSetOf<String>()

            for (link in menuLinks) {
                val href = link.attr("abs:href")
                val title = link.text()

                if (href.startsWith("$baseUrl/de/") &&
                    !href.contains("/blog") &&
                    !href.contains("#") &&
                    title.length > 2 &&
                    href !in seenUrls
                ) {
                    seenUrls.add(href)
                    val level = calculateLevel(link)
                    pages.add(ContentItem(title, href, "", "", level))
                    android.util.Log.d("VollaParser", "Seite: $title")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Fehler: ${e.message}")
        }

        return pages.sortedBy { it.level }
    }

    suspend fun parseBlog(): List<ContentItem> {
        val posts = mutableListOf<ContentItem>()

        try {
            android.util.Log.d("VollaParser", "Lade Blog...")
            val doc = Jsoup.connect("$baseUrl/de/blog/")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(30000)
                .followRedirects(true)
                .get()

            android.util.Log.d("VollaParser", "Blog-Seite geladen: ${doc.title()}")

            val blogEntries = doc.select("div.blog-entry")
            android.util.Log.d("VollaParser", "Gefundene Blog-Einträge: ${blogEntries.size}")

            for (entry in blogEntries) {
                val title = entry.select("h1").firstOrNull()?.text() ?: continue
                val linkElem = entry.select("a[href]").firstOrNull()
                val url = linkElem?.attr("abs:href") ?: ""

                val finalUrl = if (url.isEmpty() || !url.contains("/de/blog/")) {
                    val slug = title.lowercase()
                        .replace(Regex("[^a-z0-9äöüß\\s-]"), "")
                        .replace(Regex("\\s+"), "-")
                        .replace("ä", "ae")
                        .replace("ö", "oe")
                        .replace("ü", "ue")
                        .replace("ß", "ss")
                    "$baseUrl/de/blog/$slug/"
                } else {
                    url
                }

                val date = entry.select(".blog-entry-date").firstOrNull()?.text() ?: ""
                val excerpt = entry.select(".blog-entry-body p").firstOrNull()?.text()?.take(150) ?: ""

                posts.add(ContentItem(title, finalUrl, excerpt, date))
                android.util.Log.d("VollaParser", "Blog-Beitrag: $title -> $finalUrl")
            }

            android.util.Log.d("VollaParser", "${posts.size} Blog-Beiträge gefunden")
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Fehler beim Blog-Laden: ${e.message}")
        }

        return posts
    }

    suspend fun parseWiki(pageName: String): List<ContentItem> {
        val articles = mutableListOf<ContentItem>()

        try {
            android.util.Log.d("VollaParser", "Lade Wiki: $pageName...")
            val doc = Jsoup.connect("$wikiBaseUrl/index.php?title=$pageName")
                .userAgent("Mozilla/5.0")
                .timeout(15000)
                .get()

            articles.add(
                ContentItem(
                    pageName.replace("%C4%8C", "Č").replace("%C3%A1", "á").replace("%C3%B1", "ñ"),
                    "$wikiBaseUrl/index.php?title=$pageName"
                )
            )

            val links = doc.select("a[href*='index.php?title=']")
            val seenTitles = mutableSetOf(pageName)

            for (link in links) {
                val href = link.attr("abs:href")
                val title = link.text()

                if (href.contains("index.php?title=") &&
                    !href.contains("Spezial:") &&
                    !href.contains("Diskussion:") &&
                    !href.contains("action=") &&
                    !href.contains("&") &&
                    title.isNotEmpty() &&
                    title !in seenTitles
                ) {
                    seenTitles.add(title)
                    articles.add(ContentItem(title, href))
                    android.util.Log.d("VollaParser", "Wiki-Artikel: $title")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Fehler: ${e.message}")
        }

        return articles
    }

    suspend fun searchWiki(query: String): List<ContentItem> {
        val results = mutableListOf<ContentItem>()
        try {
            val url = "$wikiBaseUrl/index.php?search=${java.net.URLEncoder.encode(query, "UTF-8")}&title=Spezial:Suche&fulltext=1"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            
            val searchResults = doc.select("li.mw-search-result")
            for (result in searchResults) {
                val link = result.select("a").first()
                val title = link?.text() ?: ""
                val href = link?.attr("abs:href") ?: ""
                
                // Filter: Ignoriere offensichtlich nicht-deutsche Seiten (z.B. englische Übersetzungen)
                if (href.contains("/en/") || title.contains("(en)", ignoreCase = true) || title.startsWith("En/")) {
                    continue
                }

                val excerpt = result.select(".searchresult").text()
                results.add(ContentItem(title, href, excerpt))
            }
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Wiki Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchForum(query: String): List<ContentItem> {
        val results = mutableListOf<ContentItem>()
        try {
            // f=94 ist der Hauptbereich für Deutsch im Volla Forum
            val url = "https://forum.volla.online/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}&fid[]=94"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            
            val topics = doc.select(".search.post") 
            for (topic in topics) {
                val links = topic.select("a[href]")
                val bestLink = links.find { it.hasClass("topictitle") } 
                    ?: links.find { it.parent()?.tagName() == "h3" }
                    ?: links.find { 
                        val href = it.attr("href")
                        !href.contains("u=") && !href.contains("viewprofile") && !href.contains("memberlist")
                    }

                val title = bestLink?.text() ?: ""
                val href = bestLink?.attr("abs:href") ?: ""
                val excerpt = topic.select(".postbody").text().take(200)
                
                if (href.isNotEmpty() && title.isNotEmpty()) {
                    results.add(ContentItem(title, href, excerpt))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Forum Search Error: ${e.message}")
        }
        return results
    }

    suspend fun searchOnline(query: String): List<ContentItem> {
        val results = mutableListOf<ContentItem>()
        try {
            val url = "$baseUrl/de/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
            
            val articles = doc.select("article, .post, .entry")
            for (article in articles) {
                val link = article.select("a").first()
                val title = article.select("h1, h2, h3").first()?.text() ?: link?.text() ?: ""
                val href = link?.attr("abs:href") ?: ""
                val excerpt = article.select("p").first()?.text()?.take(200) ?: ""
                
                if (href.isNotEmpty() && title.isNotEmpty()) {
                    results.add(ContentItem(title, href, excerpt))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VollaParser", "Online Search Error: ${e.message}")
        }
        return results
    }

    private fun calculateLevel(link: org.jsoup.nodes.Element): Int {
        var level = 0
        var parent = link.parent()

        while (parent != null) {
            if (parent.tagName() == "ul" || parent.tagName() == "ol") level++
            parent = parent.parent()
        }

        return level.coerceAtMost(3)
    }
}