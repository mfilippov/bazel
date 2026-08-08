// Copyright 2017 The Bazel Authors. All rights reserved.
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

#include <windows.h>

#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "src/main/native/jni.h"
#include "src/main/native/windows/directory.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/jni-util.h"
#include "src/main/native/windows/stat.h"
#include "src/main/native/windows/util.h"

static bool CanReportError(JNIEnv* env, jobjectArray error_msg_holder) {
  return error_msg_holder != nullptr &&
         env->GetArrayLength(error_msg_holder) > 0;
}

static void ReportLastError(const std::wstring& error_str, JNIEnv* env,
                            jobjectArray error_msg_holder) {
  jstring error_msg = env->NewString(
      reinterpret_cast<const jchar*>(error_str.c_str()), error_str.size());
  env->SetObjectArrayElement(error_msg_holder, 0, error_msg);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeIsSymlinkOrJunction(
    JNIEnv* env, jclass clazz, jstring path, jbooleanArray result_holder,
    jobjectArray error_msg_holder) {
  std::wstring wpath(bazel::windows::GetJavaWstring(env, path));
  std::wstring error;
  bool is_sym = false;
  int result =
      bazel::windows::IsSymlinkOrJunction(wpath.c_str(), &is_sym, &error);
  if (result == bazel::windows::IsSymlinkOrJunctionResult::kSuccess) {
    jboolean is_sym_jbool = is_sym ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(result_holder, 0, 1, &is_sym_jbool);
  } else {
    if (!error.empty() && CanReportError(env, error_msg_holder)) {
      ReportLastError(
          bazel::windows::MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                           L"nativeIsJunction", wpath, error),
          env, error_msg_holder);
    }
  }
  return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeGetChangeTime(
    JNIEnv* env, jclass clazz, jstring path, jboolean follow_reparse_points,
    jlongArray result_holder, jobjectArray error_msg_holder) {
  std::wstring wpath(bazel::windows::GetJavaWstring(env, path));
  std::wstring error;
  jlong ctime = 0;
  int result =
      bazel::windows::GetChangeTime(wpath.c_str(), follow_reparse_points,
                                    reinterpret_cast<int64_t*>(&ctime), &error);
  if (result == bazel::windows::GetChangeTimeResult::kSuccess) {
    env->SetLongArrayRegion(result_holder, 0, 1, &ctime);
  } else {
    if (!error.empty() && CanReportError(env, error_msg_holder)) {
      ReportLastError(
          bazel::windows::MakeErrorMessage(
              WSTR(__FILE__), __LINE__, L"nativeGetChangeTime", wpath, error),
          env, error_msg_holder);
    }
  }
  return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeCreateJunction(
    JNIEnv* env, jclass clazz, jstring name, jstring target,
    jobjectArray error_msg_holder) {
  std::wstring wname(bazel::windows::GetJavaWstring(env, name));
  std::wstring wtarget(bazel::windows::GetJavaWstring(env, target));
  std::wstring error;
  int result = bazel::windows::CreateJunction(wname, wtarget, &error);
  if (result != bazel::windows::CreateJunctionResult::kSuccess &&
      !error.empty() && CanReportError(env, error_msg_holder)) {
    ReportLastError(bazel::windows::MakeErrorMessage(
                        WSTR(__FILE__), __LINE__, L"nativeCreateJunction",
                        wname + L", " + wtarget, error),
                    env, error_msg_holder);
  }
  return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeCreateSymlink(
    JNIEnv* env, jclass clazz, jstring name, jstring target,
    jobjectArray error_msg_holder) {
  std::wstring wname(bazel::windows::GetJavaWstring(env, name));
  std::wstring wtarget(bazel::windows::GetJavaWstring(env, target));
  std::wstring error;
  int result = bazel::windows::CreateSymlink(wname, wtarget, &error);
  if (result != bazel::windows::CreateSymlinkResult::kSuccess &&
      !error.empty() && CanReportError(env, error_msg_holder)) {
    ReportLastError(bazel::windows::MakeErrorMessage(
                        WSTR(__FILE__), __LINE__, L"nativeCreateSymlink",
                        wname + L", " + wtarget, error),
                    env, error_msg_holder);
  }
  return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeReadSymlinkOrJunction(
    JNIEnv* env, jclass clazz, jstring name, jobjectArray target_holder,
    jobjectArray error_msg_holder) {
  std::wstring wname(bazel::windows::GetJavaWstring(env, name));
  std::wstring target, error;
  int result = bazel::windows::ReadSymlinkOrJunction(wname, &target, &error);
  if (result == bazel::windows::ReadSymlinkOrJunctionResult::kSuccess) {
    env->SetObjectArrayElement(
        target_holder, 0,
        env->NewString(reinterpret_cast<const jchar*>(target.c_str()),
                       target.size()));
  } else {
    if (!error.empty() && CanReportError(env, error_msg_holder)) {
      ReportLastError(bazel::windows::MakeErrorMessage(
                          WSTR(__FILE__), __LINE__,
                          L"nativeReadSymlinkOrJunction", wname, error),
                      env, error_msg_holder);
    }
  }
  return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeDeletePath(
    JNIEnv* env, jclass clazz, jstring path, jobjectArray error_msg_holder) {
  std::wstring wpath(bazel::windows::GetJavaWstring(env, path));
  std::wstring error;
  int result = bazel::windows::DeletePath(wpath, &error);
  if (result != bazel::windows::DeletePathResult::kSuccess && !error.empty() &&
      CanReportError(env, error_msg_holder)) {
    ReportLastError(
        bazel::windows::MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                         L"nativeDeletePath", wpath, error),
        env, error_msg_holder);
  }
  return result;
}

// Raises java.lang.OutOfMemoryError, for a native allocation that failed.
//
// Every other allocation on this path is the JVM's, and the JVM leaves the
// error pending by itself. These are the two that are not.
static void ThrowOutOfMemory(JNIEnv* env, const char* what) {
  if (env->ExceptionCheck()) {
    return;
  }
  jclass oom = env->FindClass("java/lang/OutOfMemoryError");
  if (oom != nullptr) {
    env->ThrowNew(oom, what);
    env->DeleteLocalRef(oom);
  }
}

// java.lang.String, looked up one time and held through a global reference.
//
// FindClass walks the class loader, and nativeReadDirectory runs for every
// directory of every glob. The lookup does not belong on that path.
//
// A global reference has no owner to release it, which is correct here: the
// class is java.lang.String, the reference lives as long as the JVM, and this
// library is never unloaded.
static jclass StringClass(JNIEnv* env) {
  // A function-local static with a dynamic initializer. C++11 makes exactly one
  // thread run the initializer and the others wait, so the pointer is written
  // once and read under a happens-before edge - a plain static would be a data
  // race, and this path runs on every worker thread at once.
  //
  // java.lang.String is loaded by the bootstrap loader before any native code
  // runs, so FindClass cannot fail here, and caching the result forever is
  // correct: it is the same class for the life of the JVM. There is likewise
  // nothing to retry, so the initializer cannot cache a failure that a later
  // call would have recovered from.
  static jclass cached = [env]() -> jclass {
    jclass local = env->FindClass("java/lang/String");
    if (local == nullptr) {
      return nullptr;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
  }();
  return cached;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeReadDirectory(
    JNIEnv* env, jclass clazz, jstring path, jobjectArray names_holder,
    jobjectArray attributes_holder, jobjectArray error_msg_holder) {
  std::wstring wpath(bazel::windows::GetJavaWpath(env, path));
  std::wstring error;
  std::vector<bazel::windows::DirectoryEntry> entries;
  int result;
  try {
    result = bazel::windows::ReadDirectory(wpath.c_str(), &entries, &error);
  } catch (const std::bad_alloc&) {
    // A directory large enough to exhaust the native heap. A C++ exception that
    // leaves this frame enters the JVM, which is undefined behaviour and in
    // practice ends the process; an OutOfMemoryError is what the caller expects
    // and what File#list produces for the same condition.
    entries.clear();
    entries.shrink_to_fit();
    ThrowOutOfMemory(env, "listing a directory");
    return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
  }
  if (result != bazel::windows::ReadDirectoryResult::kSuccess) {
    if (!error.empty() && CanReportError(env, error_msg_holder)) {
      ReportLastError(bazel::windows::MakeErrorMessage(
                          WSTR(__FILE__), __LINE__, L"nativeReadDirectory",
                          wpath, error),
                      env, error_msg_holder);
    }
    return static_cast<jint>(result);
  }

  const jsize count = static_cast<jsize>(entries.size());

  // Each allocation below fails only by leaving an OutOfMemoryError pending,
  // and the JVM raises that as soon as this function returns. The value
  // returned is therefore never read; kError is returned to say so, and the
  // Java side never has to look at 'error'.
  jclass string_class = StringClass(env);
  if (string_class == nullptr) {
    return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
  }
  jobjectArray names = env->NewObjectArray(count, string_class, nullptr);
  if (names == nullptr) {
    return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
  }
  for (jsize i = 0; i < count; ++i) {
    const std::wstring& name = entries[i].name;
    jstring jname = env->NewString(reinterpret_cast<const jchar*>(name.c_str()),
                                   static_cast<jsize>(name.size()));
    if (jname == nullptr) {
      return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
    }
    env->SetObjectArrayElement(names, i, jname);
    // A directory with many entries would otherwise exhaust the local reference
    // table, whose guaranteed capacity is only 16 slots.
    env->DeleteLocalRef(jname);
  }

  jintArray attributes = env->NewIntArray(count);
  if (attributes == nullptr) {
    return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
  }
  if (count > 0) {
    // Copied into a contiguous buffer so that the whole array crosses the
    // boundary in one call. Per-element calls would cost a JNI transition per
    // directory entry, which is the cost this whole change exists to remove.
    std::vector<jint> raw;
    try {
      raw.reserve(entries.size());
    } catch (const std::bad_alloc&) {
      ThrowOutOfMemory(env, "copying directory entry attributes");
      return static_cast<jint>(bazel::windows::ReadDirectoryResult::kError);
    }
    for (const auto& entry : entries) {
      raw.push_back(static_cast<jint>(entry.attributes));
    }
    env->SetIntArrayRegion(attributes, 0, count, raw.data());
  }

  env->SetObjectArrayElement(names_holder, 0, names);
  env->SetObjectArrayElement(attributes_holder, 0, attributes);
  return static_cast<jint>(bazel::windows::ReadDirectoryResult::kSuccess);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_windows_WindowsFileOperations_nativeStat(
    JNIEnv* env, jclass clazz, jstring path, jboolean follow_reparse_points,
    jlongArray result_holder) {
  std::wstring wpath(bazel::windows::GetJavaWstring(env, path));
  bazel::windows::FileMetadata metadata = {};
  int result =
      bazel::windows::Stat(wpath.c_str(), follow_reparse_points, &metadata);
  if (result != bazel::windows::StatResult::kSuccess) {
    // Neither outcome carries a message: "does not exist" needs none, and every
    // other failure is an instruction to the caller to resolve the path the way
    // it did before, not an error to report.
    return static_cast<jint>(result);
  }

  // Keep the layout in sync with WindowsFileOperations#stat. The times are
  // converted here, so that every time Java receives from this file is in the
  // one unit Java counts in, as nativeGetChangeTime's already is.
  jlong values[bazel::windows::kStatResultLength] = {
      static_cast<jlong>(metadata.attributes),
      static_cast<jlong>(metadata.size),
      bazel::windows::WindowsFileTimeToUnixMillis(metadata.last_write_time),
      bazel::windows::WindowsFileTimeToUnixMillis(metadata.change_time),
  };
  env->SetLongArrayRegion(result_holder, 0, bazel::windows::kStatResultLength,
                          values);
  return static_cast<jint>(bazel::windows::StatResult::kSuccess);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_google_devtools_build_lib_windows_WindowsPathOperations_nativeGetLongPath(
    JNIEnv* env, jclass clazz, jstring path, jobjectArray result_holder,
    jobjectArray error_msg_holder) {
  std::unique_ptr<WCHAR[]> result;
  std::wstring wpath(bazel::windows::GetJavaWstring(env, path));
  std::wstring error(bazel::windows::GetLongPath(wpath.c_str(), &result));
  if (!error.empty()) {
    if (CanReportError(env, error_msg_holder)) {
      ReportLastError(
          bazel::windows::MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                           L"nativeGetLongPath", wpath, error),
          env, error_msg_holder);
    }
    return JNI_FALSE;
  }
  env->SetObjectArrayElement(
      result_holder, 0,
      env->NewString(reinterpret_cast<const jchar*>(result.get()),
                     wcslen(result.get())));
  return JNI_TRUE;
}
