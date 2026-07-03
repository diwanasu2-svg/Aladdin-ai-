package com.aladdin.memory.di

import android.content.Context
import com.aladdin.memory.db.MemoryDatabase
import com.aladdin.memory.db.dao.*
import com.aladdin.memory.engine.*
import com.aladdin.memory.phase3.*
import com.aladdin.memory.repository.MemoryRepository
import com.aladdin.memory.repository.UserProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    // ── Database ───────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideMemoryDatabase(@ApplicationContext ctx: Context): MemoryDatabase =
        MemoryDatabase.getInstance(ctx)

    // ── v1 DAOs ────────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideMemoryDao(db: MemoryDatabase): MemoryDao = db.memoryDao()

    @Provides @Singleton
    fun provideUserProfileDao(db: MemoryDatabase): UserProfileDao = db.userProfileDao()

    @Provides @Singleton
    fun provideContactDao(db: MemoryDatabase): ContactDao = db.contactDao()

    @Provides @Singleton
    fun provideProjectDao(db: MemoryDatabase): ProjectDao = db.projectDao()

    @Provides @Singleton
    fun provideReminderDao(db: MemoryDatabase): ReminderDao = db.reminderDao()

    @Provides @Singleton
    fun provideLocationDao(db: MemoryDatabase): LocationDao = db.locationDao()

    @Provides @Singleton
    fun provideSummaryDao(db: MemoryDatabase): SummaryDao = db.summaryDao()

    // ── Phase 3 DAOs ───────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideGoalDao(db: MemoryDatabase): GoalDao = db.goalDao()

    @Provides @Singleton
    fun provideHabitDao(db: MemoryDatabase): HabitDao = db.habitDao()

    @Provides @Singleton
    fun provideRelationshipDao(db: MemoryDatabase): RelationshipDao = db.relationshipDao()

    // ── Engines ────────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideEmbeddingEngine(@ApplicationContext ctx: Context): EmbeddingEngine =
        EmbeddingEngine(ctx)

    @Provides @Singleton
    fun provideVectorStore(embeddingEngine: EmbeddingEngine): VectorStore =
        VectorStore(embeddingEngine.embeddingDim)

    @Provides @Singleton
    fun provideBM25Engine(): BM25Engine = BM25Engine()

    @Provides @Singleton
    fun provideImportanceScorer(): ImportanceScorer = ImportanceScorer()

    @Provides @Singleton
    fun provideMemorySummarizer(): MemorySummarizer = MemorySummarizer()

    // NOTE: HybridSearchEngine's actual constructor only takes
    // (embeddingEngine, vectorStore, bm25Engine) - it does not take an
    // ImportanceScorer. Order/arity here must match that constructor exactly.
    @Provides @Singleton
    fun provideHybridSearchEngine(
        embeddingEngine: EmbeddingEngine,
        vectorStore: VectorStore,
        bm25Engine: BM25Engine
    ): HybridSearchEngine =
        HybridSearchEngine(embeddingEngine, vectorStore, bm25Engine)

    // ── Phase 3 Systems ────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideGoalManager(goalDao: GoalDao): GoalManager = GoalManager(goalDao)

    @Provides @Singleton
    fun provideHabitLearning(habitDao: HabitDao): HabitLearning = HabitLearning(habitDao)

    @Provides @Singleton
    fun provideRelationshipGraph(relationshipDao: RelationshipDao): RelationshipGraph =
        RelationshipGraph(relationshipDao)

    @Provides @Singleton
    fun provideUserProfileEvolution(userProfileDao: UserProfileDao): UserProfileEvolution =
        UserProfileEvolution(userProfileDao)

    @Provides @Singleton
    fun provideMemoryDecay(
        memoryDao: MemoryDao,
        hybridSearch: HybridSearchEngine
    ): MemoryDecay = MemoryDecay(memoryDao, hybridSearch)

    @Provides @Singleton
    fun provideMemoryAnalytics(
        memoryDao: MemoryDao,
        summaryDao: SummaryDao,
        vectorStore: VectorStore
    ): MemoryAnalytics = MemoryAnalytics(memoryDao, summaryDao, vectorStore)

    @Provides @Singleton
    fun provideProactiveRecall(
        memoryRepository: MemoryRepository,
        contactDao: ContactDao,
        reminderDao: ReminderDao,
        userProfileEvolution: UserProfileEvolution
    ): ProactiveRecall = ProactiveRecall(memoryRepository, contactDao, reminderDao, userProfileEvolution)

    @Provides @Singleton
    fun provideMemoryRouter(
        memoryRepository: MemoryRepository,
        contactDao: ContactDao,
        projectDao: ProjectDao,
        reminderDao: ReminderDao,
        locationDao: LocationDao,
        goalManager: GoalManager,
        habitLearning: HabitLearning,
        relationshipGraph: RelationshipGraph
    ): MemoryRouter = MemoryRouter(
        memoryRepository, contactDao, projectDao,
        reminderDao, locationDao, goalManager,
        habitLearning, relationshipGraph
    )
}
