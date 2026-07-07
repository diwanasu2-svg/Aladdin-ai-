cmake_minimum_required(VERSION 3.22.1)
project(aladdin_piper_bridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ─────────────────────────────────────────────────────────────────────────────
# Piper neural TTS — pinned checkout, fetched at native-build time (kept out
# of git for the same reason as llama.cpp in :llama-cpp).
# ─────────────────────────────────────────────────────────────────────────────
FetchContent_Declare(
    piper
    GIT_REPOSITORY https://github.com/rhasspy/piper.git
    GIT_TAG        2023.11.14-2
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(piper)

# onnxruntime Android AAR provides prebuilt libonnxruntime.so per-ABI; the
# Gradle build extracts it (see :piper-tts build.gradle.kts comment) and
# CMake links against it directly here.
find_library(ONNXRUNTIME_LIB onnxruntime PATHS ${CMAKE_ANDROID_ARCH_ABI})

add_library(piper_bridge SHARED
    piper_bridge.cpp
)

target_include_directories(piper_bridge PRIVATE
    ${piper_SOURCE_DIR}/src/cpp
    ${piper_SOURCE_DIR}/lib/Public
)

target_link_libraries(piper_bridge
    PRIVATE
    piper
    ${ONNXRUNTIME_LIB}
    android
    log
)
