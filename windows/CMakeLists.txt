cmake_minimum_required(VERSION 3.20)
project(LibrePods LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT WIN32)
    message(FATAL_ERROR "LibrePods Windows target requires Windows.")
endif()

if(NOT MSVC)
    message(FATAL_ERROR "LibrePods Windows target requires MSVC (cppwinrt).")
endif()

add_compile_definitions(
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

add_compile_options(
    /await:strict
    /permissive-
    /Zc:__cplusplus
    /EHsc
    /W4
    /wd4100
)

set(SRC
    src/main.cpp
    src/BluetoothRfcommClient.cpp
    src/MediaPlaybackWatcher.cpp
    src/AirPodsConnector.cpp
    src/HandoverController.cpp
    src/TrayIcon.cpp
    src/SettingsStore.cpp
    src/TeamsCallWatcher.cpp
    src/PeerRegistry.cpp
)

set(HDR
    common/crossdevice_protocol.hpp
    src/BluetoothRfcommClient.hpp
    src/MediaPlaybackWatcher.hpp
    src/AirPodsConnector.hpp
    src/HandoverController.hpp
    src/TrayIcon.hpp
    src/SettingsStore.hpp
    src/TeamsCallWatcher.hpp
    src/PeerRegistry.hpp
    src/Logger.hpp
)

add_executable(LibrePods WIN32 ${SRC} ${HDR})

target_include_directories(LibrePods PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/common
)

target_link_libraries(LibrePods PRIVATE
    windowsapp.lib
    Shell32.lib
    User32.lib
    Gdi32.lib
    Ole32.lib
    Shlwapi.lib
    Bthprops.lib
)
