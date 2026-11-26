package io.github.kotoriinsky.tsuzuri.core

import java.net.URI
import java.util.Locale

/**
 * 磁力链 / 电驴链 / 网盘提取码的零网络纯本地预解析。
 */
object MagnetEd2kMeta {

    private val MAGNET_DN = Regex("""[?&]dn=([^&]+)""", RegexOption.IGNORE_CASE)
    private val MAGNET_HASH = Regex("""xt=urn:btih:([0-9A-Fa-f]{40}|[A-Za-z2-7]{32})""", RegexOption.IGNORE_CASE)
    private val ED2K_FILE = Regex("""(?i)ed2k://\|file\|([^|\s]+)\|(\d+)\|[0-9a-f]{32}\|""")
    private val URL_PWD = Regex("""[?&](?:pwd|passcode|password|code)=([0-9A-Za-z]{4,6})(?:[&#]|$)""", RegexOption.IGNORE_CASE)
    private val TEXT_PWD = Regex("""(?:提取码|提取密码|访问码|密码)\s*[:：=]?\s*([0-9A-Za-z]{4,6})\b""")

    /** 磁力链展示核心：优先解码 `dn=` 文件名，缺失时退回大写 InfoHash。 */
    fun magnetCore(url: String): String? {
        val dn = MAGNET_DN.find(url)?.groupValues?.get(1)?.let(::decodeQueryValue)?.trim()
        if (!dn.isNullOrEmpty()) return truncateTitle(dn)
        return MAGNET_HASH.find(url)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    }

    /** 电驴链展示核心：解码文件名并附带格式化体积（如 `movie.mkv · 1.42 GB`）。 */
    fun ed2kCore(url: String): String? {
        val m = ED2K_FILE.find(url) ?: return null
        val name = decodeQueryValue(m.groupValues[1]).trim().takeIf { it.isNotEmpty() } ?: return null
        val size = m.groupValues[2].toLongOrNull()?.let(::formatBytes)
        val shortName = truncateTitle(name)
        return if (size != null) "$shortName · $size" else shortName
    }

