package com.aladdin.tools.tools

import com.aladdin.tools.db.dao.NoteDao
import com.aladdin.tools.db.entity.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notes tool — Room SQLite storage.
 *
 * Commands:
 *   create  — create a new note
 *   read    — get note by ID
 *   update  — update title/content
 *   delete  — delete by ID
 *   search  — full-text search
 *   list    — list all notes
 *   pin     — pin/unpin a note
 *   voice   — mark as voice note (for voice memo integration)
 *
 * Params: command, title, content, note_id, query, is_voice, audio_path, tags, pin
 */
@Singleton
class NotesTool @Inject constructor(private val noteDao: NoteDao) : BaseTool {

    override val id = "notes"
    override val name = "Notes"
    override val description = "Create, read, update, delete, and search notes with voice note support"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "create") {
            "read"   -> readNote(params)
            "update" -> updateNote(params)
            "delete" -> deleteNote(params)
            "search" -> searchNotes(params)
            "list"   -> listNotes()
            "pin"    -> pinNote(params)
            "voice"  -> createVoiceNote(params)
            else     -> createNote(params)
        }
    }

    private suspend fun createNote(params: Map<String, String>): ToolResult {
        val title = params["title"] ?: return ToolResult.error(id, "Missing title")
        val content = params["content"] ?: ""
        val tags = params["tags"] ?: ""
        val entity = NoteEntity(title = title, content = content, tags = tags)
        val noteId = noteDao.insert(entity)
        return ToolResult.success(id, "📝 Note created: '$title' (ID: $noteId)")
    }

    private suspend fun createVoiceNote(params: Map<String, String>): ToolResult {
        val title = params["title"] ?: "Voice Note ${System.currentTimeMillis()}"
        val transcript = params["content"] ?: ""
        val audioPath = params["audio_path"] ?: ""
        val entity = NoteEntity(title = title, content = transcript, isVoiceNote = true, audioPath = audioPath)
        val noteId = noteDao.insert(entity)
        return ToolResult.success(id, "🎤 Voice note saved: '$title' (ID: $noteId)")
    }

    private suspend fun readNote(params: Map<String, String>): ToolResult {
        val noteId = params["note_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing note_id")
        val note = noteDao.getById(noteId) ?: return ToolResult.error(id, "Note $noteId not found")
        return ToolResult.success(id, buildString {
            appendLine("📝 ${note.title}")
            if (note.tags.isNotBlank()) appendLine("🏷 ${note.tags}")
            appendLine()
            append(note.content)
        })
    }

    private suspend fun updateNote(params: Map<String, String>): ToolResult {
        val noteId = params["note_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing note_id")
        val existing = noteDao.getById(noteId) ?: return ToolResult.error(id, "Note $noteId not found")
        val updated = existing.copy(
            title = params["title"] ?: existing.title,
            content = params["content"] ?: existing.content,
            tags = params["tags"] ?: existing.tags,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.update(updated)
        return ToolResult.success(id, "✅ Note '${ updated.title}' updated")
    }

    private suspend fun deleteNote(params: Map<String, String>): ToolResult {
        val noteId = params["note_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing note_id")
        noteDao.deleteById(noteId)
        return ToolResult.success(id, "🗑 Note $noteId deleted")
    }

    private suspend fun searchNotes(params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.error(id, "Missing query")
        val results = noteDao.search(query)
        if (results.isEmpty()) return ToolResult.success(id, "No notes matching '$query'")
        val sb = StringBuilder("🔍 Notes matching '$query':\n\n")
        results.forEach { n ->
            sb.appendLine("[${n.id}] ${n.title}")
            val preview = n.content.take(80).replace('\n', ' ')
            if (preview.isNotBlank()) sb.appendLine("   $preview…")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun listNotes(): ToolResult {
        val notes = noteDao.getAll()
        if (notes.isEmpty()) return ToolResult.success(id, "No notes yet.")
        val sb = StringBuilder("📋 All Notes (${notes.size}):\n\n")
        notes.forEach { n ->
            val pin = if (n.isPinned) "📌 " else ""
            val voice = if (n.isVoiceNote) "🎤 " else ""
            sb.appendLine("${pin}${voice}[${n.id}] ${n.title}")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun pinNote(params: Map<String, String>): ToolResult {
        val noteId = params["note_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing note_id")
        val pinned = params["pin"]?.equals("true", ignoreCase = true) ?: true
        noteDao.setPin(noteId, pinned)
        val action = if (pinned) "pinned 📌" else "unpinned"
        return ToolResult.success(id, "Note $noteId $action")
    }
}
