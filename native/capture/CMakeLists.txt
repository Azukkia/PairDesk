# PairDesk screen capture (Windows, DXGI Desktop Duplication): the picture of
# a screen without the mouse cursor. See capture.cpp.
#
#   cmake -S native/capture -B out/capture -A x64
#   cmake --build out/capture --config Release
#
# Produces PairDeskCapture.dll, linked with the static C++ runtime (no
# Visual C++ redistributable needed on the user's computer).

cmake_minimum_required(VERSION 3.20)
project(pairdesk_capture LANGUAGES CXX)

if(NOT WIN32)
  message(FATAL_ERROR "PairDesk capture uses DXGI Desktop Duplication (Windows)")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(WIN32 UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)

add_library(pairdesk_capture SHARED capture.cpp)
set_target_properties(pairdesk_capture PROPERTIES OUTPUT_NAME PairDeskCapture)
target_compile_options(pairdesk_capture PRIVATE /W4)
target_link_libraries(pairdesk_capture PRIVATE d3d11 dxgi)
