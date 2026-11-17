package com.mediafind.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

enum class MediaKind { IMAGE, VIDEO, GIF }

/** One picture or clip. For videos, [url] is the stream and [fallback] a plain mp4 (no sound). */
data class MediaItem(val url: String, val kind: MediaKind, val fallback: String? = null)

data class Post(
    val id: String,
    val title: String,
    val permalink: String,
    val thumb: String,
    val items: List<MediaItem>
) {
    val isVideo get() = items.firstOrNull()?.kind != MediaKind.IMAGE
    val isGallery get() = items.size > 1
}

data class Page(val posts: List<Post>, val after: String?)

class RedditBlockedException(message: String) : IOException(message)

/**
 * Talks to Reddit's search. Tries the public endpoint first; if a Reddit app key (client ID)
 * is set, uses the official API with an app-only token. Adult (NSFW) posts are always left out.
 */
class RedditClient(private val clientId: () -> String?) {

    companion object {
        const val USER_AGENT = "android:com.mediafind.app:v1.0 (personal media search)"
        private val deviceId = UUID.randomUUID().toString()
    }

    private var token: String? = null
    private var tokenExpires = 0L

    /** Builds the path for a query: plain words search everything; "r/name" browses or searches a subreddit. */
    fun path(query: String, sort: String, time: String, after: String?): String {
        val q = query.trim()
        val sub = Regex("^/?r/([A-Za-z0-9_]{2,21})\\s*(.*)$").find(q)
        val common = "limit=50&raw_json=1&include_over_18=off" + (after?.let { "&after=$it" } ?: "")
        return if (sub != null) {
            val name = sub.groupValues[1]
            val rest = sub.groupValues[2].trim()
            if (rest.isEmpty()) {
                val listing = when (sort) { "top" -> "top"; "new" -> "new"; else -> "hot" }
                "/r/$name/$listing.json?t=$time&$common"
            } else {
                "/r/$name/search.json?q=${enc(rest)}&restrict_sr=1&sort=$sort&t=$time&$common"
            }
        } else {
            "/search.json?q=${enc(q)}&sort=$sort&t=$time&type=link&$common"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(query: String, sort: String, time: String, after: String?): Page {
        val p = path(query, sort, time, after)
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() }
        val body = if (id != null) {
            get("https://oauth.reddit.com" + p.replace(".json", ""), "bearer " + accessToken(id))
        } else {
            get("https://www.reddit.com$p", null)
        }
        return parseListing(body)
    }

    private fun accessToken(id: String): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpires) return it }
        val conn = URL("https://www.reddit.com/api/v1/access_token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val auth = android.util.Base64.encodeToString("$id:".toByteArray(), android.util.Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "grant_type=${enc("https://oauth.reddit.com/grants/installed_client")}&device_id=$deviceId"
            conn.outputStream.use { it.write(form.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw RedditBlockedException("Reddit didn't accept the app key (error $code). Check the client ID in Settings.")
            val o = JSONObject(text)
            val t = o.optString("access_token")
            if (t.isEmpty()) throw RedditBlockedException("Reddit didn't accept the app key. Check the client ID in Settings.")
            token = t
            tokenExpires = now + (o.optLong("expires_in", 3600) - 120) * 1000
            return t
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, auth: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (auth != null) conn.setRequestProperty("Authorization", auth)
            val code = conn.responseCode
            if (code == 401) { token = null }
            if (code == 403 || code == 429 || code == 401) {
                throw RedditBlockedException(
                    if (auth == null) "Reddit blocked the request. Add a free Reddit app key in Settings (⚙) to fix this."
                    else "Reddit refused the request (error $code). Try again in a minute."
                )
            }
            if (code !in 200..299) throw IOException("Reddit error $code")
            val text = conn.inputStream.bufferedReader().readText()
            if (text.trimStart().startsWith("<")) {
                throw RedditBlockedException("Reddit sent a web page instead of results. Add a free Reddit app key in Settings (⚙).")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/** Turns a Reddit listing into posts that have pictures or videos (text and link-only posts are dropped). */
fun parseListing(text: String): Page {
    val root = JSONObject(text)
    val data = root.optJSONObject("data") ?: return Page(emptyList(), null)
    val children = data.optJSONArray("children") ?: JSONArray()
    val posts = ArrayList<Post>()
    for (i in 0 until children.length()) {
        val d = children.optJSONObject(i)?.optJSONObject("data") ?: continue
        if (d.optBoolean("over_18")) continue // never show adult posts
        val post = parsePost(d) ?: continue
        posts.add(post)
    }
    val after = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
    return Page(posts, after)
}

private fun httpOk(s: String?) = s != null && s.startsWith("http")

fun parsePost(d: JSONObject): Post? {
    val id = d.optString("id")
    val title = d.optString("title")
    val permalink = "https://www.reddit.com" + d.optString("permalink")
    val url = d.optString("url_overridden_by_dest", d.optString("url"))
    val preview = d.optJSONObject("preview")
    val previewImage = preview?.optJSONArray("images")?.optJSONObject(0)

    // Thumbnail: a ~320px preview if there is one
    var thumb = ""
    previewImage?.optJSONArray("resolutions")?.let { res ->
        var best = ""
        for (k in 0 until res.length()) {
            val r = res.optJSONObject(k) ?: continue
            best = r.optString("url")
            if (r.optInt("width") >= 300) break
        }
        thumb = best
    }
    if (thumb.isEmpty()) thumb = previewImage?.optJSONObject("source")?.optString("url") ?: ""
    if (thumb.isEmpty() && httpOk(d.optString("thumbnail"))) thumb = d.optString("thumbnail")

    val items = ArrayList<MediaItem>()

    // Reddit-hosted video
    val rv = d.optJSONObject("secure_media")?.optJSONObject("reddit_video")
        ?: d.optJSONObject("media")?.optJSONObject("reddit_video")
    if (rv != null) {
        val hls = rv.optString("hls_url")
        val mp4 = rv.optString("fallback_url")
        val gif = rv.optBoolean("is_gif")
        if (httpOk(hls) || httpOk(mp4)) {
            items.add(MediaItem(if (httpOk(hls)) hls else mp4, if (gif) MediaKind.GIF else MediaKind.VIDEO, mp4.takeIf { httpOk(it) }))
        }
    }

    // Gallery
    if (items.isEmpty() && d.optBoolean("is_gallery")) {
        val meta = d.optJSONObject("media_metadata")
        val order = d.optJSONObject("gallery_data")?.optJSONArray("items")
        if (meta != null && order != null) {
            for (k in 0 until order.length()) {
                val mid = order.optJSONObject(k)?.optString("media_id") ?: continue
                val m = meta.optJSONObject(mid) ?: continue
                if (m.optString("status") != "valid" && m.has("status")) continue
                val s = m.optJSONObject("s") ?: continue
                when {
                    httpOk(s.optString("mp4")) -> items.add(MediaItem(s.optString("mp4"), MediaKind.GIF))
                    httpOk(s.optString("u")) -> items.add(MediaItem(s.optString("u"), MediaKind.IMAGE))
                    httpOk(s.optString("gif")) -> items.add(MediaItem(s.optString("gif"), MediaKind.IMAGE))
                }
                if (thumb.isEmpty()) {
                    val p = m.optJSONArray("p")
                    val small = p?.optJSONObject(minOf(2, (p.length() - 1).coerceAtLeast(0)))?.optString("u")
                    thumb = if (httpOk(small)) small!! else s.optString("u")
                }
            }
        }
    }

    // Animated GIFs: Reddit provides an mp4 version, which plays far better
    if (items.isEmpty()) {
        val mp4Variant = previewImage?.optJSONObject("variants")?.optJSONObject("mp4")?.optJSONObject("source")?.optString("url")
        val previewVideo = preview?.optJSONObject("reddit_video_preview")?.optString("fallback_url")
        when {
            url.endsWith(".gifv") && url.contains("imgur.com") -> items.add(MediaItem(url.removeSuffix(".gifv") + ".mp4", MediaKind.GIF))
            httpOk(mp4Variant) && (url.endsWith(".gif") || d.optString("post_hint") == "image") -> items.add(MediaItem(mp4Variant!!, MediaKind.GIF))
            httpOk(previewVideo) -> items.add(MediaItem(previewVideo!!, MediaKind.GIF))
        }
    }

    // Plain image
    if (items.isEmpty()) {
        val lower = url.lowercase().substringBefore('?')
        val isImageUrl = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
        if (d.optString("post_hint") == "image" || isImageUrl) {
            val full = previewImage?.optJSONObject("source")?.optString("url")
            items.add(MediaItem(if (isImageUrl) url else (full ?: url), MediaKind.IMAGE))
        }
    }

    if (items.isEmpty()) return null
    if (thumb.isEmpty()) thumb = if (items[0].kind == MediaKind.IMAGE) items[0].url else return null
    return Post(id, title, permalink, thumb, items)
}
