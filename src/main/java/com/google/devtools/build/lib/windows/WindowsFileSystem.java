// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import com.google.devtools.build.lib.windows.WindowsFileOperations.FileMetadata;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Collection;

/** File system implementation for Windows. */
@ThreadSafe
public class WindowsFileSystem extends JavaIoFileSystem {

  public static final LinkOption[] NO_OPTIONS = new LinkOption[0];
  public static final LinkOption[] NO_FOLLOW = new LinkOption[] {LinkOption.NOFOLLOW_LINKS};

  private final boolean createSymbolicLinks;

  public WindowsFileSystem(DigestHashFunction hashFunction, boolean createSymbolicLinks) {
    super(hashFunction);
    this.createSymbolicLinks = createSymbolicLinks;
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    // TODO(laszlocsomor): implement this properly, i.e. actually query this information from
    // somewhere (java.nio.Filesystem? System.getProperty? implement JNI method and use WinAPI?).
    return "ntfs";
  }

  @Override
  public boolean delete(PathFragment path) throws IOException {
    long startTime = Profiler.instance().nanoTimeMaybe();
    try {
      return WindowsFileOperations.deletePath(
          StringEncoding.internalToPlatform(path.getPathString()));
    } catch (java.nio.file.DirectoryNotEmptyException e) {
      throw new IOException(path.getPathString() + ERR_DIRECTORY_NOT_EMPTY, e);
    } catch (java.nio.file.AccessDeniedException e) {
      throw new IOException(path.getPathString() + ERR_PERMISSION_DENIED, e);
    } finally {
      Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, path.getPathString());
    }
  }

  @Override
  public void createSymbolicLink(
      PathFragment linkPath, PathFragment targetFragment, SymlinkTargetType type)
      throws IOException {
    PathFragment targetPath =
        targetFragment.isAbsolute()
            ? targetFragment
            : linkPath.getParentDirectory().getRelative(targetFragment);

    FileStatus stat = statIfFound(targetPath, /* followSymlinks= */ true);
    boolean existingFile = stat != null && stat.isFile();
    boolean existingDirectory = stat != null && stat.isDirectory();

    try {
      Path link = getNioPath(linkPath);
      Path target = getNioPath(targetPath);

      if (!createSymbolicLinks && existingFile) {
        // If symlinks aren't enabled and the target is an existing file, fall back to a copy.
        Files.copy(target, link);
      } else if (createSymbolicLinks
          && (existingFile || (!existingDirectory && type != SymlinkTargetType.DIRECTORY))) {
        // If symlinks are enabled and the target is not an existing or future directory, create a
        // symlink.
        WindowsFileOperations.createSymlink(link.toString(), target.toString());
      } else {
        // Otherwise, create a junction.
        WindowsFileOperations.createJunction(link.toString(), target.toString());
      }
    } catch (IOException e) {
      throw translateNioToIoException(linkPath, e);
    }
  }

  @Override
  public PathFragment readSymbolicLink(PathFragment path) throws IOException {
    java.nio.file.Path nioPath = getNioPath(path);
    return PathFragment.create(
        StringEncoding.platformToInternal(
            WindowsFileOperations.readSymlinkOrJunction(nioPath.toString())));
  }

  /**
   * Lists a directory's entries, and the name of each one only.
   *
   * <p>Overridden so that this class answers both questions from the same enumeration. The
   * inherited implementation calls {@code File#list}, which resolves the path a second time, and
   * one file system that answers the same question two ways is one file system that can give two
   * answers.
   */
  @Override
  public Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    // Straight from the enumeration, and not through readdir: the caller wants names, and
    // classifying each entry to build a Dirent that is then thrown away is work nobody asked for.
    // UnixFileSystem implements the two methods side by side for the same reason.
    String[] names = enumerate(path).names();
    ImmutableList.Builder<String> entries = ImmutableList.builderWithExpectedSize(names.length);
    for (String name : names) {
      entries.add(StringEncoding.platformToInternal(name));
    }
    return entries.build();
  }

  /**
   * Lists a directory's entries along with their types.
   *
   * <p>The inherited implementation lists the directory and then stats every entry, which on
   * Windows means resolving every child path through the filesystem driver stack a second time. A
   * directory enumeration already reports each entry's attributes, so that second resolution asks
   * for something the platform has already said.
   *
   * <p>This mirrors {@code UnixFileSystem#readdir}, which has always taken the type from {@code
   * d_type} and only stats an entry when the type is unusable, or when a symlink has to be
   * resolved because {@code followSymlinks} was requested.
   */
  @Override
  public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks) throws IOException {
    WindowsFileOperations.Dirents entries = enumerate(path);
    String[] names = entries.names();
    int[] attributes = entries.attributes();
    ImmutableList.Builder<Dirent> dirents = ImmutableList.builderWithExpectedSize(names.length);
    for (int i = 0; i < names.length; i++) {
      String entryName = StringEncoding.platformToInternal(names[i]);
      Dirent.Type type;
      if ((attributes[i] & WindowsFileOperations.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        // Bazel treats a junction like a symlink, so every reparse point is a symlink here - the
        // same single-bit test WindowsFileOperations#isSymlinkOrJunction performs, and the same
        // rule stat() applies. Following one is the only case that still needs a syscall of its
        // own, and reparse points are rare; the hot callers (SyscallCache#readdir, UnixGlob) pass
        // followSymlinks=false and never reach it at all.
        type =
            followSymlinks
                ? direntFromStat(statNullable(path.getChild(entryName), true))
                : Dirent.Type.SYMLINK;
      } else if ((attributes[i] & WindowsFileOperations.FILE_ATTRIBUTE_DEVICE) != 0) {
        // Before the directory bit, because direntFromStat asks isSpecialFile before isDirectory
        // and isSpecialFile is this bit. An enumeration is not documented to report it; the branch
        // is here so that the two paths cannot disagree about an entry if one ever does.
        type = Dirent.Type.UNKNOWN;
      } else if ((attributes[i] & WindowsFileOperations.FILE_ATTRIBUTE_DIRECTORY) != 0) {
        // The directory bit is only trustworthy once the entry is known not to be a reparse point:
        // it is set on junctions and directory symlinks too.
        type = Dirent.Type.DIRECTORY;
      } else {
        type = Dirent.Type.FILE;
      }
      dirents.add(new Dirent(entryName, type));
    }
    return dirents.build();
  }

  /**
   * One directory enumeration, with the exceptions the inherited implementation would have thrown.
   *
   * <p>{@code WindowsFileOperations#readDirectory} reports two failures, and it chooses between
   * them the way {@code JavaIoFileSystem#getDirectoryEntries} does: by whether the path exists once
   * every reparse point on it is followed. A path that is there and cannot be listed is "not a
   * directory", whether it is a file or a directory the user may not read.
   *
   * <p>A third failure is possible: an enumeration that starts and then fails. {@code File#list}
   * reports it the way it reports one that never started - by returning null - so it is decided by
   * the same probe, into the same two exceptions. Returning the entries read so far instead would
   * hand the caller a truncated listing it could not tell from a complete one.
   */
  private WindowsFileOperations.Dirents enumerate(PathFragment path) throws IOException {
    // toAbsolutePath because WindowsOsPathPolicy calls a path with no drive letter absolute,
    // and the native side needs one - WindowsPathOperations#asLongPath writes a \\?\ prefix,
    // which only a drive-qualified path may carry. java.nio returns a drive-qualified path
    // unchanged, so this costs nothing in the case that always happens.
    String name = getNioPath(path).toAbsolutePath().toString();
    long startTime = Profiler.instance().nanoTimeMaybe();
    try {
      return WindowsFileOperations.readDirectory(name);
    } catch (NotDirectoryException e) {
      throw new IOException(path + ERR_NOT_A_DIRECTORY, e);
    } catch (FileNotFoundException e) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    } finally {
      Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DIR, name);
    }
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return createSymbolicLinks;
  }

  @Override
  public boolean mayBeCaseOrNormalizationInsensitive() {
    return true;
  }

  @Override
  protected boolean fileIsSymbolicLink(Path file) {
    try {
      if (isSymlinkOrJunction(file)) {
        return true;
      }
    } catch (IOException e) {
      // Did not work, try in another way
    }
    return super.fileIsSymbolicLink(file);
  }

  public static LinkOption[] symlinkOpts(boolean followSymlinks) {
    return followSymlinks ? NO_OPTIONS : NO_FOLLOW;
  }

  @Override
  public FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    Path nioPath = getNioPath(path);
    FileMetadata metadata = statMetadata(path, nioPath, followSymlinks);

    // A reparse point is a symbolic link as far as Bazel is concerned, whether it is an NTFS
    // symlink or a junction.
    boolean isLink = !followSymlinks && metadata.isReparsePoint();

    FileStatus status =
        new FileStatus() {
          volatile long lastChangeTime = -1;

          @Override
          public boolean isFile() {
            return !isLink && (isRegularFile() || isSpecialFile());
          }

          private boolean isRegularFile() {
            return !metadata.isDirectory() && !metadata.isReparsePoint() && !metadata.isDevice();
          }

          @Override
          public boolean isSpecialFile() {
            // This is sun.nio.fs.WindowsFileAttributes#isOther, which is
            // !isSymbolicLink() && (FILE_ATTRIBUTE_DEVICE | FILE_ATTRIBUTE_REPARSE_POINT).
            // A junction answers it, and Bazel treats junctions like symlinks, so isLink has to
            // be tested first. This fixes https://github.com/bazelbuild/bazel/issues/9176
            return !isLink && (metadata.isDevice() || metadata.isReparsePoint());
          }

          @Override
          public boolean isDirectory() {
            return !isLink && metadata.isDirectory();
          }

          @Override
          public boolean isSymbolicLink() {
            return isLink;
          }

          @Override
          public long getSize() {
            return metadata.size();
          }

          @Override
          public long getLastModifiedTime() {
            return metadata.lastModifiedTime();
          }

          @Override
          public long getLastChangeTime() throws IOException {
            if (metadata.lastChangeTime() != FileMetadata.NO_CHANGE_TIME) {
              return metadata.lastChangeTime();
            }
            // The metadata came from java.nio, which carries no change time, so this costs a
            // second path resolution.
            if (lastChangeTime == -1) {
              lastChangeTime =
                  WindowsFileOperations.getLastChangeTime(nioPath.toString(), followSymlinks);
            }
            return lastChangeTime;
          }

          @Override
          public long getNodeId() {
            // TODO(bazel-team): Consider making use of attributes.fileKey().
            return -1;
          }

          @Override
          public int getPermissions() {
            // Files on Windows are implicitly readable and executable.
            return 0555 | (metadata.isReadOnly() ? 0 : 0200);
          }
        };

    return status;
  }

  /**
   * Reads the metadata of {@code path}, preferring the one native call that answers all of it.
   *
   * <p>That call exists only on Windows 11 build 26100 and newer, and it cannot follow a reparse
   * point. Everything it declines goes through {@code java.nio}, which costs what it costs today:
   * an attribute read, and a second path resolution for a change time if a caller asks for one.
   */
  private FileMetadata statMetadata(PathFragment path, Path nioPath, boolean followSymlinks)
      throws IOException {
    try {
      FileMetadata metadata =
          WindowsFileOperations.statIfSupported(nioPath.toString(), followSymlinks);
      if (metadata != null) {
        return metadata;
      }

      DosFileAttributes attributes = getAttribs(nioPath, followSymlinks);
      // An entry that is neither a symbolic link nor "other" cannot be a reparse point, so
      // fileIsSymbolicLink - which resolves the path again - runs only for one that could be.
      // DosFileAttributes#isOther is true for a junction and for a device alike, and only that
      // second test tells the two apart.
      boolean isReparsePoint =
          !followSymlinks
              && (attributes.isSymbolicLink() || attributes.isOther())
              && fileIsSymbolicLink(nioPath);
      return new FileMetadata(
          attributes.isDirectory(),
          isReparsePoint,
          !isReparsePoint && attributes.isOther(),
          attributes.isReadOnly(),
          attributes.size(),
          attributes.lastModifiedTime().toMillis(),
          FileMetadata.NO_CHANGE_TIME);
    } catch (IOException e) {
      // Every failure becomes a FileNotFoundException, because that is the only type
      // JavaIoFileSystem#statIfFound turns into a null; it wraps any other IOException in an
      // IllegalStateException, so a denied path would stop the server.
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
  }

  @Override
  public boolean isSymbolicLink(PathFragment path) {
    return fileIsSymbolicLink(getNioPath(path));
  }

  @Override
  public boolean isDirectory(PathFragment path, boolean followSymlinks) {
    if (!followSymlinks) {
      try {
        if (isSymlinkOrJunction(getNioPath(path))) {
          return false;
        }
      } catch (IOException e) {
        return false;
      }
    }
    return super.isDirectory(path, followSymlinks);
  }

  @Override
  public void setReadable(PathFragment path, boolean readable) {
    // Windows does not have a notion of readable files.
    // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
  }

  @Override
  public void setExecutable(PathFragment path, boolean executable) {
    // Windows does not have a notion of executable files.
    // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    // Windows does not have a notion of read-only directories.
    // See https://learn.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants.
    // JavaIoFileSystem#setWritable(dir, true) would throw, so reimplement it here as a no-op.
    if (isDirectory(path, /* followSymlinks= */ true)) {
      return;
    }
    super.setWritable(path, writable);
  }

  /**
   * Returns true if the path refers to a directory junction, directory symlink, or regular symlink.
   *
   * <p>Directory junctions are symbolic links created with "mklink /J" where the target is a
   * directory or another directory junction. Directory junctions can be created without any user
   * privileges.
   *
   * <p>Directory symlinks are symbolic links created with "mklink /D" where the target is a
   * directory or another directory symlink. Note that directory symlinks can only be created by
   * Administrators.
   *
   * <p>Normal symlinks are symbolic links created with "mklink". Normal symlinks should not point
   * at directories, because even though "mklink" can create the link, it will not be a functional
   * one (the linked directory's contents cannot be listed). Only Administrators may create regular
   * symlinks.
   *
   * <p>This method returns true for all three types as long as their target is a directory (even if
   * they are dangling), though only directory junctions and directory symlinks are useful.
   */
  @VisibleForTesting
  static boolean isSymlinkOrJunction(Path file) throws IOException {
    return WindowsFileOperations.isSymlinkOrJunction(file.toString());
  }

  private static DosFileAttributes getAttribs(Path file, boolean followSymlinks)
      throws IOException {
    return Files.readAttributes(file, DosFileAttributes.class, symlinkOpts(followSymlinks));
  }
}
