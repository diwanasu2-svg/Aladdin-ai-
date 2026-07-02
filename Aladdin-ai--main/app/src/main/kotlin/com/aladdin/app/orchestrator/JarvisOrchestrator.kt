package com.aladdin.app.orchestrator

/**
 * Typealias shim — all callers should migrate to
 * com.aladdin.assistant.orchestrator.JarvisOrchestrator directly.
 *
 * The canonical orchestrator lives at com.aladdin.assistant.orchestrator.JarvisOrchestrator
 * and is annotated with @Singleton + @Inject constructor for Hilt DI.
 */
typealias JarvisOrchestrator = com.aladdin.assistant.orchestrator.JarvisOrchestrator
