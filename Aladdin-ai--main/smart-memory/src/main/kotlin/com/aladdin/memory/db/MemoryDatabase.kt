package com.aladdin.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aladdin.memory.db.dao.*
import com.aladdin.memory.db.entity.*

/**
 * MemoryDatabase — Phase 3 (version 2)
 *
 * Version 2 adds: goals, habits, relationships tables for
 * cross-session goals, habit learning, and the relationship graph.
 * The v1→v2 migration is non-destructive — all existing data is preserved.
 */
@Database(
    entities = [
        MemoryEntity::class,
        UserProfileEntity::class,
        ContactEntity::class,
        ProjectEntity::class,
        ReminderEntity::class,
        LocationEntity::class,
        SummaryEntity::class,
        // Phase 3
        GoalEntity::class,
        HabitEntity::class,
        RelationshipEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {

    // ── v1 DAOs ────────────────────────────────────────────────────────────────
    abstract fun memoryDao(): MemoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun contactDao(): ContactDao
    abstract fun projectDao(): ProjectDao
    abstract fun reminderDao(): ReminderDao
    abstract fun locationDao(): LocationDao
    abstract fun summaryDao(): SummaryDao

    // ── Phase 3 DAOs ───────────────────────────────────────────────────────────
    abstract fun goalDao(): GoalDao
    abstract fun habitDao(): HabitDao
    abstract fun relationshipDao(): RelationshipDao

    companion object {
        private const val DB_NAME = "aladdin_memory.db"

        @Volatile private var INSTANCE: MemoryDatabase? = null

        /**
         * Migration v1 → v2: adds goals, habits, relationships tables.
         * Safe — existing data is untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Goals
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        steps TEXT NOT NULL DEFAULT '[]',
                        completed_steps TEXT NOT NULL DEFAULT '[]',
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        progress_percent INTEGER NOT NULL DEFAULT 0,
                        priority INTEGER NOT NULL DEFAULT 2,
                        due_at_ms INTEGER,
                        category TEXT NOT NULL DEFAULT 'general',
                        resume_context TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_status ON goals(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_priority ON goals(priority)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_created_at ON goals(created_at)")

                // Habits
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS habits (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        action_type TEXT NOT NULL,
                        description TEXT NOT NULL,
                        time_pattern TEXT NOT NULL,
                        minute_of_day INTEGER NOT NULL,
                        days_of_week TEXT NOT NULL DEFAULT '[]',
                        location TEXT,
                        occurrence_count INTEGER NOT NULL DEFAULT 1,
                        confidence REAL NOT NULL DEFAULT 0.3,
                        is_confirmed INTEGER NOT NULL DEFAULT 0,
                        last_observed_at INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_action_type ON habits(action_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_confidence ON habits(confidence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_is_confirmed ON habits(is_confirmed)")

                // Relationships
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS relationships (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        from_name TEXT NOT NULL,
                        from_type TEXT NOT NULL DEFAULT 'person',
                        relation_type TEXT NOT NULL,
                        to_name TEXT NOT NULL,
                        to_type TEXT NOT NULL DEFAULT 'person',
                        notes TEXT NOT NULL DEFAULT '',
                        strength REAL NOT NULL DEFAULT 0.5,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relationships_from_name ON relationships(from_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relationships_to_name ON relationships(to_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relationships_relation_type ON relationships(relation_type)")
            }
        }

        fun getInstance(context: Context): MemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): MemoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MemoryDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        setupFts(db)
                    }
                })
                .build()

        private fun setupFts(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts
                USING fts5(content, summary, tags, content='memories', content_rowid='id')
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_fts_insert AFTER INSERT ON memories BEGIN
                    INSERT INTO memories_fts(rowid, content, summary, tags)
                    VALUES (new.id, new.content, COALESCE(new.summary,''), COALESCE(new.tags,''));
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_fts_delete AFTER DELETE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, content, summary, tags)
                    VALUES ('delete', old.id, old.content, COALESCE(old.summary,''), COALESCE(old.tags,''));
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_fts_update AFTER UPDATE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, content, summary, tags)
                    VALUES ('delete', old.id, old.content, COALESCE(old.summary,''), COALESCE(old.tags,''));
                    INSERT INTO memories_fts(rowid, content, summary, tags)
                    VALUES (new.id, new.content, COALESCE(new.summary,''), COALESCE(new.tags,''));
                END
            """.trimIndent())
        }
    }
}
