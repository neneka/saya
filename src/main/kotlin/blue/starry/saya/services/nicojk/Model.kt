package blue.starry.saya.services.nicojk

import blue.starry.saya.services.nicolive.Chat
import kotlinx.serialization.Serializable

@Serializable
data class CommentLog(
    val packet: List<Packet>
) {
    @Serializable
    data class Packet(
        val chat: Chat
    )
}
