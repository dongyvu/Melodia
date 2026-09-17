package com.lin0721.linmusic.core.player.data

import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.contentfilter.ContentFilter
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.core.network.apiFlow
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.player.domain.LyricResolver
import com.lin0721.linmusic.core.player.domain.LyricsResult
import com.lin0721.linmusic.core.player.domain.TtmlLyricParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "PlaybackRepositoryImpl"

class PlaybackRepositoryImpl(
    private val apiService: PlaybackApi,
    private val settingsPreferences: SettingsPreferences,
    private val userPreferences: UserPreferences,
    private val userPlaylistRepository: UserPlaylistRepository,
    private val contentFilter: ContentFilter,
    private val context: android.content.Context,
    private val json: Json,
    private val amllLyricsClient: AmllLyricsClient,
    private val lyricResolver: LyricResolver,
    private val lyricsCache: LyricsCache
) : PlaybackRepository {

    private fun isWifiConnected(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Wi-Fi 状态检测异常，按移动网络处理", e)
            false
        }
    }

    override fun getSongUrl(songId: Long): Flow<Result<String>> = apiFlow(
        request = {
            val quality = if (isWifiConnected()) {
                settingsPreferences.wifiQuality.first()
            } else {
                settingsPreferences.mobileQuality.first()
            }
            apiService.getSongUrl(body = SongUrlRequest(ids = "[$songId]", level = quality))
        },
        // 该歌曲可能需要开启 VIP 或版权受限：code=200 但 url 为空，也算失败
        isSuccess = { it.isSuccess && !it.data.firstOrNull()?.url.isNullOrBlank() },
        code = { it.code },
        transform = { it.data.first().url!! }
    )

    override fun getLyrics(songId: Long): Flow<Result<LyricsResult>> = flow {
        val resolved = resolveLyrics(songId)
        AppLogger.d(TAG, "歌词加载完成: songId=$songId source=${resolved.source} format=${resolved.format} lines=${resolved.lines.size} wordLines=${resolved.lines.count { it.words.isNotEmpty() }} translations=${resolved.lines.count { !it.translation.isNullOrBlank() }} romanizations=${resolved.lines.count { !it.romanization.isNullOrBlank() }} backgrounds=${resolved.lines.count { it.backgroundLine != null }} rightAligned=${resolved.lines.count { it.alignment == com.lin0721.linmusic.core.player.domain.LyricAlignment.END }}")
        emit(Result.success(resolved))
    }.flowOn(Dispatchers.IO)

    override suspend fun prefetchLyrics(songId: Long) {
        if (songId > 0) runCatching { resolveLyrics(songId) }
            .onFailure { AppLogger.d(TAG, "下一首歌词预加载失败: songId=$songId", it) }
    }

    private suspend fun resolveLyrics(songId: Long): LyricsResult {
        val preferAmll = settingsPreferences.preferAmllLyrics.first()
        lyricsCache.getParsed(songId, preferAmll)?.let { return it }
        if (preferAmll) {
            amllLyricsClient.cached(songId)?.let(TtmlLyricParser::parse)?.takeIf { it.isNotEmpty() }?.let { lines ->
                return lyricResolver.choose(lines, emptyList()).also {
                    lyricsCache.putParsed(songId, preferAmll, it)
                }
            }
        }
        val resolved = lyricResolver.resolve(
            amll = {
                if (preferAmll) amllLyricsClient.fetch(songId)?.let(TtmlLyricParser::parse).orEmpty()
                else emptyList()
            },
            netease = { getNeteaseLyrics(songId).first().getOrThrow() }
        )
        lyricsCache.putParsed(songId, preferAmll, resolved)
        return resolved
    }

    private fun getNeteaseLyrics(songId: Long): Flow<Result<List<LyricLine>>> = apiFlow(
        request = {
            apiService.getLyrics(
                LyricRequest(id = songId, tv = -1, lv = -1, rv = -1, kv = -1, ytv = -1, yrv = -1)
            )
        },
        isSuccess = { it.isSuccess },
        code = { it.code },
        transform = { response ->
            when {
                // 纯音乐返回带标识的单行；未收录返回空列表以隐藏卡片
                response.nolyric -> listOf(LyricLine(timeMs = 0, text = "纯音乐"))
                response.uncollected -> emptyList()
                else -> {
                    val yrcText = response.yrc?.lyric
                    val lrcText = response.lrc?.lyric
                    // 检测歌词文本中是否包含“纯音乐”或“Instrumental”标识
                    val isInstrumental = (!yrcText.isNullOrBlank() && (yrcText.contains("纯音乐") || yrcText.contains("Instrumental", ignoreCase = true))) ||
                            (!lrcText.isNullOrBlank() && (lrcText.contains("纯音乐") || lrcText.contains("Instrumental", ignoreCase = true)))
                    if (isInstrumental) {
                        listOf(LyricLine(timeMs = 0, text = "纯音乐"))
                    } else {
                        val lines = if (!yrcText.isNullOrBlank()) {
                            val parsedYrc = lyricResolver.choose(emptyList(), LyricParser.parseYrc(yrcText)).lines
                            if (parsedYrc.any { it.words.isNotEmpty() }) parsedYrc
                            else LyricParser.parseLrc(lrcText ?: "").ifEmpty { parsedYrc }
                        } else {
                            LyricParser.parseLrc(lrcText ?: "")
                        }
                        if (lines.isEmpty()) {
                            emptyList()
                        } else {
                            // 解析翻译歌词列表（优先使用 ytlrc，其次使用 tlyric）
                            val translationLines = LyricParser.parseLrc(response.ytlrc?.lyric ?: response.tlyric?.lyric ?: "")
                            lines.map { line ->
                                // 寻找在 150ms 内与原词时间戳最接近的翻译行
                                val matchedTranslation = translationLines
                                    .filter { kotlin.math.abs(it.timeMs - line.timeMs) < 150 }
                                    .minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                                    ?.text
                                line.copy(translation = matchedTranslation)
                            }
                        }
                    }
                }
            }
        }
    )

    override fun getSimilarSongs(songId: Long): Flow<Result<List<Track>>> = apiFlow(
        request = { apiService.getSimiSongs(SimiSongRequest(songid = songId.toString())) },
        isSuccess = { it.isSuccess },
        code = { it.code },
        transform = { contentFilter.filterBlockedArtists(it.songs) { song -> song.ar.map { a -> a.id } } }
    )

    // 未指定歌单上下文时（如首页心动模式入口），取当前歌单列表首位
    override fun getIntelligenceSongs(songId: Long, playlistId: Long): Flow<Result<List<Track>>> = apiFlow(
        request = {
            val finalPlaylistId = if (playlistId != 0L) {
                playlistId
            } else {
                val uid = userPreferences.userProfile.first()?.uid
                    ?: throw AppError.BizError(-1, null)
                userPlaylistRepository.getUserPlaylists(uid, limit = 1).first().getOrThrow()
                    .firstOrNull()?.id
                    ?: throw AppError.BizError(-1, null)
            }
            apiService.getIntelligenceSongs(
                IntelligenceSongsRequest(
                    songId = songId.toString(),
                    playlistId = finalPlaylistId.toString(),
                    startMusicId = songId.toString(),
                    count = 20
                )
            )
        },
        isSuccess = { response -> response.isSuccess && response.data.any { it.songInfo != null } },
        code = { it.code },
        transform = { response ->
            contentFilter.filterBlockedArtists(response.data.mapNotNull { it.songInfo }) { it.ar.map { a -> a.id } }
        }
    )

    // 歌曲开始播放时立即上报，进「最近播放」；sourceId 暂用 songId 本身代替（缺少真实来源容器映射）
    override fun reportStartPlay(songId: Long): Flow<Result<Unit>> = flow {
        val startplayLogs = json.encodeToString(
            listOf(StartPlayLogEntry(json = StartPlayLogJson(id = songId, content = "id=$songId")))
        )
        val startRes = apiService.reportWeblog(WeblogRequest(logs = startplayLogs))
        AppLogger.i(TAG, "打卡上报 startplay 返回: songId=$songId code=${startRes.code} data=${startRes.data}")

        if (startRes.isSuccess) {
            emit(Result.success(Unit))
        } else {
            emit(Result.failure(AppError.BizError(startRes.code, startRes.data)))
        }
    }.catch { e ->
        AppLogger.e(TAG, "打卡上报 startplay 请求异常", e)
        emit(Result.failure(mapToAppError(e)))
    }

    // 离开歌曲（切歌/退出播放器）时上报实际播放时长，涨「听歌排行」计数
    override fun reportPlayEnd(songId: Long, playedSeconds: Long): Flow<Result<Unit>> = flow {
        val playLogs = json.encodeToString(
            listOf(
                PlayLogEntry(
                    json = PlayLogJson(
                        id = songId,
                        sourceId = songId,
                        time = playedSeconds,
                        content = "id=$songId"
                    )
                )
            )
        )
        val playRes = apiService.reportWeblog(WeblogRequest(logs = playLogs))
        AppLogger.i(TAG, "打卡上报 play 返回: songId=$songId time=$playedSeconds code=${playRes.code} data=${playRes.data}")

        if (playRes.isSuccess) {
            emit(Result.success(Unit))
        } else {
            emit(Result.failure(AppError.BizError(playRes.code, playRes.data)))
        }
    }.catch { e ->
        AppLogger.e(TAG, "打卡上报 play 请求异常", e)
        emit(Result.failure(mapToAppError(e)))
    }
}
