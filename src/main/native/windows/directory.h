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

#ifndef BAZEL_SRC_MAIN_NATIVE_WINDOWS_DIRECTORY_H_
#define BAZEL_SRC_MAIN_NATIVE_WINDOWS_DIRECTORY_H_

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>

#include <string>
#include <vector>

namespace bazel {
namespace windows {

// Keep in sync with the READ_DIRECTORY_* constants in
// j.c.g.devtools.build.lib.windows.WindowsFileOperations.
// kDoesNotExist and kNotADirectory are the two failures that
// JavaIoFileSystem#getDirectoryEntries reports, and they are decided the same
// way. kError means the enumeration started and then failed, so the listing
// would be truncated - a case the old code could not report at all.
struct ReadDirectoryResult {
  enum {
    kSuccess = 0,
    kError = 1,
    kDoesNotExist = 2,
    kNotADirectory = 3,
  };
};

// One directory entry: its name relative to the directory, and the Win32 file
// attribute bits the enumeration reported for it.
//
// The attributes are deliberately passed through raw rather than being
// interpreted here, so that the classification rules stay in one place (Java),
// next to the FileStatus semantics they have to agree with.
struct DirectoryEntry {
  std::wstring name;
  DWORD attributes;
};

// Lists the entries of the directory at `path`, along with their attributes.
//
// `path` should be an absolute, normalized, Windows-style path, with a "\\?\"
// prefix if it's longer than MAX_PATH.
//
// The "." and ".." entries are not reported. Symbolic links and junctions among
// the entries are not followed, and neither is `path` itself resolved any
// differently than by any other Win32 call - the reparse points encountered
// while resolving `path` are followed, the ones found *in* it are not.
//
// This performs a single directory enumeration. In particular it does not
// resolve any child path, which is the whole point: resolving a path on Windows
// walks it through the filesystem driver stack, filter drivers included, and
// doing that once per entry is what makes the generic FileSystem#readdir
// expensive here.
int ReadDirectory(const WCHAR* path, std::vector<DirectoryEntry>* result,
                  std::wstring* error);

}  // namespace windows
}  // namespace bazel

#endif  // BAZEL_SRC_MAIN_NATIVE_WINDOWS_DIRECTORY_H_