    /** 从网盘分享文案或 URL 提取 4~6 位提取码/访问码。 */
    fun extractCloudPassword(text: String, url: String? = null): String? {
        if (!url.isNullOrBlank()) {
            URL_PWD.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return TEXT_PWD.find(text)?.groupValues?.get(1)
    }

    fun formatBytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val tb = gb * 1024.0
        return when {
            bytes >= tb -> String.format(Locale.ROOT, "%.2f TB", bytes / tb)
            bytes >= gb -> String.format(Locale.ROOT, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.ROOT, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.ROOT, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun decodeQueryValue(raw: String): String =
        PipixShare.percentDecode(raw.replace('+', ' '))

    private fun truncateTitle(text: String, max: Int = 36): String {
        if (text.length <= max) return text
        var cut = max
        if (Character.isHighSurrogate(text[cut - 1])) cut -= 1
        return text.take(cut).trimEnd() + "…"
    }
}

/**
 * 网易云音乐单曲公开只读接口（`music.163.com/api/song/detail/?ids=[<id>]`）解析。
 *
 * 实测（2026-11-26 Xiaomi 17 Pro 国内直连）：无需 Cookie，直接返回歌名、歌手列表与高清专辑图。
 * 参考端点事实：`Zhalslar/astrbot_plugin_parser`（MIT）。
 */
object NeteaseMusicShare {

    data class Fields(
        val title: String,
        val author: String?,
        val cover: String?,
    )

    private val SONG_ID = Regex("""(?:/song/|\bsong\?(?:[^#]*&)?id=)(\d{3,15})""")

    fun songId(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host != "music.163.com" && !host.endsWith(".music.163.com")) return null
        return SONG_ID.find(url)?.groupValues?.get(1)
    }

    fun apiUrl(id: String): String = "https://music.163.com/api/song/detail/?ids=%5B$id%5D"

    fun parse(json: String, expectedId: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val songs = root["songs"] as? List<*> ?: return null
        val song = songs.firstOrNull() as? Map<*, *> ?: return null
        val actualId = (song["id"] as? Double)?.toLong()?.toString()
        if (actualId != null && actualId != expectedId) return null
        val name = (song["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artists = (song["artists"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
        val album = song["album"] as? Map<*, *>
        val rawCover = ((album?.get("picUrl") ?: album?.get("blurPicUrl")) as? String)?.trim()
        val cover = rawCover?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.replaceFirst("http://", "https://")
            ?.let { if ('?' in it) it else "$it?param=320y320" }
        return Fields(name, artists, cover)
    }
}

/**
 * B 站直播间公开只读接口（`api.live.bilibili.com/room/v1/Room/get_info`）解析。
 *
 * 实测（2026-11-26 Xiaomi 17 Pro 国内直连）：无需 Cookie，返回直播标题、开播状态、分区与封面。
 */
object BiliLiveShare {

    data class Fields(
        val title: String,
        val subtitle: String?,
        val cover: String?,
    )

    private val ROOM_PATH = Regex("""^/(?:h5/|blanc/)?(\d{1,12})(?:/|$)""")

    fun roomId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "live.bilibili.com") return null
        return ROOM_PATH.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    fun apiUrl(roomId: String): String = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId"

    fun parse(json: String, expectedRoomId: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        if ((root["code"] as? Double)?.toInt() != 0) return null
        val data = root["data"] as? Map<*, *> ?: return null
        val roomId = (data["room_id"] as? Double)?.toLong()?.toString()
        val shortId = (data["short_id"] as? Double)?.toLong()?.toString()
        if (roomId != null && roomId != expectedRoomId && shortId != expectedRoomId) return null
        val title = (data["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val status = when ((data["live_status"] as? Double)?.toInt()) {
            1 -> "直播中"
            2 -> "轮播中"
            0 -> "未开播"
            else -> null
        }
        val area = (data["area_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (data["parent_area_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val subtitle = listOfNotNull(status, area).joinToString(" · ").ifEmpty { null }
        val rawCover = listOf(data["user_cover"], data["keyframe"])
            .firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf { s -> s.startsWith("http://") || s.startsWith("https://") } }
        val cover = rawCover?.replaceFirst("http://", "https://")
        return Fields(title, subtitle, cover)
    }
}

/**
 * 公开网盘分享页元数据解析（夸克 / 天翼云盘 / 123云盘 / 蓝奏云）。
 *
 * 端点参考公开文档及开源项目（均仅借公开只读接口与字段事实，实现自写）：
 * - `ikooc/Gopeed-Extensions`（MIT）
 * - `ByLsPro/JxPan`（Apache-2.0）
 * - `zaxtyson/LanZouCloud-API`（MIT）
 */
object CloudShareMeta {

    data class Fields(
        val title: String,
        val subtitle: String? = null,
    )

    private val QUARK_ID = Regex("""/s/([0-9A-Za-z_-]{6,32})""")
    private val CLOUD189_CODE = Regex("""/t/([0-9A-Za-z_-]{6,32})|[?&]code=([0-9A-Za-z_-]{6,32})""")
    private val PAN123_KEY = Regex("""/s/([0-9A-Za-z_-]{4,32})(?:\.html)?""")
    private val LANZOU_HOST = Regex("""(?:^|\.)(?:lanzou[a-z]?|ilanzou|lanzn|woozooo)\.com$""")
    private val LANZOU_TITLE_DIV = Regex("""<div[^>]+class\s*=\s*["'](?:b|md)["'][^>]*>\s*([^<]{1,120})""")
    private val LANZOU_TITLE_TAG = Regex("""<title>([^<]{1,140})</title>""", RegexOption.IGNORE_CASE)
    private val LANZOU_SIZE = Regex("""文件大小[:：]\s*([0-9.]+\s*[KMGTP]?B)""", RegexOption.IGNORE_CASE)

    fun quarkPwdId(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host != "pan.quark.cn" && !host.endsWith(".pan.quark.cn")) return null
        return QUARK_ID.find(url)?.groupValues?.get(1)
    }

    fun quarkTokenUrl(): String = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc"

    fun parseQuarkToken(json: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val code = (root["code"] as? Double)?.toInt() ?: (root["status"] as? Double)?.toInt()
        if (code != 0 && code != 200) return null
        val data = root["data"] as? Map<*, *> ?: return null
        val title = (data["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Fields(title = title)
    }

    fun cloud189Code(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host != "cloud.189.cn") return null
        val m = CLOUD189_CODE.find(url) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotEmpty() }
    }

    fun cloud189ApiUrl(code: String): String =
        "https://cloud.189.cn/api/open/share/getShareInfoByCodeV2.action?shareCode=$code"

    fun parseCloud189(json: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val resCode = (root["res_code"] as? Double)?.toInt()
        if (resCode != null && resCode != 0) return null
        val name = (root["fileName"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val size = (root["fileSize"] as? Double)?.toLong()?.let(MagnetEd2kMeta::formatBytes)
        return Fields(title = name, subtitle = size)
    }

    fun pan123ShareKey(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        val valid = listOf("123pan.com", "123pan.cn", "123684.com", "123865.com", "123912.com", "123592.com")
            .any { host == it || host.endsWith(".$it") }
        if (!valid) return null
        return PAN123_KEY.find(url)?.groupValues?.get(1)
    }

    fun pan123ApiUrl(shareKey: String, pwd: String?): String {
        val encPwd = pwd?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return "https://www.123865.com/b/api/share/get?limit=10&next=1&orderBy=file_name&orderDirection=asc" +
            "&shareKey=$shareKey&SharePwd=$encPwd&ParentFileId=0&Page=1"
    }

    fun parsePan123(json: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        if ((root["code"] as? Double)?.toInt() != 0) return null
        val data = root["data"] as? Map<*, *> ?: return null
        val list = (data["InfoList"] ?: data["info_list"]) as? List<*> ?: return null
        val first = list.firstOrNull() as? Map<*, *> ?: return null
        val name = ((first["FileName"] ?: first["filename"]) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val total = (data["Total"] as? Double)?.toInt() ?: list.size
        val size = ((first["Size"] ?: first["size"]) as? Double)?.toLong()?.let(MagnetEd2kMeta::formatBytes)
        val subtitle = when {
            total > 1 -> "共 $total 项"
            else -> size
        }
        return Fields(title = name, subtitle = subtitle)
    }

    fun isLanzouUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return LANZOU_HOST.containsMatchIn(host)
    }

    fun parseLanzouHtml(html: String): Fields? {
        if (html.isEmpty()) return null
        val rawTitle = LANZOU_TITLE_DIV.find(html)?.groupValues?.get(1)
            ?: LANZOU_TITLE_TAG.find(html)?.groupValues?.get(1)
            ?: return null
        val cleaned = rawTitle
            .replace(Regex("""\s*[-—|｜]\s*蓝奏云.*$"""), "")
            .trim()
        if (cleaned.isEmpty() || cleaned == "蓝奏云" || cleaned.contains("文件取消分享")) return null
        val size = LANZOU_SIZE.find(html)?.groupValues?.get(1)?.trim()
        return Fields(title = cleaned, subtitle = size)
    }
}

/**
 * Steam 商店公开接口（`store.steampowered.com/api/appdetails`）解析。
 */
object SteamStoreShare {

    data class Fields(
        val title: String,
        val subtitle: String?,
        val cover: String?,
    )

    private val APP_ID = Regex("""/app/(\d{2,10})(?:/|$)""")

    fun appId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "store.steampowered.com" && host != "steampowered.com") return null
        return APP_ID.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    fun apiUrl(appId: String): String =
        "https://store.steampowered.com/api/appdetails?appids=$appId&l=schinese&filters=basic,price_overview"

    fun parse(json: String, expectedAppId: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val entry = root[expectedAppId] as? Map<*, *> ?: return null
        if (entry["success"] != true) return null
        val data = entry["data"] as? Map<*, *> ?: return null
        val name = (data["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isFree = data["is_free"] == true
        val price = data["price_overview"] as? Map<*, *>
        val formatted = (price?.get("final_formatted") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val discount = (price?.get("discount_percent") as? Double)?.toInt() ?: 0
        val subtitle = when {
            formatted != null && discount > 0 -> "$formatted (-$discount%)"
            formatted != null -> formatted
            isFree -> "免费游玩"
            else -> null
        }
        val cover = (data["header_image"] as? String)?.trim()?.takeIf { it.startsWith("https://") }
        return Fields(name, subtitle, cover)
    }
}

/**
 * Bangumi 番组计划公开只读 API（`api.bgm.tv/v0/subjects/<id>`）解析。
 * 参考形态：`z-mio/ParseHub`（MIT）。
 */
object BangumiShare {

    data class Fields(
        val title: String,
        val subtitle: String?,
        val cover: String?,
    )

    private val SUBJECT_ID = Regex("""^/subject/(\d{1,10})(?:/|$)""")
    private val HOSTS = setOf("bgm.tv", "bangumi.tv", "chii.in")

    fun subjectId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        return SUBJECT_ID.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    fun apiUrl(id: String): String = "https://api.bgm.tv/v0/subjects/$id"

    fun parse(json: String, expectedId: String): Fields? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val actualId = (root["id"] as? Double)?.toLong()?.toString()
        if (actualId != null && actualId != expectedId) return null
        val nameCn = (root["name_cn"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val name = (root["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val title = nameCn ?: name ?: return null
        val rating = root["rating"] as? Map<*, *>
        val score = (rating?.get("score") as? Double)?.takeIf { it > 0.0 }
        val date = (root["date"] as? String)?.trim()?.takeIf { it.length >= 4 }?.take(4)
        val subtitle = listOfNotNull(
            score?.let { String.format(Locale.ROOT, "%.1f ★", it) },
            date,
        ).joinToString(" · ").ifEmpty { null }
        val images = root["images"] as? Map<*, *>
        val cover = listOf("common", "medium", "large")
            .firstNotNullOfOrNull { (images?.get(it) as? String)?.trim()?.takeIf { s -> s.startsWith("https://") } }
        return Fields(title, subtitle, cover)
    }
}
