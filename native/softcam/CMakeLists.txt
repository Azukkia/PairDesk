# PairDesk Camera: the virtual webcam of PairDesk (Windows, DirectShow).
#
#   cmake -S native/softcam -B build/softcam -A x64
#   cmake --build build/softcam --config Release
#
# Produces PairDeskCamera.dll, linked with the static C++ runtime (no
# Visual C++ redistributable needed on the user's computer).

cmake_minimum_required(VERSION 3.20)
project(pairdesk_camera LANGUAGES CXX)

if(NOT WIN32)
  message(FATAL_ERROR "PairDesk Camera is a Windows DirectShow filter")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(WIN32 UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)

file(GLOB BASECLASSES_SOURCES CONFIGURE_DEPENDS src/baseclasses/*.cpp)
add_library(baseclasses STATIC ${BASECLASSES_SOURCES})
target_include_directories(baseclasses PUBLIC src/baseclasses)
target_compile_definitions(baseclasses PRIVATE _LIB)

add_library(softcamcore STATIC
  src/softcamcore/DShowSoftcam.cpp
  src/softcamcore/FrameBuffer.cpp
  src/softcamcore/Misc.cpp
  src/softcamcore/SenderAPI.cpp
  src/softcamcore/Watchdog.cpp
)
target_include_directories(softcamcore PUBLIC src src/softcamcore)
target_link_libraries(softcamcore PUBLIC baseclasses)

add_library(pairdesk_camera SHARED src/softcam/softcam.cpp src/softcam/softcam.def)
set_target_properties(pairdesk_camera PROPERTIES OUTPUT_NAME PairDeskCamera)
target_link_libraries(pairdesk_camera PRIVATE
  softcamcore strmiids winmm kernel32 user32 gdi32 advapi32 ole32 oleaut32 uuid)
