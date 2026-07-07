cmake_minimum_required(VERSION 3.22.1)
project(aladdin_llama_bridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ─────────────────────────────────────────────────────────────────────────────
# Pull a pinned llama.cpp release into the Gradle build cache the first time
# the native build runs (offline-friendly after that: Gradle caches the
# fetched source under app/.cxx / build, and CMake won't re-download unless
# the cache is cleared). This keeps the multi-hundred-MB llama.cpp checkout
# out of the git repo while still producing a self-contained, offline .so.
#
# Pin can be bumped by changing GIT_TAG below.
# ─────────────────────────────────────────────────────────────────────────────
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b4600
    GIT_SHALLOW    TRUE
)

# Build only the core ggml/llama static libs, skip examples/tools/tests/server
# to keep the compile fast and the resulting .so small.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ─── JNI bridge ───────────────────────────────────────────────────────────────
add_library(llama_bridge SHARED
    llama_bridge.cpp
)

target_include_directories(llama_bridge PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/common
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(llama_bridge
    PRIVATE
    llama
    common
    ggml
    android
    log
)
