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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include "src/main/native/windows/stat.h"

#include <windows.h>

#include <cwchar>

#include "src/main/native/windows/file.h"

namespace bazel {
namespace windows {

namespace {

// The SDK declares FILE_STAT_BASIC_INFORMATION only behind NTDDI_WIN11_ZN, and
// Bazel pins neither an SDK nor a _WIN32_WINNT, so this declaration is local
// and carries a name of its own that cannot collide with the SDK's. file.cc
// declares REPARSE_DATA_BUFFER for the same reason.
//
// The layout is the 10.0.26100.0 SDK's. FileId128 is a FILE_ID_128 there: 16
// bytes, alignment one, so a byte array holds the same place.
struct FileStatBasicInformation {
  LARGE_INTEGER FileId;
  LARGE_INTEGER CreationTime;
  LARGE_INTEGER LastAccessTime;
  LARGE_INTEGER LastWriteTime;
  LARGE_INTEGER ChangeTime;
  LARGE_INTEGER AllocationSize;
  LARGE_INTEGER EndOfFile;
  DWORD FileAttributes;
  DWORD ReparseTag;
  DWORD NumberOfLinks;
  DWORD DeviceType;
  DWORD DeviceCharacteristics;
  DWORD Reserved;
  LARGE_INTEGER VolumeSerialNumber;
  BYTE FileId128[16];
};

// sizeof is all the call is told, so a layout that drifts from the platform's
// would be read as the wrong fields rather than refused. stat_test.cc compares
// the fields themselves against GetFileAttributesExW and GetChangeTime.
static_assert(sizeof(FileStatBasicInformation) == 104,
              "FileStatBasicInformation must match the SDK's layout");

// FileStatBasicByNameInfo, the fourth member of FILE_INFO_BY_NAME_CLASS, after
// FileStatByNameInfo, FileStatLxByNameInfo and FileCaseSensitiveByNameInfo.
constexpr int kFileStatBasicByNameInfo = 3;

// Resolved through GetProcAddress, not called directly. kernel32.lib carries
// the symbol, so a direct call links and becomes a static import; the loader on
// Windows 10 then fails to resolve it and the process does not start at all.
using GetFileInformationByNameFn = BOOL(WINAPI*)(PCWSTR, int, PVOID, ULONG);

// Null below Windows 11 build 26100.
GetFileInformationByNameFn ByNameOrNull() {
  // kernel32 is always loaded, so this needs no LoadLibrary and no matching
  // FreeLibrary. Function-local static initialization is thread-safe.
  static const GetFileInformationByNameFn fn =
      reinterpret_cast<GetFileInformationByNameFn>(::GetProcAddress(
          ::GetModuleHandleW(L"kernel32.dll"), "GetFileInformationByName"));
  return fn;
}

}  // namespace

int Stat(const WCHAR* path, bool follow_reparse_points, FileMetadata* result) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    return StatResult::kUnsupported;
  }

  // The caller reaches this through a "\\?\" prefix, which turns off the
  // normalization Win32 performs on the final component: a path naming "foo."
  // no longer answers with the file "foo". java.nio does not carry the prefix
  // for a short path, so it still would, and the same path would then describe
  // different files on either side of the fallback. Decline instead, and let
  // one implementation answer such a path on every version of Windows.
  const size_t length = ::wcslen(path);
  if (length > 0 && (path[length - 1] == L'.' || path[length - 1] == L' ')) {
    return StatResult::kUnsupported;
  }

  GetFileInformationByNameFn by_name = ByNameOrNull();
  if (by_name == nullptr) {
    return StatResult::kUnsupported;
  }

  FileStatBasicInformation info = {};
  if (!by_name(path, kFileStatBasicByNameInfo, &info, sizeof(info))) {
    // Only a missing path is answered here, because Bazel stats one constantly
    // and a fallback would add a syscall to that case. Every other error falls
    // back: this call is new and the errors a third-party filesystem returns
    // through it are undocumented. ERROR_NOT_READY and ERROR_BAD_NETPATH fall
    // back too - an unmounted volume is not a missing path, and reporting it as
    // one would tell Bazel that every file under it was deleted.
    DWORD err = ::GetLastError();
    if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
      return StatResult::kDoesNotExist;
    }
    // Not remembered: the SMB redirector answers ERROR_NOT_SUPPORTED for every
    // path on a share, so one such path would disable the call process-wide.
    return StatResult::kUnsupported;
  }

  // The call cannot follow a reparse point, so its answer is wrong only for one
  // the caller asked to resolve. An ordinary entry therefore uses it even under
  // FOLLOW, which is what Path#stat, isFile, isDirectory, getFileSize and
  // SyscallCache#statIfFound all default to.
  if (follow_reparse_points &&
      (info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
    return StatResult::kUnsupported;
  }

  result->attributes = info.FileAttributes;
  result->size = info.EndOfFile.QuadPart;
  result->last_write_time = info.LastWriteTime;
  result->change_time = info.ChangeTime;
  return StatResult::kSuccess;
}

}  // namespace windows
}  // namespace bazel
