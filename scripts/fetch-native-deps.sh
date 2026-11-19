#!/usr/bin/env bash
# Baixa as dependências nativas que o LeviLauncher-Android usa como submódulos
# (ficam vazias no zip). Rode uma vez antes de compilar localmente.
set -euo pipefail
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp
if [ ! -f "$CPP/preloader/CMakeLists.txt" ] && [ ! -f "$CPP/preloader/xmake.lua" ]; then
  rm -rf "$CPP/preloader"
  git clone --depth 1 https://github.com/LiteLDev/preloader-android "$CPP/preloader"
fi
if [ ! -d "$CPP/libHttpClient/Build" ]; then
  rm -rf "$CPP/libHttpClient"
  git clone --depth 1 --recurse-submodules https://github.com/microsoft/libHttpClient "$CPP/libHttpClient"
fi
echo "Dependências nativas prontas."
