package blue.starry.saya.services.comments

import blue.starry.saya.common.createSayaLogger
import blue.starry.saya.models.Comment
import blue.starry.saya.models.CommentSource
import blue.starry.saya.models.Definitions
import blue.starry.saya.models.TimeshiftCommentControl
import blue.starry.saya.services.createSaya5chClient
import blue.starry.saya.services.gochan.LiveGochanResProvider
import blue.starry.saya.services.gochan.TimeshiftGochanResProvider
import blue.starry.saya.services.nicolive.LiveNicoliveCommentProvider
import blue.starry.saya.services.nicolive.TimeshiftNicoliveCommentProvider
import blue.starry.saya.services.twitter.LiveTweetProvider
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import mu.KLogger
import mu.KotlinLogging
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.readText
import kotlin.time.Duration

object CommentChannelManager {
    val Channels: List<Definitions.Channel>
    val Boards: List<Definitions.Board>

    init {
        val definitionsPath = Paths.get("docs", "definitions.yml")
        val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
        val definitions = yaml.decodeFromString<Definitions>(definitionsPath.readText())

        Channels = definitions.channels
        Boards = definitions.boards
    }

    private val logger: KLogger
        get() = KotlinLogging.createSayaLogger("saya.CommentChannelManager")

    fun findByTarget(target: String): Definitions.Channel? {
        return when {
            // jk*** から探す
            target.startsWith("jk") -> {
                val jk = target.removePrefix("jk").toIntOrNull() ?: return null

                Channels.find { it.nicojkId == jk }
            }
            // {Channel Type}_{Service ID} から探す
            '_' in target -> {
                val (type, sid) = target.split('_', limit = 2)
                val serviceId = sid.toIntOrNull() ?: return null
                val channelType = Definitions.Channel.Type.values().find { it.name == type } ?: Definitions.Channel.Type.GR

                Channels.find { it.type == channelType && serviceId in it.serviceIds }
            }
            // {Service ID} から探す
            else -> {
                val serviceId = target.toIntOrNull() ?: return null

                Channels.find { serviceId in it.serviceIds }
            }
        }
    }

    private val liveProviders = mutableMapOf<Pair<Definitions.Channel, CommentSource>, Pair<LiveCommentProvider, Job?>>()
    private val liveProvidersLock = Mutex()

    /**
     * リアルタイムコメント配信を購読する
     *
     * 購読数が 0 になると自動でコメントの取得 [Job] がキャンセルされる
     *
     * @param channel 実況チャンネル [Definitions.Channel]
     * @param sources コメント配信元 [CommentSource] のリスト
     */
    fun subscribeLiveComments(
        channel: Definitions.Channel,
        sources: List<CommentSource>
    ): ReceiveChannel<Comment> {
        return GlobalScope.produce {
            val id = UUID.randomUUID()

            /**
             * 実況チャンネル [Definitions.Channel] と コメント配信元 [CommentSource] を紐付け, コメントの取得処理を開始する
             *
             * 外側の produce が終了したときに購読数が 0 なら自動で処理も停止させる
             *
             * @param source コメント配信元 [CommentSource]
             * @param block リアルタイムコメントを取得する [LiveCommentProvider]
             */
            suspend fun register(source: CommentSource, block: () -> LiveCommentProvider?) {
                if (source !in sources) {
                    return
                }

                val (provider, job) = liveProvidersLock.withLock {
                    val (oldProvider, oldJob) = liveProviders.getOrPut(channel to source) {
                        (block() ?: return) to null
                    }

                    // 取得 Job
                    // 前回の Job が走っていなければ再生成
                    if (oldJob == null || !oldJob.isActive) {
                        // 取得 Job はクライアント間で共有されるため GlobalScope を用いる
                        liveProviders[channel to source] = oldProvider to GlobalScope.launch {
                            while (isActive) {
                                try {
                                    ensureActive()
                                    oldProvider.start()
                                } catch (e: CancellationException) {
                                    break
                                } catch (t: Throwable) {
                                    logger.error(t) { "error in $oldProvider" }
                                }

                                delay(Duration.seconds(5))
                            }

                            logger.debug { "$oldProvider is canceled." }
                        }
                    }

                    liveProviders[channel to source]!!
                }

                // 配信 Job
                // クライアントが接続を閉じると終了する
                launch {
                    try {
                        provider.subscription.create(id)
                        logger.debug { "create id: $id [${channel.name}, $source]" }

                        provider.queue.collect {
                            send(it)
                        }
                    } finally {
                        provider.subscription.remove(id)
                        logger.debug { "remove id: $id [${channel.name}, $source]" }

                        if (provider.subscription.isEmpty()) {
                            logger.debug { "There is no subscriptions on [${channel.name}, $source]. Job: $job is stopping..." }
                            job!!.cancel()

                            provider.close()
                            liveProvidersLock.withLock {
                                liveProviders.remove(channel to source)
                            }
                        }

                        logger.debug { "$this is closing... ($id) [${channel.name}, $source]" }
                    }
                }
            }

            register(CommentSource.Nicolive) {
                // チャンネル名をタグ名として追加
                val tags = channel.nicoliveTags.plus(channel.name)

                LiveNicoliveCommentProvider(channel, tags)
            }

            register(CommentSource.Twitter) {
                val keywords = channel.twitterKeywords.ifEmpty { return@register null }

                LiveTweetProvider(channel, keywords)
            }

            register(CommentSource.Gochan) {
                val client = createSaya5chClient() ?: return@register null
                val ids = channel.boardIds.ifEmpty { return@register null }
                val boards = Boards.filter { it.id in ids }.ifEmpty { return@register null }

                LiveGochanResProvider(channel, client, boards)
            }
        }
    }

