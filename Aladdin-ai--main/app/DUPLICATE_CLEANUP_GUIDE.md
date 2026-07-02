# Duplicate Architecture Cleanup Guide

## Background
The Android app repository contains two overlapping package structures:
1. `com.aladdin.app.*` - **Canonical (Keep)**
2. `com.aladdin.assistant.*` - **Parallel (Migrate & Remove)**

## Current State

Both `com.aladdin.app` and `com.aladdin.assistant` contain separate components. They have overlapping features, and our goal is to consolidate everything into `com.aladdin.app` to improve maintainability and avoid duplicate code.

## Migration Plan

1. **Analyze Dependencies:** Look at `AladdinApplication.kt` (in assistant) and `AladdinApp.kt` (in app) to reconcile module initializations.
2. **Move Unique Components:** Files from `com.aladdin.assistant.*` that *do not* exist in `com.aladdin.app.*` must be migrated to `com.aladdin.app.*`.
3. **Consolidate Duplicates:** Compare files that exist in both structures (e.g. `AppModule.kt`, `MainActivity.kt`). Merge the unique features from the `assistant` version into the `app` version.
4. **Update Imports & Manifests:** After migrating, update all `import` statements and AndroidManifest.xml declarations to point to the new `com.aladdin.app.*` paths.
5. **Delete Old Packages:** Once the project compiles and runs successfully using only `com.aladdin.app.*`, the `com.aladdin.assistant.*` directories can be safely deleted.

## Next Steps for Developers

Review the `MigrationLog.md` document which outlines the specific files found in `com.aladdin.assistant` that require attention. Use this as a checklist for the migration.