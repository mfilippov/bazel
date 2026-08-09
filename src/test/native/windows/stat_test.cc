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

#include <cstdint>
#include <string>

#include "gtest/gtest.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/util.h"
#include "src/test/cpp/util/windows_test_util.h"

#if !defined(_WIN32) && !defined(__CYGWIN__)
#error("This test should only be run on Windows")
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

namespace bazel {
namespace windows {

using blaze_util::CreateDummyFile;
using blaze_util::DeleteAllUnder;
using blaze_util::GetTestTmpDirW;
using std::string;
using std::wstring;

static const wstring kUncPrefix = wstring(L"\\\\?\\");
static const string kContent = "hello";

// Whether this machine carries GetFileInformationByName, which arrived in
// Windows 11 build 26100.
//
// Asked of the platform rather than of Stat, which resolves the same symbol.
// Stat declines without a word wherever the call is missing, so nothing it
// returns can tell a fast path that never runs from one that works, and a build
// server on Windows Server would ship either of them green.
static bool HasGetFileInformationByName() {
  return ::GetProcAddress(::GetModuleHandleW(L"kernel32.dll"),
                          "GetFileInformationByName") != nullptr;
}

static int64_t ToTicks(const FILETIME& time) {
  return static_cast<int64_t>(
      (static_cast<uint64_t>(time.dwHighDateTime) << 32) | time.dwLowDateTime);
}

class WindowsStatTest : public ::testing::Test {
 public:
  void SetUp() override {
    if (!HasGetFileInformationByName()) {
      GTEST_SKIP() << "this machine has no GetFileInformationByName; "
                   << "WindowsFileSystemTest covers the java.nio fallback";
    }
  }
  void TearDown() override { DeleteAllUnder(GetTestTmpDirW()); }

 protected:
  static wstring TmpDir() { return kUncPrefix + GetTestTmpDirW(); }
};

TEST(WindowsStatNoFastPathTest, TestDeclinesWhereThePlatformLacksTheCall) {
  if (HasGetFileInformationByName()) {
    GTEST_SKIP() << "this machine has GetFileInformationByName";
  }
  wstring path(kUncPrefix + GetTestTmpDirW());
  FileMetadata metadata = {};
  EXPECT_EQ(StatResult::kUnsupported, Stat(path.c_str(), false, &metadata));
}

TEST_F(WindowsStatTest, TestDeclinesPathThatIsNotAbsoluteAndNormalized) {
  FileMetadata metadata = {};
  EXPECT_EQ(StatResult::kUnsupported, Stat(L"", false, &metadata));
  EXPECT_EQ(StatResult::kUnsupported, Stat(L"foo\\bar", false, &metadata));
  EXPECT_EQ(StatResult::kUnsupported,
            Stat(L"c:\\foo\\..\\bar", false, &metadata));
}

TEST_F(WindowsStatTest, TestDeclinesTrailingDotOrSpace) {
  // Win32 strips a trailing dot or space from the final component of a path
  // that carries no "\\?\" prefix, and this call always carries one. Declining
  // keeps one implementation answering such a path on every version of
  // Windows, rather than the fast path and the fallback disagreeing.
  wstring dot(TmpDir() + L"\\a_file.");
  wstring space(TmpDir() + L"\\a_file ");
  ASSERT_TRUE(CreateDummyFile(TmpDir() + L"\\a_file", kContent));

  FileMetadata metadata = {};
  EXPECT_EQ(StatResult::kUnsupported, Stat(dot.c_str(), false, &metadata));
  EXPECT_EQ(StatResult::kUnsupported, Stat(space.c_str(), false, &metadata));
}

TEST_F(WindowsStatTest, TestReportsMissingPath) {
  wstring path(TmpDir() + L"\\no_such_entry");
  FileMetadata metadata = {};
  EXPECT_EQ(StatResult::kDoesNotExist, Stat(path.c_str(), false, &metadata));
  EXPECT_EQ(StatResult::kDoesNotExist, Stat(path.c_str(), true, &metadata));
}

TEST_F(WindowsStatTest, TestDescribesRegularFile) {
  wstring path(TmpDir() + L"\\a_file");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  for (bool follow : {false, true}) {
    FileMetadata metadata = {};
    ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), follow, &metadata))
        << "follow=" << follow;
    EXPECT_EQ(0u, metadata.attributes & FILE_ATTRIBUTE_DIRECTORY);
    EXPECT_EQ(0u, metadata.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
    EXPECT_EQ(static_cast<int64_t>(kContent.size()), metadata.size);
    EXPECT_GT(metadata.last_write_time.QuadPart, 0);
    EXPECT_GT(metadata.change_time.QuadPart, 0);
  }
}

