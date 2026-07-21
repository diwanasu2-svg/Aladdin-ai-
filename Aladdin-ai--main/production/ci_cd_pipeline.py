"""production/ci_cd_pipeline.py — Phase 15, Feature 9: CI/CD Pipeline.

Generates GitHub Actions workflows for automated testing, APK builds,
and staged releases with versioning and changelog generation.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Optional

log = logging.getLogger(__name__)


MAIN_CI_WORKFLOW = """\
# .github/workflows/ci.yml
# Aladdin AI — Main CI Pipeline (Phase 15)

name: CI

on:
  push:
    branches: [main, develop, 'feature/**']
  pull_request:
    branches: [main, develop]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

env:
  PYTHON_VERSION: '3.11'

jobs:
  # ─────────────────────────────────────────────────────────────────────
  # Lint & type check
  # ─────────────────────────────────────────────────────────────────────
  lint:
    name: Lint & Type Check
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ env.PYTHON_VERSION }}
          cache: pip

      - name: Install dev deps
        run: pip install ruff mypy types-requests

      - name: Ruff lint
        run: ruff check Aladdin-ai--main/

      - name: Type check
        run: mypy Aladdin-ai--main/ --ignore-missing-imports --no-strict-optional

  # ─────────────────────────────────────────────────────────────────────
  # Unit tests
  # ─────────────────────────────────────────────────────────────────────
  unit-tests:
    name: Unit Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ env.PYTHON_VERSION }}
          cache: pip

      - name: Install dependencies
        run: pip install -r Aladdin-ai--main/requirements_phase14_phase15.txt

      - name: Run unit tests
        run: |
          cd Aladdin-ai--main
          python -m pytest production/test_unit/ -v \\
            --cov=. \\
            --cov-report=xml \\
            --cov-report=term-missing \\
            --cov-fail-under=75 \\
            -p no:warnings

      - name: Upload coverage
        uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          fail_ci_if_error: false

  # ─────────────────────────────────────────────────────────────────────
  # Integration tests
  # ─────────────────────────────────────────────────────────────────────
  integration-tests:
    name: Integration Tests
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ env.PYTHON_VERSION }}
          cache: pip

      - name: Install dependencies
        run: pip install -r Aladdin-ai--main/requirements_phase14_phase15.txt

      - name: Run integration tests
        run: |
          cd Aladdin-ai--main
          python -m pytest production/test_integration/ -v --timeout=120

  # ─────────────────────────────────────────────────────────────────────
  # Performance tests
  # ─────────────────────────────────────────────────────────────────────
  performance:
    name: Performance Tests
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ env.PYTHON_VERSION }}
          cache: pip

      - name: Install dependencies
        run: pip install -r Aladdin-ai--main/requirements_phase14_phase15.txt

      - name: Run performance benchmarks
        run: |
          cd Aladdin-ai--main
          python -m pytest production/test_performance/ -v --benchmark-json=benchmark.json

      - name: Store benchmark results
        uses: actions/upload-artifact@v4
        with:
          name: benchmark-results
          path: Aladdin-ai--main/benchmark.json

  # ─────────────────────────────────────────────────────────────────────
  # Android build
  # ─────────────────────────────────────────────────────────────────────
  android-build:
    name: Android Debug Build
    runs-on: ubuntu-latest
    needs: [unit-tests, integration-tests]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - uses: android-actions/setup-android@v3

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle*') }}

      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk

  # ─────────────────────────────────────────────────────────────────────
  # Security scan
  # ─────────────────────────────────────────────────────────────────────
  security:
    name: Security Scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ env.PYTHON_VERSION }}
      - name: Install bandit
        run: pip install bandit
      - name: Run Bandit security scan
        run: bandit -r Aladdin-ai--main/ -f json -o bandit-report.json || true
      - name: Upload security report
        uses: actions/upload-artifact@v4
        with:
          name: security-report
          path: bandit-report.json
