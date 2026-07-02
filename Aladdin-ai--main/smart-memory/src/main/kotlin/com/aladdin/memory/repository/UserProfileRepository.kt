package com.aladdin.memory.repository

import com.aladdin.memory.db.dao.ContactDao
import com.aladdin.memory.db.dao.LocationDao
import com.aladdin.memory.db.dao.ProjectDao
import com.aladdin.memory.db.dao.ReminderDao
import com.aladdin.memory.db.dao.UserProfileDao
import com.aladdin.memory.db.entity.ContactEntity
import com.aladdin.memory.db.entity.LocationEntity
import com.aladdin.memory.db.entity.ProjectEntity
import com.aladdin.memory.db.entity.ProjectStatus
import com.aladdin.memory.db.entity.ReminderEntity
import com.aladdin.memory.db.entity.UserProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── User Profile Repository ──────────────────────────────────────────────────

@Singleton
class UserProfileRepository @Inject constructor(
    private val dao: UserProfileDao
) {
    fun observe(): Flow<UserProfileEntity?> = dao.observe()

    suspend fun get(): UserProfileEntity? = withContext(Dispatchers.IO) { dao.get() }

    suspend fun upsert(profile: UserProfileEntity) = withContext(Dispatchers.IO) {
        dao.insert(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateName(name: String) = withContext(Dispatchers.IO) {
        val current = dao.get() ?: UserProfileEntity()
        dao.insert(current.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addFavoriteTopic(topic: String) = withContext(Dispatchers.IO) {
        val current = dao.get() ?: UserProfileEntity()
        if (topic !in current.favoriteTopics) {
            dao.insert(current.copy(
                favoriteTopics = current.favoriteTopics + topic,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    suspend fun removeFavoriteTopic(topic: String) = withContext(Dispatchers.IO) {
        val current = dao.get() ?: return@withContext
        dao.insert(current.copy(
            favoriteTopics = current.favoriteTopics - topic,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun addFavoriteMusic(music: String) = withContext(Dispatchers.IO) {
        val current = dao.get() ?: UserProfileEntity()
        dao.insert(current.copy(
            favoriteMusic = (current.favoriteMusic + music).distinct(),
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun addFavoriteFood(food: String) = withContext(Dispatchers.IO) {
        val current = dao.get() ?: UserProfileEntity()
        dao.insert(current.copy(
            favoriteFoods = (current.favoriteFoods + food).distinct(),
            updatedAt = System.currentTimeMillis()
        ))
    }
}

// ─── Contact Repository ───────────────────────────────────────────────────────

@Singleton
class ContactRepository @Inject constructor(private val dao: ContactDao) {

    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun getAll(): List<ContactEntity> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun getById(id: Long): ContactEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun add(contact: ContactEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(contact)
    }

    suspend fun update(contact: ContactEntity) = withContext(Dispatchers.IO) {
        dao.update(contact)
    }

    suspend fun delete(contact: ContactEntity) = withContext(Dispatchers.IO) {
        dao.delete(contact)
    }

    suspend fun search(query: String): List<ContactEntity> = withContext(Dispatchers.IO) {
        dao.search(query)
    }

    /** Called whenever the user mentions or interacts with a contact. */
    suspend fun recordInteraction(id: Long) = withContext(Dispatchers.IO) {
        dao.recordInteraction(id)
    }

    /** Returns contacts sorted by relationship score (closest first). */
    suspend fun getClosestContacts(limit: Int = 10): List<ContactEntity> =
        withContext(Dispatchers.IO) { dao.getAll().take(limit) }
}

// ─── Project Repository ───────────────────────────────────────────────────────

@Singleton
class ProjectRepository @Inject constructor(private val dao: ProjectDao) {

    fun observeActive(): Flow<List<ProjectEntity>> = dao.observeByStatus(ProjectStatus.ACTIVE)

    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    suspend fun getById(id: Long): ProjectEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun add(project: ProjectEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(project)
    }

    suspend fun update(project: ProjectEntity) = withContext(Dispatchers.IO) {
        dao.update(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateProgress(id: Long, progress: Int) = withContext(Dispatchers.IO) {
        dao.updateProgress(id, progress.coerceIn(0, 100))
    }

    suspend fun complete(id: Long) = withContext(Dispatchers.IO) {
        dao.updateProgress(id, 100)
        dao.updateStatus(id, ProjectStatus.COMPLETED)
    }

    suspend fun archive(id: Long) = withContext(Dispatchers.IO) {
        dao.updateStatus(id, ProjectStatus.ARCHIVED)
    }

    suspend fun delete(project: ProjectEntity) = withContext(Dispatchers.IO) {
        dao.delete(project)
    }
}

// ─── Reminder Repository ──────────────────────────────────────────────────────

@Singleton
class ReminderRepository @Inject constructor(private val dao: ReminderDao) {

    fun observePending(): Flow<List<ReminderEntity>> = dao.observePending()

    suspend fun getById(id: Long): ReminderEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun add(reminder: ReminderEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(reminder)
    }

    suspend fun update(reminder: ReminderEntity) = withContext(Dispatchers.IO) {
        dao.update(reminder)
    }

    suspend fun markDone(id: Long) = withContext(Dispatchers.IO) { dao.markDone(id) }

    suspend fun delete(reminder: ReminderEntity) = withContext(Dispatchers.IO) {
        dao.delete(reminder)
    }

    suspend fun getOverdue(): List<ReminderEntity> =
        withContext(Dispatchers.IO) { dao.getOverdue() }

    suspend fun getDue(from: Long, to: Long): List<ReminderEntity> =
        withContext(Dispatchers.IO) { dao.getDue(from, to) }

    suspend fun cleanupCompleted(before: Long): Int =
        withContext(Dispatchers.IO) { dao.cleanupCompleted(before) }
}

// ─── Location Repository ──────────────────────────────────────────────────────

@Singleton
class LocationRepository @Inject constructor(private val dao: LocationDao) {

    fun observeAll(): Flow<List<LocationEntity>> = dao.observeAll()

    suspend fun getById(id: Long): LocationEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun add(location: LocationEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(location)
    }

    suspend fun update(location: LocationEntity) = withContext(Dispatchers.IO) {
        dao.update(location)
    }

    suspend fun delete(location: LocationEntity) = withContext(Dispatchers.IO) {
        dao.delete(location)
    }

    suspend fun search(query: String): List<LocationEntity> =
        withContext(Dispatchers.IO) { dao.search(query) }

    suspend fun recordVisit(id: Long) = withContext(Dispatchers.IO) {
        dao.recordVisit(id)
    }

    suspend fun getMostVisited(limit: Int = 10): List<LocationEntity> =
        withContext(Dispatchers.IO) { dao.search("", limit).sortedByDescending { it.visitCount } }
}