TEST_F(WindowsStatTest, TestDescribesDirectory) {
  wstring path(TmpDir() + L"\\a_dir");
  ASSERT_TRUE(::CreateDirectoryW(path.c_str(), nullptr));

  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, &metadata));
  EXPECT_NE(0u, metadata.attributes & FILE_ATTRIBUTE_DIRECTORY);
  EXPECT_EQ(0u, metadata.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
}

TEST_F(WindowsStatTest, TestDescribesRootDirectory) {
  wstring root(TmpDir().substr(0, kUncPrefix.size() + 3));
  ASSERT_EQ(L'\\', root[root.size() - 1]);

  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(root.c_str(), false, &metadata));
  EXPECT_NE(0u, metadata.attributes & FILE_ATTRIBUTE_DIRECTORY);
}

TEST_F(WindowsStatTest, TestDescribesJunctionButDeclinesToFollowIt) {
  wstring target(TmpDir() + L"\\junc_target");
  wstring name(TmpDir() + L"\\junc");
  ASSERT_TRUE(::CreateDirectoryW(target.c_str(), nullptr));
  ASSERT_EQ(CreateJunctionResult::kSuccess,
            CreateJunction(name, target, nullptr));

  // NOFOLLOW describes the junction: a directory that is also a reparse point.
  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(name.c_str(), false, &metadata));
  EXPECT_NE(0u, metadata.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
  EXPECT_NE(0u, metadata.attributes & FILE_ATTRIBUTE_DIRECTORY);

  // FOLLOW wants the target, and this call cannot resolve one.
  EXPECT_EQ(StatResult::kUnsupported, Stat(name.c_str(), true, &metadata));
}

TEST_F(WindowsStatTest, TestDescribesDanglingJunctionUnderNofollow) {
  wstring target(TmpDir() + L"\\gone");
  wstring name(TmpDir() + L"\\dangling_junc");
  ASSERT_TRUE(::CreateDirectoryW(target.c_str(), nullptr));
  ASSERT_EQ(CreateJunctionResult::kSuccess,
            CreateJunction(name, target, nullptr));
  ASSERT_TRUE(::RemoveDirectoryW(target.c_str()));

  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(name.c_str(), false, &metadata));
  EXPECT_NE(0u, metadata.attributes & FILE_ATTRIBUTE_REPARSE_POINT);
}

TEST_F(WindowsStatTest, TestDescribesFileAnotherHandleHoldsExclusively) {
  // GetFileInformationByName opens no handle, so an exclusive holder does not
  // trouble it. GetFileAttributesExW fails with ERROR_SHARING_VIOLATION on such
  // a file, and sun.nio.fs.WindowsFileAttributes has a second attempt through
  // FindFirstFile for it; this call needs none.
  wstring path(TmpDir() + L"\\exclusive");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  AutoHandle handle(::CreateFileW(path.c_str(), GENERIC_READ,
                                  /* dwShareMode */ 0, nullptr, OPEN_EXISTING,
                                  FILE_ATTRIBUTE_NORMAL, nullptr));
  ASSERT_TRUE(handle.IsValid());

  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, &metadata));
  EXPECT_EQ(static_cast<int64_t>(kContent.size()), metadata.size);
}

TEST_F(WindowsStatTest, TestReadsTheSameFieldsAsTheCallsItReplaces) {
  wstring path(TmpDir() + L"\\a_file");
  ASSERT_TRUE(CreateDummyFile(path, kContent));

  FileMetadata metadata = {};
  ASSERT_EQ(StatResult::kSuccess, Stat(path.c_str(), false, &metadata));

  // After the call under test, because GetChangeTime opens a handle and this
  // compares a change time.
  WIN32_FILE_ATTRIBUTE_DATA expected = {};
  ASSERT_TRUE(
      ::GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &expected));
  int64_t expected_change_time = 0;
  ASSERT_EQ(GetChangeTimeResult::kSuccess,
            GetChangeTime(path.c_str(), false, &expected_change_time, nullptr));

  // FILE_STAT_BASIC_INFORMATION is declared in stat.cc, because the SDK may not
  // declare it. A layout that drifts from the platform's would be read as the
  // wrong fields rather than refused - sizeof is all the call is told - and
  // these are the fields Bazel decides with.
  EXPECT_EQ(expected.dwFileAttributes, metadata.attributes);
  EXPECT_EQ((static_cast<int64_t>(expected.nFileSizeHigh) << 32) |
                expected.nFileSizeLow,
            metadata.size);
  EXPECT_EQ(ToTicks(expected.ftLastWriteTime), metadata.last_write_time.QuadPart);
  EXPECT_EQ(expected_change_time,
            WindowsFileTimeToUnixMillis(metadata.change_time));
}

}  // namespace windows
}  // namespace bazel