"""


RELEASE_WORKFLOW = """\
# .github/workflows/release.yml
# Aladdin AI — Automated Release Pipeline (Phase 15)

name: Release

on:
  push:
    tags:
      - 'v[0-9]+.[0-9]+.[0-9]+'

jobs:
  changelog:
    name: Generate Changelog
    runs-on: ubuntu-latest
    outputs:
      changelog: ${{ steps.changelog.outputs.changelog }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Generate changelog
        id: changelog
        uses: mikepenz/release-changelog-builder-action@v4
        with:
          token: ${{ secrets.GITHUB_TOKEN }}

  release-apk:
    name: Build & Publish Release APK
    runs-on: ubuntu-latest
    needs: changelog
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: android-actions/setup-android@v3

      - name: Decode signing keystore
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/release.keystore
          echo "storeFile=release.keystore" > keystore.properties
          echo "storePassword=${{ secrets.KEYSTORE_PASSWORD }}" >> keystore.properties
          echo "keyAlias=${{ secrets.KEY_ALIAS }}" >> keystore.properties
          echo "keyPassword=${{ secrets.KEY_PASSWORD }}" >> keystore.properties

      - name: Bump version from tag
        run: |
          VERSION="${GITHUB_REF#refs/tags/v}"
          echo "Building version: $VERSION"
          sed -i "s/versionName = .*/versionName = \\"$VERSION\\"/" app/build.gradle.kts

      - name: Build release AAB
        run: ./gradlew bundleRelease --no-daemon

      - name: Build release APK
        run: ./gradlew assembleRelease --no-daemon

      - name: Create GitHub Release
        uses: ncipollo/release-action@v1
        with:
          artifacts: "app/build/outputs/bundle/release/*.aab,app/build/outputs/apk/release/*.apk"
          body: ${{ needs.changelog.outputs.changelog }}
          token: ${{ secrets.GITHUB_TOKEN }}

  deploy-staging:
    name: Deploy to Staging Track
    runs-on: ubuntu-latest
    needs: release-apk
    environment: staging
    steps:
      - name: Upload to Play Store (internal)
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: com.aladdin.ai
          releaseFiles: app/build/outputs/bundle/release/*.aab
          track: internal
          status: completed

  deploy-production:
    name: Deploy to Production Track
    runs-on: ubuntu-latest
    needs: deploy-staging
    environment: production
    steps:
      - name: Promote to production
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: com.aladdin.ai
          releaseFiles: app/build/outputs/bundle/release/*.aab
          track: production
          userFraction: 0.1
          status: inProgress
"""


class CICDPipeline:
    """Writes and manages CI/CD workflow files for the Aladdin project."""

    def __init__(self, project_root: str = ".") -> None:
        self.root = Path(project_root)
        self.workflows_dir = self.root / ".github" / "workflows"
        self.workflows_dir.mkdir(parents=True, exist_ok=True)

    def write_main_ci(self) -> Path:
        path = self.workflows_dir / "ci.yml"
        path.write_text(MAIN_CI_WORKFLOW)
        log.info("[CI/CD] Main CI workflow written: %s", path)
        return path

    def write_release_workflow(self) -> Path:
        path = self.workflows_dir / "release.yml"
        path.write_text(RELEASE_WORKFLOW)
        log.info("[CI/CD] Release workflow written: %s", path)
        return path

    def write_all(self) -> None:
        self.write_main_ci()
        self.write_release_workflow()
        log.info("[CI/CD] All workflows written to %s", self.workflows_dir)

    def validate_secrets_list(self) -> list:
        """Return the list of GitHub Secrets that must be configured."""
        return [
            "KEYSTORE_BASE64",
            "KEYSTORE_PASSWORD",
            "KEY_ALIAS",
            "KEY_PASSWORD",
            "PLAY_SERVICE_ACCOUNT_JSON",
            "CODECOV_TOKEN",
            "FIREBASE_APP_ID",
        ]
