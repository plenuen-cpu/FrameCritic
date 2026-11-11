#!/usr/bin/env bash
set -euo pipefail
revision=956591f4570aa6636895d90439892fef7c075b17
mkdir -p vendor/llama.cpp
if [ ! -d vendor/llama.cpp/.git ]; then
  git -C vendor/llama.cpp init
  git -C vendor/llama.cpp remote add origin https://github.com/ggml-org/llama.cpp.git
fi
git -C vendor/llama.cpp fetch --depth 1 origin "$revision"
git -C vendor/llama.cpp checkout --detach FETCH_HEAD
args=(-DCMAKE_BUILD_TYPE=Release -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_OPENSSL=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF)
if [ "${1:-android}" = android ]; then
  args+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384)
  destination=vendor/android-build
else
  destination=vendor/host-build
fi
cmake -S vendor/llama.cpp -B "$destination" "${args[@]}"
cmake --build "$destination" --target llama-server -j 3
if [ "${1:-android}" = android ]; then
  mkdir -p app/src/main/jniLibs/arm64-v8a
  cp "$destination/bin/llama-server" app/src/main/jniLibs/arm64-v8a/libframecritic-server.so
fi