    /**
     * タイムシフトコメント配信を購読する
     *
     * @param channel 実況チャンネル [Definitions.Channel]
     * @param sources コメント配信元 [CommentSource] のリスト
     * @param controls コメント制御 [TimeshiftCommentControl] のフロー
     * @param startAt タイムシフト開始時刻 (エポック秒)
     * @param endAt タイムシフト終了時刻 (エポック秒)
     */
    fun subscribeTimeshiftComments(
        channel: Definitions.Channel,
        sources: List<CommentSource>,
        controls: Flow<TimeshiftCommentControl>,
        startAt: Long,
        endAt: Long
    ): ReceiveChannel<Comment> {
        return GlobalScope.produce {
            val providers = mutableListOf<TimeshiftCommentProvider>()

            /**
             * 実況チャンネル [Definitions.Channel] と コメント配信元 [CommentSource] を紐付け, コメントの取得処理を開始する
             *
             * 外側の produce が終了したときに自動で処理も停止させる
             *
             * @param source コメント配信元 [CommentSource]
             * @param block タイムシフトコメントを取得する [TimeshiftCommentProvider]
             */
            suspend fun register(source: CommentSource, block: () -> TimeshiftCommentProvider?) {
                if (source !in sources) {
                    return
                }

                val provider = block() ?: return
                providers += provider

                // 取得 Job
                launch {
                    while (isActive) {
                        try {
                            ensureActive()
                            provider.fetch()

                            logger.debug { "Fetch Job: $provider is done." }
                            break
                        } catch (e: CancellationException) {
                            logger.debug { "Fetch Job: $provider is canceled." }
                            break
                        } catch (t: Throwable) {
                            logger.error(t) { "error in $provider" }
                        }

                        delay(Duration.seconds(5))
                    }
                }

                // シーク Job
                launch {
                    while (isActive) {
                        try {
                            provider.start()
                        } catch (e: CancellationException) {
                            break
                        } catch (t: Throwable) {
                            logger.error(t) { "error in $provider" }
                        }
                    }

                    logger.debug { "Seek Job: $provider is canceled." }
                }

                // 配信 Job
                launch {
                    provider.use { provider ->
                        provider.queue.consumeEach {
                            send(it)
                            logger.trace { "Timeshift: $it" }
                        }
                    }
                }
            }

            register(CommentSource.Nicolive) {
                TimeshiftNicoliveCommentProvider(channel, startAt, endAt)
            }

            register(CommentSource.Gochan) {
                val client = createSaya5chClient() ?: return@register null
                val ids = channel.boardIds.ifEmpty { return@register null }
                val boards = Boards.filter { it.id in ids }.ifEmpty { return@register null }

                TimeshiftGochanResProvider(channel, startAt, endAt, client, boards)
            }

            // コントロール処理 Job
            launch {
                controls.collect { control ->
                    when (control.action) {
                        /**
                         * クライアントの準備ができ, コメントの配信を開始する命令
                         *   {"action": "Ready"}
                         *
                         * コメントの配信を再開する命令
                         *   {"action": "Resume"}
                         */
                        TimeshiftCommentControl.Action.Ready,
                        TimeshiftCommentControl.Action.Resume -> {
                            providers.map {
                                launch {
                                    it.resume()
                                }
                            }.joinAll()
                        }

                        /**
                         * コメントの配信を一時停止する命令
                         *   {"action": "Pause"}
                         */
                        TimeshiftCommentControl.Action.Pause -> {
                            providers.map {
                                launch {
                                    it.pause()
                                }
                            }.joinAll()
                        }

                        /**
                         * コメントの位置を同期する命令
                         *   {"action": "Sync", "seconds": 10.0}
                         */
                        TimeshiftCommentControl.Action.Sync -> {
                            providers.map {
                                launch {
                                    it.seek(control.seconds)
                                    it.resume()
                                }
                            }.joinAll()
                        }
                    }

                    logger.debug { "TimeshiftCommentControl: $control" }
                }
            }
        }
    }
}
