package com.aladdin.assistant.data.repository

import androidx.room.*
import com.aladdin.assistant.data.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// ─── DAOs ─────────────────────────────────────────────────────────────────────
@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): Conversation?

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' AND isArchived = 0 ORDER BY updatedAt DESC")
    fun searchConversations(query: String): Flow<List<Conversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation)

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE isArchived = 1")
    suspend fun deleteArchived()

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: String): ChatMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage)

    @Delete
    suspend fun delete(message: ChatMessage)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

// ─── Database ─────────────────────────────────────────────────────────────────
@Database(
    entities = [Conversation::class, ChatMessage::class],
    version = 1,
    exportSchema = false
)
abstract class AladdinDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}

// ─── Repository ───────────────────────────────────────────────────────────────
@Singleton
class ChatRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun getAllConversations(): Flow<List<Conversation>> = conversationDao.getAllConversations()
    fun searchConversations(query: String): Flow<List<Conversation>> = conversationDao.searchConversations(query)
    fun getMessages(conversationId: String): Flow<List<ChatMessage>> = messageDao.getMessagesForConversation(conversationId)

    suspend fun createConversation(title: String = "New Conversation"): Conversation {
        val conv = Conversation(title = title)
        conversationDao.insert(conv)
        return conv
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        val conv = conversationDao.getConversationById(id) ?: return
        conversationDao.update(conv.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun sendMessage(conversationId: String, content: String, role: MessageRole): ChatMessage {
        val msg = ChatMessage(conversationId = conversationId, role = role, content = content)
        messageDao.insert(msg)
        // update conversation metadata
        val conv = conversationDao.getConversationById(conversationId) ?: return msg
        val summary = if (role == MessageRole.ASSISTANT && content.length > 60)
            content.take(80) + "…" else conv.summary
        conversationDao.update(conv.copy(
            updatedAt = System.currentTimeMillis(),
            messageCount = conv.messageCount + 1,
            summary = if (role == MessageRole.ASSISTANT) summary else conv.summary
        ))
        return msg
    }

    suspend fun deleteConversation(conversation: Conversation) {
        messageDao.deleteForConversation(conversation.id)
        conversationDao.delete(conversation)
    }

    suspend fun pinConversation(id: String, pinned: Boolean) = conversationDao.setPinned(id, pinned)
    suspend fun archiveConversation(id: String, archived: Boolean) = conversationDao.setArchived(id, archived)
}
