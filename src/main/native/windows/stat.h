// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef BAZEL_SRC_MAIN_NATIVE_WINDOWS_STAT_H_
#define BAZEL_SRC_MAIN_NATIVE_WINDOWS_STAT_H_

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>

#include <cstdint>

namespace bazel {
namespace windows {

// Keep in sync with the STAT_* constants in
// j.c.g.devtools.build.lib.windows.WindowsFileOperations.
struct StatResult {
  enum {
    kSuccess = 0,
    kDoesNotExist = 1,
    // This system, this filesystem or this entry cannot be described by the one
    // call below. The caller resolves the path the way it did before.
    kUnsupported = 2,
  };
};

// The number of jlongs that nativeStat writes. Keep in sync with
// WindowsFileOperations#STAT_RESULT_LENGTH.
constexpr int kStatResultLength = 4;

// The metadata of one file. The times are Windows FILETIME ticks: 100ns units
// since 1601-01-01 UTC. file-jni.cc converts them for Java.
struct FileMetadata {
  DWORD attributes;
  int64_t size;
  LARGE_INTEGER last_write_time;
  LARGE_INTEGER change_time;
};

// Describes `path`, including its change time, in a single path resolution.
//
// `path` must be an absolute, normalized, Windows-style path naming a drive,
// optionally behind a "\\?\" prefix. Reparse points are not followed: the
// metadata describes the link and not its target.
//
// Only GetFileInformationByName, which arrived in Windows 11 build 26100, can
// answer this in one call: GetFileAttributesExW carries no change time, and a
// handle must be opened for one. This function is that call and nothing else.
// It returns kUnsupported wherever the call is absent or declines, and for a
// reparse point that `follow_reparse_points` asks to resolve; the caller then
// falls back to java.nio at no greater cost than today.
int Stat(const WCHAR* path, bool follow_reparse_points, FileMetadata* result);

}  // namespace windows
}  // namespace bazel

#endif  // BAZEL_SRC_MAIN_NATIVE_WINDOWS_STAT_H_
