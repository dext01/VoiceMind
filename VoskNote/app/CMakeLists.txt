cmake_minimum_required(VERSION 3.22.1)
project(voicemind)

# ── Whisper ──────────────────────────────────────────────────────────────────
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)

add_subdirectory(src/main/cpp/whisper.cpp whisper_build)

add_library(voicemind-jni SHARED src/main/cpp/whisper_jni.cpp)

target_include_directories(voicemind-jni PRIVATE
    src/main/cpp/whisper.cpp/include
    src/main/cpp/whisper.cpp
)

target_link_libraries(voicemind-jni whisper android log)

# ── llama.cpp (separate .so — own ggml namespace, no symbol conflict) ────────
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)

add_subdirectory(src/main/cpp/llama.cpp llama_build)

add_library(voicemind-llm SHARED src/main/cpp/llama_jni.cpp)

target_include_directories(voicemind-llm PRIVATE
    src/main/cpp/llama.cpp/include
    src/main/cpp/llama.cpp
)

target_link_libraries(voicemind-llm llama android log vulkan)
