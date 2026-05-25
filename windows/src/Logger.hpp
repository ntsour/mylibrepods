#pragma once

#include <windows.h>
#include <chrono>
#include <filesystem>
#include <format>
#include <fstream>
#include <iostream>
#include <mutex>
#include <string>
#include <string_view>

namespace librepods::log {

inline std::mutex& mutex() {
    static std::mutex m;
    return m;
}

inline std::filesystem::path logDir() {
    wchar_t buf[MAX_PATH] = {};
    DWORD len = GetEnvironmentVariableW(L"APPDATA", buf, MAX_PATH);
    auto dir = (len > 0 ? std::filesystem::path{buf} : std::filesystem::temp_directory_path()) / L"LibrePods";
    std::filesystem::create_directories(dir);
    return dir;
}

inline std::ofstream& logFile() {
    static std::ofstream f = []() {
        return std::ofstream{logDir() / "librepods.log", std::ios::app};
    }();
    return f;
}

inline std::ofstream& handoverLogFile() {
    static std::ofstream f = []() {
        return std::ofstream{logDir() / "handover.log", std::ios::app};
    }();
    return f;
}

inline std::string timestamp() {
    using namespace std::chrono;
    const auto now = system_clock::now();
    try {
        // current_zone() requires Windows 10 1903+ and the tzdata package.
        // Fall back to UTC on older systems or if the database is missing.
        const auto local = zoned_time{current_zone(), now}.get_local_time();
        return std::format("{:%H:%M:%S}", floor<milliseconds>(local));
    } catch (...) {
        return std::format("{:%H:%M:%S}", floor<milliseconds>(now));
    }
}

inline void writeLine(std::string_view level, std::string_view msg,
                      std::ofstream* extra = nullptr) {
    const auto ts = timestamp();
    const auto line = std::format("[{}] {} {}\n", ts, level, msg);
    std::cerr << line;
    if (auto& f = logFile(); f.is_open()) {
        f << line;
        f.flush();
    }
    if (extra && extra->is_open()) {
        *extra << line;
        extra->flush();
    }
}

template <class... Args>
void info(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    writeLine("INFO ", std::vformat(fmt, std::make_format_args(args...)));
}

template <class... Args>
void warn(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    writeLine("WARN ", std::vformat(fmt, std::make_format_args(args...)));
}

template <class... Args>
void error(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    writeLine("ERROR", std::vformat(fmt, std::make_format_args(args...)));
}

// Writes to both the main log and the dedicated handover.log.
// Use for every decision point in the handover state machine so the handover
// log gives a clean, noise-free timeline without endpoint enumeration spam.
template <class... Args>
void handover(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    writeLine("HNDOVR", std::vformat(fmt, std::make_format_args(args...)),
              &handoverLogFile());
}

template <class... Args>
void debug(std::string_view fmt, Args&&... args) {
#ifndef NDEBUG
    std::scoped_lock lk{mutex()};
    writeLine("DEBUG", std::vformat(fmt, std::make_format_args(args...)));
#else
    (void)fmt;
    ((void)args, ...);
#endif
}

}
