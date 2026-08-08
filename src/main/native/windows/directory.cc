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

#include "src/main/native/windows/directory.h"

#include <windows.h>

#include <string>
#include <vector>

#include "src/main/native/windows/file.h"
#include "src/main/native/windows/util.h"

namespace bazel {
namespace windows {

using std::wstring;

namespace {

// Closes a FindFirstFileEx handle however the scope is left.
//
// The loop below allocates for every entry, so it can throw std::bad_alloc -
// Bazel compiles this with /EHsc. Without an owner the handle would leak and
// hold the directory against rename and delete for the life of the server.
// bazel::windows::AutoHandle cannot be used: it calls CloseHandle, and a find
// handle needs FindClose.
class AutoFindHandle {
 public:
  explicit AutoFindHandle(HANDLE handle) : handle_(handle) {}
  ~AutoFindHandle() {
    if (handle_ != INVALID_HANDLE_VALUE) {
      ::FindClose(handle_);
    }
  }
  AutoFindHandle(const AutoFindHandle&) = delete;
  AutoFindHandle& operator=(const AutoFindHandle&) = delete;

 private:
  HANDLE handle_;
};

bool IsDotOrDotDot(const WCHAR* name) {
  return name[0] == L'.' &&
         (name[1] == L'\0' || (name[1] == L'.' && name[2] == L'\0'));
}

// True when `path` leads somewhere once every reparse point on it is followed.
//
// An open is the only Win32 call that follows the final reparse point.
// GetFileAttributesW and GetFileAttributesExW both describe the link itself,
// and neither offers an option to do otherwise. FILE_FLAG_OPEN_REPARSE_POINT is
// absent here for that reason, and FILE_FLAG_BACKUP_SEMANTICS is present so
// that a directory can be opened at all. No access is asked for, so this needs
// no permission on the file.
//
// A failed open is a false, whatever the reason. That is not an approximation:
// java.io.WinNTFileSystem, which is what File#exists calls, reaches a reparse
// point through getFinalAttributes, and that opens the path and reports every
// failure as "does not exist". Reading the error code instead would answer "it
// is there" for a link into a directory the caller may not open, for a symbolic
// link cycle, and for a junction whose target is a file - and File#exists calls
// each of those absent.
//
// Two details of getFinalAttributes are copied rather than simplified away. It
// asks for FILE_READ_ATTRIBUTES and then requires GetFileInformationByHandle to
// succeed, so an open on its own is not enough. And it retries an open that
// fails with ERROR_CANT_ACCESS_FILE without following, because that is a Unix
// domain socket: its reparse point cannot be followed, and the file is there.
bool Describes(HANDLE handle) {
  BY_HANDLE_FILE_INFORMATION info;
  return ::GetFileInformationByHandle(handle, &info) != 0;
}

bool TargetExists(const WCHAR* path) {
  const DWORD kShareAll =
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
  HANDLE handle =
      ::CreateFileW(path, FILE_READ_ATTRIBUTES, kShareAll, nullptr,
                    OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
  if (handle == INVALID_HANDLE_VALUE) {
    if (::GetLastError() != ERROR_CANT_ACCESS_FILE) {
      return false;
    }
    handle = ::CreateFileW(
        path, FILE_READ_ATTRIBUTES, kShareAll, nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
      return false;
    }
  }
  bool described = Describes(handle);
  ::CloseHandle(handle);
  return described;
}

// Decides what a failed FindFirstFileExW means, the way that
// JavaIoFileSystem#getDirectoryEntries decides it today.
//
// That method calls File#list, and File#list reports every failure the same
// way: it returns null. The two exceptions it can throw are then chosen by
// File#exists alone - IOException(ERR_NOT_A_DIRECTORY) if the path exists, and
// FileNotFoundException otherwise. The Win32 error plays no part.
//
// This function makes the same decision from the same evidence, by following
// what File#exists does rather than what a Win32 error code says. The error
// code cannot decide it: FindFirstFileExW reports ERROR_DIRECTORY for an
// ordinary file and for a symbolic link to a file that is gone alike, and
// RemoteActionFileSystem needs those two told apart - it catches
// FileNotFoundException from readdir and reads the other source instead.
//
// File#exists is java.io.WinNTFileSystem#getBooleanAttributes, which is
// GetFileAttributesEx with two exceptions, and then a resolution of the final
// reparse point. Both parts are needed:
//
//   - The exceptions are ERROR_SHARING_VIOLATION and ERROR_ACCESS_DENIED, where
//     getFinalAttributes asks the parent directory through FindFirstFileW
//     instead. A file another process holds exclusively - pagefile.sys, a file
//     open in an editor, a file an antivirus is scanning - exists, and so does
//     a file whose own metadata the caller may not read while the entry the
//     parent directory holds for it stays readable.
//   - GetFileAttributesW describes a link and not its target, so a link whose
//     target cannot be reached needs the open that TargetExists makes.
//
// The probe costs one path resolution, and only on a path that has already
// failed, so no successful listing pays for it.
int ClassifyFindFirstError(const WCHAR* path, DWORD err) {
  DWORD attributes = ::GetFileAttributesW(path);
  if (attributes == INVALID_FILE_ATTRIBUTES) {
    DWORD attribute_error = ::GetLastError();
    if (attribute_error != ERROR_SHARING_VIOLATION &&
        attribute_error != ERROR_ACCESS_DENIED) {
      return ReadDirectoryResult::kDoesNotExist;
    }
    // The parent directory still holds an entry for it, and an entry the parent
    // holds is an entry that exists.
    WIN32_FIND_DATAW find_data;
    HANDLE handle = ::FindFirstFileW(path, &find_data);
    if (handle == INVALID_HANDLE_VALUE) {
      return ReadDirectoryResult::kDoesNotExist;
    }
    ::FindClose(handle);
    attributes = find_data.dwFileAttributes;
  }
  if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0 && !TargetExists(path)) {
    return ReadDirectoryResult::kDoesNotExist;
  }
  // This one error code and no other, which is what java.io's list0 accepts.
  // ERROR_NO_MORE_FILES would be a reasonable thing for a filesystem to return
  // here, and accepting it would turn an error into an empty listing on a
  // filesystem where the old code raised one - the worst direction for a glob
  // to be wrong in.
  if ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0 &&
      err == ERROR_FILE_NOT_FOUND) {
    // A "<dir>\*" pattern that matched nothing, on a directory that is there.
    // The directory is empty. NTFS always reports "." and "..", so this needs a
    // filesystem that omits them - some network redirectors do, and so does a
    // volume root under some configurations.
    return ReadDirectoryResult::kSuccess;
  }
  // The path is there and could not be listed. That is one case for the caller,
  // whether it is a file, a denied directory or a directory on a volume that
  // went away, because it is one case for File#list too.
  return ReadDirectoryResult::kNotADirectory;
}

}  // namespace

int ReadDirectory(const WCHAR* path, std::vector<DirectoryEntry>* result,
                  wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"ReadDirectory",
                                path, L"expected an absolute Windows path");
    }
    result->clear();
    return ReadDirectoryResult::kError;
  }

  wstring pattern(path);
  if (!pattern.empty() && pattern.back() != L'\\') {
    pattern.push_back(L'\\');
  }
  pattern.push_back(L'*');

  // FindExInfoBasic asks the filesystem not to compute the 8.3 alternate name,
  // which nothing here needs and which costs a lookup per entry on volumes that
  // still have short name creation enabled. FIND_FIRST_EX_LARGE_FETCH asks for
  // larger batches, cutting the number of user/kernel transitions.
  WIN32_FIND_DATAW find_data;
  HANDLE handle =
      ::FindFirstFileExW(pattern.c_str(), FindExInfoBasic, &find_data,
                         FindExSearchNameMatch, nullptr,
                         FIND_FIRST_EX_LARGE_FETCH);
  if (handle == INVALID_HANDLE_VALUE) {
    int classified = ClassifyFindFirstError(path, ::GetLastError());
    result->clear();
    return classified;
  }
  AutoFindHandle owner(handle);

  result->clear();
  do {
    if (IsDotOrDotDot(find_data.cFileName)) {
      continue;
    }
    result->push_back(DirectoryEntry{wstring(find_data.cFileName),
                                     find_data.dwFileAttributes});
  } while (::FindNextFileW(handle, &find_data));

  DWORD err = ::GetLastError();
  if (err != ERROR_NO_MORE_FILES) {
    // The directory was modified, or the volume went away, part way through.
    // java.io's list0 returns null for this, exactly as it does for a failed
    // FindFirstFile, so getDirectoryEntries picks between its two exceptions
    // the same way - and for a directory that has just been unlinked the answer
    // is FileNotFoundException, which RemoteActionFileSystem catches in order
    // to read another source. Deciding it here the same way keeps that
    // fall-through working.
    result->clear();
    int classified = ClassifyFindFirstError(path, err);
    // One of its answers does not carry over: the empty-directory case is
    // measured against a FindFirstFileExW that failed at once, and entries have
    // been read here, so this directory is not empty. A filesystem that ended
    // its enumeration with ERROR_FILE_NOT_FOUND rather than ERROR_NO_MORE_FILES
    // would otherwise report every directory it holds as empty - silently,
    // which is the one direction a listing must never be wrong in. java.io
    // raises for such a filesystem, and so does this.
    return classified == ReadDirectoryResult::kSuccess
               ? ReadDirectoryResult::kNotADirectory
               : classified;
  }

  return ReadDirectoryResult::kSuccess;
}

}  // namespace windows
}  // namespace bazel
