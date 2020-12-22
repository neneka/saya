package blue.starry.saya.services.comments

/**
 * コメントに関する統計情報を提供する共通インターフェイス
 */
interface CommentStatisticsProvider {
    /**
     * コメントに関する統計情報
     */
    interface Statistics {
        /**
         * コメント数
         */
        val comments: Int

        /**
         * コメント流速 (分)
         */
        val commentsPerMinute: Int
    }

    /**
     * コメントに関する統計情報を提供する
     */
    fun provide(): Statistics
}