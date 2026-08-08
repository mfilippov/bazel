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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.DefaultSyscallCache;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.windows.util.WindowsTestUtil;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link WindowsFileSystem}. */
@RunWith(TestParameterInjector.class)
@TestSpec(supportedOs = OS.WINDOWS)
public class WindowsFileSystemTest {
  @TestParameter boolean createSymbolicLinks;

  private WindowsFileSystem fs;
  private Path scratchRoot;
  private WindowsTestUtil testUtil;

  @Before
  public void createScratchDir() throws Exception {
    fs = new WindowsFileSystem(DigestHashFunction.SHA256, createSymbolicLinks);
    scratchRoot = TestUtils.createUniqueTmpDir(fs);
    testUtil = new WindowsTestUtil(scratchRoot.getPathString());
  }

  @After
  public void destroyScratchDir() throws Exception {
    scratchRoot.deleteTree();
  }

  @Test
  public void testCanWorkWithJunctionSymlinks() throws Exception {
    testUtil.scratchFile("dir\\hello.txt", "hello");
    testUtil.scratchDir("non_existent");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir", "junc_bad", "non_existent"));

    Path juncPath = testUtil.createVfsPath(fs, "junc");
    Path dirPath = testUtil.createVfsPath(fs, "dir");
    Path juncBadPath = testUtil.createVfsPath(fs, "junc_bad");
    Path nonExistentPath = testUtil.createVfsPath(fs, "non_existent");

    // Test junction creation.
    assertThat(juncPath.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(dirPath.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(nonExistentPath.exists(Symlinks.NOFOLLOW)).isTrue();

    // Test recognizing and dereferencing a directory junction.
    assertThat(juncPath.isSymbolicLink()).isTrue();
    assertThat(juncPath.isDirectory(Symlinks.FOLLOW)).isTrue();
    assertThat(juncPath.isDirectory(Symlinks.NOFOLLOW)).isFalse();
    assertThat(juncPath.getDirectoryEntries())
        .containsExactly(testUtil.createVfsPath(fs, "junc\\hello.txt"));

    // Test deleting a directory junction.
    assertThat(juncPath.delete()).isTrue();
    assertThat(juncPath.exists(Symlinks.NOFOLLOW)).isFalse();

    // Test recognizing a dangling directory junction.
    assertThat(nonExistentPath.delete()).isTrue();
    assertThat(nonExistentPath.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isTrue();
    // TODO(bazel-team): fix https://github.com/bazelbuild/bazel/issues/1690 and uncomment the
    // assertion below.
    // assertThat(fs.isSymbolicLink(juncBadPath)).isTrue();
    assertThat(fs.isDirectory(juncBadPath.asFragment(), /* followSymlinks */ true)).isFalse();
    assertThat(fs.isDirectory(juncBadPath.asFragment(), /* followSymlinks */ false)).isFalse();

    // Test deleting a dangling junction.
    assertThat(juncBadPath.delete()).isTrue();
    assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isFalse();
  }

  // The assertions below hold whichever way WindowsFileSystem#stat answered: the single native
  // call on Windows 11 build 26100 and newer, or java.nio anywhere else. That the two agree is
  // what they are for.

  @Test
  public void testStatOfRegularFile(@TestParameter boolean followSymlinks) throws Exception {
    java.nio.file.Path nioPath = testUtil.scratchFile("dir\\hello.txt", "hello");
    Path path = testUtil.createVfsPath(fs, "dir\\hello.txt");

    FileStatus status = fs.stat(path.asFragment(), followSymlinks);

    assertThat(status.isFile()).isTrue();
    assertThat(status.isDirectory()).isFalse();
    assertThat(status.isSymbolicLink()).isFalse();
    assertThat(status.isSpecialFile()).isFalse();
    assertThat(status.getSize()).isEqualTo(Files.size(nioPath));
    assertThat(status.getLastModifiedTime())
        .isEqualTo(Files.getLastModifiedTime(nioPath).toMillis());
  }

  @Test
  public void testStatOfDirectory(@TestParameter boolean followSymlinks) throws Exception {
    testUtil.scratchDir("a_dir");
    Path path = testUtil.createVfsPath(fs, "a_dir");

    FileStatus status = fs.stat(path.asFragment(), followSymlinks);

    assertThat(status.isDirectory()).isTrue();
    assertThat(status.isFile()).isFalse();
    assertThat(status.isSymbolicLink()).isFalse();
    assertThat(status.isSpecialFile()).isFalse();
  }

  @Test
  public void testStatOfRootDirectory() throws Exception {
    PathFragment root = PathFragment.create(scratchRoot.getPathString().substring(0, 3));

    FileStatus status = fs.stat(root, /* followSymlinks= */ true);

    assertThat(status.isDirectory()).isTrue();
    assertThat(status.isSymbolicLink()).isFalse();
  }

  @Test
  public void testStatOfJunctionDependsOnFollowSymlinks() throws Exception {
    testUtil.scratchFile("dir\\hello.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir"));
    Path junc = testUtil.createVfsPath(fs, "junc");

    FileStatus noFollow = fs.stat(junc.asFragment(), /* followSymlinks= */ false);
    assertThat(noFollow.isSymbolicLink()).isTrue();
    assertThat(noFollow.isDirectory()).isFalse();
    assertThat(noFollow.isFile()).isFalse();
    assertThat(noFollow.isSpecialFile()).isFalse();

    FileStatus follow = fs.stat(junc.asFragment(), /* followSymlinks= */ true);
    assertThat(follow.isSymbolicLink()).isFalse();
    assertThat(follow.isDirectory()).isTrue();
    assertThat(follow.isFile()).isFalse();
    assertThat(follow.isSpecialFile()).isFalse();
  }

  @Test
  public void testStatOfDanglingJunction() throws Exception {
    testUtil.scratchDir("non_existent");
    testUtil.createJunctions(ImmutableMap.of("junc_bad", "non_existent"));
    Path target = testUtil.createVfsPath(fs, "non_existent");
    Path juncBad = testUtil.createVfsPath(fs, "junc_bad");
    assertThat(target.delete()).isTrue();

    FileStatus noFollow = fs.stat(juncBad.asFragment(), /* followSymlinks= */ false);
    assertThat(noFollow.isSymbolicLink()).isTrue();
    assertThat(noFollow.isDirectory()).isFalse();

    assertThat(fs.statIfFound(juncBad.asFragment(), /* followSymlinks= */ true)).isNull();
  }

  @Test
  public void testStatOfMissingFile() throws Exception {
    Path missing = testUtil.createVfsPath(fs, "no_such_file");

    PathFragment fragment = missing.asFragment();
    assertThrows(
        FileNotFoundException.class, () -> fs.stat(fragment, /* followSymlinks= */ true));
    assertThat(fs.statIfFound(missing.asFragment(), /* followSymlinks= */ true)).isNull();
    assertThat(fs.statIfFound(missing.asFragment(), /* followSymlinks= */ false)).isNull();
  }

  @Test
  public void testStatChangeTimeAgreesWithASecondResolution() throws Exception {
    // Where the stat carries a change time this compares it against the separate call; elsewhere
    // it exercises the fallback, which makes that separate call itself.
    java.nio.file.Path nioPath = testUtil.scratchFile("dir\\hello.txt", "hello");
    Path path = testUtil.createVfsPath(fs, "dir\\hello.txt");

    long fromStat = fs.stat(path.asFragment(), /* followSymlinks= */ true).getLastChangeTime();
    long fromSecondCall =
        WindowsFileOperations.getLastChangeTime(
            nioPath.toAbsolutePath().toString(), /* followReparsePoints= */ true);

    assertThat(fromStat).isEqualTo(fromSecondCall);
  }

  @Test
  public void testMockJunctionCreation() throws Exception {
    String root = testUtil.scratchDir("dir").getParent().toString();
    testUtil.scratchFile("dir/file.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir"));
    String[] children = new File(root + "/junc").list();
    assertThat(children).isNotNull();
    assertThat(children).hasLength(1);
    assertThat(Arrays.asList(children)).containsExactly("file.txt");
  }

  @Test
  public void testIsJunction() throws Exception {
    final Map<String, String> junctions = new HashMap<>();
    junctions.put("shrtpath/a", "shrttrgt");
    junctions.put("shrtpath/b", "longtargetpath");
    junctions.put("shrtpath/c", "longta~1");
    junctions.put("longlinkpath/a", "shrttrgt");
    junctions.put("longlinkpath/b", "longtargetpath");
    junctions.put("longlinkpath/c", "longta~1");
    junctions.put("abbrev~1/a", "shrttrgt");
    junctions.put("abbrev~1/b", "longtargetpath");
    junctions.put("abbrev~1/c", "longta~1");

    String root = testUtil.scratchDir("shrtpath").getParent().toAbsolutePath().toString();
    testUtil.scratchDir("longlinkpath");
    testUtil.scratchDir("abbreviated");
    testUtil.scratchDir("control/a");
    testUtil.scratchDir("control/b");
    testUtil.scratchDir("control/c");

    testUtil.scratchFile("shrttrgt/file1.txt", "hello");
    testUtil.scratchFile("longtargetpath/file2.txt", "hello");

    testUtil.createJunctions(junctions);

    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/a"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/b"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/c"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/a"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/b"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/c"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/a"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/b"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/c"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/a"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/b"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/c"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/a"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/b"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/c"))).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/a"))).isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/b"))).isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/c"))).isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrttrgt/file1.txt")))
        .isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longtargetpath/file2.txt")))
        .isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longta~1/file2.txt")))
        .isFalse();

    assertThrows(
        FileNotFoundException.class,
        () -> WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "non-existent")));

    assertThat(Arrays.asList(new File(root + "/shrtpath/a").list())).containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/b").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/c").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/c").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/c").list()))
        .containsExactly("file2.txt");
  }

  @Test
  public void testIsJunctionIsTrueForDanglingJunction() throws Exception {
    java.nio.file.Path helloPath = testUtil.scratchFile("target\\hello.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("link", "target"));

    File linkPath = new File(helloPath.getParent().getParent().toFile(), "link");
    assertThat(Arrays.asList(linkPath.list())).containsExactly("hello.txt");
    assertThat(WindowsFileSystem.isSymlinkOrJunction(linkPath.toPath())).isTrue();

    assertThat(helloPath.toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().exists()).isFalse();
    assertThat(Arrays.asList(linkPath.getParentFile().list())).containsExactly("link");

    assertThat(WindowsFileSystem.isSymlinkOrJunction(linkPath.toPath())).isTrue();
    assertThat(
            Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts(/* followSymlinks */ false)))
        .isTrue();
    assertThat(
            Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts(/* followSymlinks */ true)))
        .isFalse();
  }

  @Test
  public void testIsJunctionHandlesFilesystemChangesCorrectly() throws Exception {
    File longPath =
        testUtil.scratchFile("target\\helloworld.txt", "hello").toAbsolutePath().toFile();
    File shortPath = new File(longPath.getParentFile(), "hellow~1.txt");
    assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isFalse();

    assertThat(longPath.delete()).isTrue();
    testUtil.createJunctions(ImmutableMap.of("target\\helloworld.txt", "target"));
    assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isTrue();

    assertThat(longPath.delete()).isTrue();
    assertThat(longPath.mkdir()).isTrue();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isFalse();
    assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isFalse();
  }

  @Test
  public void testShortPathResolution() throws Exception {
    String shortPath = "shortp~1.res/foo/withsp~1/bar/~witht~1/hello.txt";
    String longPath = "shortpath.resolution/foo/with spaces/bar/~with tilde/hello.txt";
    testUtil.scratchFile(longPath, "hello");
    Path p = scratchRoot.getRelative(shortPath);
    assertThat(p.getPathString()).endsWith(longPath);
    assertThat(p).isEqualTo(scratchRoot.getRelative(shortPath));
    assertThat(p).isEqualTo(scratchRoot.getRelative(longPath));
    assertThat(scratchRoot.getRelative(shortPath)).isEqualTo(p);
    assertThat(scratchRoot.getRelative(longPath)).isEqualTo(p);
  }

  @Test
  public void testUnresolvableShortPathWhichIsThenCreated() throws Exception {
    String shortPath = "unreso~1.sho/foo/will~1.exi/bar/hello.txt";
    String longPath = "unresolvable.shortpath/foo/will.exist/bar/hello.txt";
    // Assert that we can create an unresolvable path.
    Path p = scratchRoot.getRelative(shortPath);
    assertThat(p.getPathString()).endsWith(shortPath);
    // Assert that we can then create the whole path, and can now resolve the short form.
    testUtil.scratchFile(longPath, "hello");
    Path q = scratchRoot.getRelative(shortPath);
    assertThat(q.getPathString()).endsWith(longPath);
    assertThat(p).isNotEqualTo(q);
  }

  /**
   * Test the scenario when a short path resolves to different long ones over time.
   *
   * <p>This can happen if the user deletes a directory during the bazel server's lifetime, then
   * recreates it with the same name prefix such that the resulting directory's 8dot3 name is the
   * same as the old one's.
   */
  @Test
  public void testShortPathResolvesToDifferentPathsOverTime() throws Exception {
    Path p1 = scratchRoot.getRelative("longpa~1");
    Path p2 = scratchRoot.getRelative("longpa~1");
    assertThat(p1.exists()).isFalse();
    assertThat(p1).isEqualTo(p2);

    testUtil.scratchDir("longpathnow");
    Path q1 = scratchRoot.getRelative("longpa~1");
    assertThat(q1.exists()).isTrue();
    assertThat(q1).isEqualTo(scratchRoot.getRelative("longpathnow"));

    // Delete the original resolution of "longpa~1" ("longpathnow").
    assertThat(q1.delete()).isTrue();
    assertThat(q1.exists()).isFalse();

    // Create a directory whose 8dot3 name is also "longpa~1" but its long name is different.
    testUtil.scratchDir("longpaththen");
    Path r1 = scratchRoot.getRelative("longpa~1");
    assertThat(r1.exists()).isTrue();
    assertThat(r1).isEqualTo(scratchRoot.getRelative("longpaththen"));
  }

  @Test
  public void testSymbolicLinkToExistingFile(@TestParameter SymlinkTargetType targetType)
      throws Exception {
    Path linkPath = scratchRoot.getRelative("link");
    Path targetPath = scratchRoot.getRelative("target");
    assertThat(targetPath.getParentDirectory().exists()).isTrue();
    assertThat(targetPath.getParentDirectory().isDirectory()).isTrue();
    FileSystemUtils.writeContentAsLatin1(targetPath, "hello");

    linkPath.createSymbolicLink(targetPath, targetType);

    if (createSymbolicLinks) {
      assertThat(linkPath.isSymbolicLink()).isTrue();
      assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment());
    } else {
      assertThat(linkPath.isSymbolicLink()).isFalse();
      assertThrows(NotASymlinkException.class, () -> linkPath.readSymbolicLink());
    }
    assertThat(linkPath.exists()).isTrue();
    assertThat(linkPath.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(linkPath, ISO_8859_1)).isEqualTo("hello");

    linkPath.delete();
    assertThat(linkPath.exists()).isFalse();
    assertThat(targetPath.exists()).isTrue();
  }

  @Test
  public void testSymbolicLinkToExistingDirectory(@TestParameter SymlinkTargetType targetType)
      throws Exception {
    Path linkPath = scratchRoot.getRelative("link");
    Path linkChildPath = linkPath.getRelative("hello.txt");
    Path targetPath = scratchRoot.getRelative("target");
    Path targetChildPath = targetPath.getRelative("hello.txt");
    targetPath.createDirectory();
    FileSystemUtils.writeContentAsLatin1(targetChildPath, "hello");

    linkPath.createSymbolicLink(targetPath, targetType);

    assertThat(linkPath.isSymbolicLink()).isTrue();
    assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment());
    assertThat(linkPath.exists()).isTrue();
    assertThat(linkPath.isDirectory()).isTrue();
    assertThat(linkChildPath.exists()).isTrue();
    assertThat(linkChildPath.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(linkChildPath, ISO_8859_1)).isEqualTo("hello");

    linkPath.delete();
    assertThat(linkPath.exists()).isFalse();
    assertThat(targetPath.exists()).isTrue();
  }

  @Test
  public void testCreateSymbolicLinkToNonExistingTargetOfUnspecifiedType() throws Exception {
    Path linkPath = scratchRoot.getRelative("link");
    Path targetPath = scratchRoot.getRelative("target");

    linkPath.createSymbolicLink(targetPath, SymlinkTargetType.UNSPECIFIED);

    assertThat(linkPath.isSymbolicLink()).isTrue();
    assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment());
    assertThat(linkPath.exists()).isFalse();

    // Check that a dangling symlink is preferred over a dangling junction when supported.
    // Do this by creating a target of the corresponding type and verifying that it can be accessed.
    if (createSymbolicLinks) {
      FileSystemUtils.writeContentAsLatin1(targetPath, "hello");
      assertThat(linkPath.exists()).isTrue();
      assertThat(linkPath.isFile()).isTrue();
      assertThat(FileSystemUtils.readContent(linkPath, ISO_8859_1)).isEqualTo("hello");
    } else {
      targetPath.createDirectory();
      assertThat(linkPath.exists()).isTrue();
      assertThat(linkPath.isDirectory()).isTrue();
    }
  }

  @Test
  public void testCreateSymbolicLinkToNonExistingTargetOfFileType() throws Exception {
    // This is only expected to work if symlinks are enabled.
    // Otherwise, our only recourse is to create a dangling junction, which does not work for files.
    assumeTrue(createSymbolicLinks);

    Path linkPath = scratchRoot.getRelative("link");
    Path targetPath = scratchRoot.getRelative("target");

    linkPath.createSymbolicLink(targetPath, SymlinkTargetType.FILE);

    assertThat(linkPath.isSymbolicLink()).isTrue();
    assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment());

    FileSystemUtils.writeContentAsLatin1(targetPath, "hello");

    assertThat(linkPath.exists()).isTrue();
    assertThat(linkPath.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(linkPath, ISO_8859_1)).isEqualTo("hello");
  }

  @Test
  public void testCreateSymbolicLinkToNonExistingTargetOfDirectoryType() throws Exception {
    Path linkPath = scratchRoot.getRelative("link");
    Path linkChildPath = linkPath.getRelative("hello.txt");
    Path targetPath = scratchRoot.getRelative("target");
    Path targetChildPath = targetPath.getRelative("hello.txt");

    linkPath.createSymbolicLink(targetPath, SymlinkTargetType.DIRECTORY);

    assertThat(linkPath.isSymbolicLink()).isTrue();
    assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment());

    targetPath.createDirectory();
    FileSystemUtils.writeContentAsLatin1(targetChildPath, "hello");

    assertThat(linkPath.exists()).isTrue();
    assertThat(linkPath.isDirectory()).isTrue();
    assertThat(linkChildPath.exists()).isTrue();
    assertThat(linkChildPath.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(linkChildPath, ISO_8859_1)).isEqualTo("hello");
  }

  @Test
  public void testReadSymbolicLinkForFile() throws Exception {
    Path filePath = scratchRoot.getRelative("file");
    FileSystemUtils.writeContentAsLatin1(filePath, "hello");

    assertThrows(NotASymlinkException.class, filePath::readSymbolicLink);
  }

  @Test
  public void testReadSymbolicLinkForDirectory() throws Exception {
    Path dirPath = scratchRoot.getRelative("dir");
    dirPath.createDirectory();

    assertThrows(NotASymlinkException.class, dirPath::readSymbolicLink);
  }

  @Test
  public void testReadSymbolicLinkForNonexistentPath() throws Exception {
    Path nonexistentPath = scratchRoot.getRelative("nonexistent");

    assertThrows(FileNotFoundException.class, nonexistentPath::readSymbolicLink);
  }

  @Test
  public void testReadOnlyAttribute() throws Exception {
    testUtil.scratchFile("dir\\hello.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir"));

    Path dir = testUtil.createVfsPath(fs, "dir");
    Path file = testUtil.createVfsPath(fs, "dir\\hello.txt");
    Path dirViaJunction = testUtil.createVfsPath(fs, "junc");
    Path fileViaJunction = testUtil.createVfsPath(fs, "junc\\hello.txt");

    assertWritable(dir);
    dir.setWritable(false); // no-op
    assertWritable(dir);
    dir.setWritable(true); // no-op
    assertWritable(dir);

    assertWritable(dirViaJunction);
    dirViaJunction.setWritable(false); // no-op
    assertWritable(dirViaJunction);
    dirViaJunction.setWritable(true); // no-op
    assertWritable(dirViaJunction);

    assertWritable(file);
    file.setWritable(false);
    assertNotWritable(file);
    file.setWritable(true);
    assertWritable(file);

    assertThat(fileViaJunction.isWritable()).isTrue();
    fileViaJunction.setWritable(false);
    assertNotWritable(fileViaJunction);
    fileViaJunction.setWritable(true);
    assertWritable(fileViaJunction);
  }

  private static void assertWritable(Path path) throws Exception {
    assertThat(path.isWritable()).isTrue();
    assertThat(path.stat().getPermissions()).isEqualTo(0755);
  }

  private static void assertNotWritable(Path path) throws Exception {
    assertThat(path.isWritable()).isFalse();
    assertThat(path.stat().getPermissions()).isEqualTo(0555);
  }

  @Test
  public void testTypeViaReaddirCache(
      @TestParameter({
            "BUILD", "Å", "K", "Ａ", "ａ", "０", " 𝐀", "𝐴", "𝒜", "Ⅳ", "Ⓑ", "ẞ", "ß", "Ä", "İ", "ı"
          })
          String entry)
      throws Exception {
    var normalizedEntry =
        Normalizer.normalize(entry, Normalizer.Form.NFC)
            .toUpperCase(Locale.ROOT)
            .toLowerCase(Locale.ROOT);
    validateGetTypeConsistency(scratchRoot, entry, normalizedEntry);
    validateGetTypeConsistency(scratchRoot, normalizedEntry, entry);
  }

  private void validateGetTypeConsistency(Path baseDir, String entryToCreate, String entryToCheck)
      throws IOException {
    baseDir.createDirectoryAndParents();
    var dir = baseDir.createTempDirectory("readdir_cache-");
    var pathToCreate = dir.getChild(StringEncoding.unicodeToInternal(entryToCreate));
    FileSystemUtils.createEmptyFile(pathToCreate);

    var syscallCache = DefaultSyscallCache.newBuilder().build();
    // Prime the cache by reading the parent directory.
    syscallCache.readdir(dir);
    assertWithMessage("expecting entry %s to exist", entryToCreate)
        .that(syscallCache.getType(pathToCreate, Symlinks.FOLLOW))
        .isNotNull();

    var pathToCheck = dir.getChild(StringEncoding.unicodeToInternal(entryToCheck));
    var existsWithCache = syscallCache.getType(pathToCheck, Symlinks.FOLLOW) != null;
    var existsWithoutCache = pathToCheck.statIfFound() != null;
    assertWithMessage("created : %s", entryToCreate)
        .withMessage("checking: %s", entryToCheck)
        .withMessage("with cache: %s", existsWithCache)
        .withMessage("w/o cache : %s", existsWithoutCache)
        .that(existsWithCache)
        .isEqualTo(existsWithoutCache);
  }

  // ---------------------------------------------------------------------------------------------
  // readdir
  //
  // WindowsFileSystem overrides readdir to answer from a single directory enumeration instead of
  // re-resolving every child path. These cases pin the behaviour that override has to preserve:
  // they were written against the inherited FileSystem#readdir first, and pass unchanged against
  // the override.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testReaddirEmptyDirectory() throws Exception {
    Path dir = scratchRoot.getRelative("empty");
    dir.createDirectory();

    assertThat(dir.readdir(Symlinks.NOFOLLOW)).isEmpty();
  }

  @Test
  public void testReaddirReportsPlainEntries() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    FileSystemUtils.writeContentAsLatin1(dir.getRelative("file.txt"), "hello");
    FileSystemUtils.createEmptyFile(dir.getRelative("zero-length"));
    dir.getRelative("subdir").createDirectory();

    assertThat(dir.readdir(Symlinks.NOFOLLOW))
        .containsExactly(
            new Dirent("file.txt", Dirent.Type.FILE),
            new Dirent("zero-length", Dirent.Type.FILE),
            new Dirent("subdir", Dirent.Type.DIRECTORY));
  }

  @Test
  public void testReaddirReportsHiddenAndReadOnlyEntriesAsPlainFiles() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    Path hidden = dir.getRelative("hidden.txt");
    Path readOnly = dir.getRelative("readonly.txt");
    FileSystemUtils.writeContentAsLatin1(hidden, "hello");
    FileSystemUtils.writeContentAsLatin1(readOnly, "hello");
    Files.setAttribute(Paths.get(hidden.getPathString()), "dos:hidden", true);
    Files.setAttribute(Paths.get(readOnly.getPathString()), "dos:readonly", true);

    // FILE_ATTRIBUTE_HIDDEN and FILE_ATTRIBUTE_READONLY have no bearing on Dirent.Type. An
    // implementation that tested dwFileAttributes for anything other than DIRECTORY and
    // REPARSE_POINT would get these wrong.
    assertThat(dir.readdir(Symlinks.NOFOLLOW))
        .containsExactly(
            new Dirent("hidden.txt", Dirent.Type.FILE),
            new Dirent("readonly.txt", Dirent.Type.FILE));
  }

  @Test
  public void testReaddirOnRegularFileThrows() throws Exception {
    Path file = scratchRoot.getRelative("file.txt");
    FileSystemUtils.writeContentAsLatin1(file, "hello");

    IOException e = assertThrows(IOException.class, () -> file.readdir(Symlinks.NOFOLLOW));
    // Not a FileNotFoundException: the path exists, it is just not a directory.
    assertThat(e).isNotInstanceOf(FileNotFoundException.class);
  }

  @Test
  public void testReaddirOnMissingPathThrowsFileNotFound() throws Exception {
    Path missing = scratchRoot.getRelative("does-not-exist");

    assertThrows(FileNotFoundException.class, () -> missing.readdir(Symlinks.NOFOLLOW));
  }

  @Test
  public void testReaddirWithMissingParentThrowsFileNotFound() throws Exception {
    Path missing = scratchRoot.getRelative("no-such-dir").getRelative("child");

    assertThrows(FileNotFoundException.class, () -> missing.readdir(Symlinks.NOFOLLOW));
  }

  @Test
  public void testReaddirNofollowReportsEveryReparsePointAsSymlink() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    Path target = dir.getRelative("target");
    target.createDirectory();
    FileSystemUtils.writeContentAsLatin1(target.getRelative("inside.txt"), "hello");
    Path fileTarget = dir.getRelative("file-target.txt");
    FileSystemUtils.writeContentAsLatin1(fileTarget, "hello");

    testUtil.createJunctions(ImmutableMap.of("dir\\junction", "dir\\target"));
    dir.getRelative("dirlink").createSymbolicLink(target, SymlinkTargetType.DIRECTORY);
    dir.getRelative("filelink").createSymbolicLink(fileTarget, SymlinkTargetType.FILE);

    // Bazel treats a junction like a symlink, so under NOFOLLOW each reparse point is SYMLINK.
    // That is one FILE_ATTRIBUTE_REPARSE_POINT test for each entry, and not a lookup of the
    // reparse tag for each entry.
    //
    // "filelink" is the exception, and only when createSymbolicLinks is false. createSymbolicLink
    // then copies an existing file instead of linking to it, so the entry is an ordinary file and
    // not a reparse point. A directory gets a junction in either mode, so "dirlink" does not
    // change.
    assertThat(dir.readdir(Symlinks.NOFOLLOW))
        .containsExactly(
            new Dirent("target", Dirent.Type.DIRECTORY),
            new Dirent("file-target.txt", Dirent.Type.FILE),
            new Dirent("junction", Dirent.Type.SYMLINK),
            new Dirent("dirlink", Dirent.Type.SYMLINK),
            new Dirent(
                "filelink",
                createSymbolicLinks ? Dirent.Type.SYMLINK : Dirent.Type.FILE));
  }

  @Test
  public void testReaddirFollowResolvesReparsePoints() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    Path target = dir.getRelative("target");
    target.createDirectory();
    Path fileTarget = dir.getRelative("file-target.txt");
    FileSystemUtils.writeContentAsLatin1(fileTarget, "hello");

    testUtil.createJunctions(ImmutableMap.of("dir\\junction", "dir\\target"));
    dir.getRelative("dirlink").createSymbolicLink(target, SymlinkTargetType.DIRECTORY);
    dir.getRelative("filelink").createSymbolicLink(fileTarget, SymlinkTargetType.FILE);

    assertThat(dir.readdir(Symlinks.FOLLOW))
        .containsExactly(
            new Dirent("target", Dirent.Type.DIRECTORY),
            new Dirent("file-target.txt", Dirent.Type.FILE),
            new Dirent("junction", Dirent.Type.DIRECTORY),
            new Dirent("dirlink", Dirent.Type.DIRECTORY),
            new Dirent("filelink", Dirent.Type.FILE));
  }

  @Test
  public void testReaddirFollowReportsDanglingReparsePointsAsUnknown() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    Path doomed = dir.getRelative("doomed");
    doomed.createDirectory();
    testUtil.createJunctions(ImmutableMap.of("dir\\dangling-junction", "dir\\doomed"));
    dir.getRelative("dangling-link")
        .createSymbolicLink(dir.getRelative("never-existed"), SymlinkTargetType.FILE);
    doomed.delete();

    // FOLLOW has to stat the target and the stat fails; direntFromStat(null) is UNKNOWN.
    assertThat(dir.readdir(Symlinks.FOLLOW))
        .containsExactly(
            new Dirent("dangling-junction", Dirent.Type.UNKNOWN),
            new Dirent("dangling-link", Dirent.Type.UNKNOWN));
  }

  @Test
  public void testReaddirThroughJunction() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    FileSystemUtils.writeContentAsLatin1(dir.getRelative("inside.txt"), "hello");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir"));

    // A reparse point encountered while resolving the directory itself is followed whatever
    // followSymlinks says; that argument only governs the types of the entries.
    assertThat(scratchRoot.getRelative("junc").readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("inside.txt", Dirent.Type.FILE));
  }

  @Test
  public void testReaddirWithAwkwardNames() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    String[] names = {
      "space in name.txt",
      "dot.in.name.txt",
      "...leading-dots.txt",
      "Привет.txt",
      "你好.txt",
      // A surrogate pair, so the UTF-16 unit count differs from the code point count.
      "😀.txt",
    };
    for (String name : names) {
      FileSystemUtils.writeContentAsLatin1(
          dir.getChild(StringEncoding.unicodeToInternal(name)), "hello");
    }

    assertThat(
            dir.readdir(Symlinks.NOFOLLOW).stream()
                .map(d -> StringEncoding.internalToUnicode(d.getName()))
                .collect(toImmutableList()))
        .containsExactlyElementsIn(names);
  }

  @Test
  public void testReaddirPathLongerThanMaxPath() throws Exception {
    // MAX_PATH is 260; walk past it in segments none of which is individually unusual.
    Path dir = scratchRoot;
    for (int i = 0; i < 12; i++) {
      dir = dir.getRelative("0123456789abcdefghijklmnopqrstuvwxyz");
    }
    dir.createDirectoryAndParents();
    assertThat(dir.getPathString().length()).isGreaterThan(260);
    FileSystemUtils.createEmptyFile(dir.getRelative("leaf.txt"));

    assertThat(dir.readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("leaf.txt", Dirent.Type.FILE));
  }

  @Test
  public void testReaddirManyEntries() throws Exception {
    // Enough to span several FindNextFileW batches, so a truncated listing would show up here.
    Path dir = scratchRoot.getRelative("many");
    dir.createDirectory();
    ImmutableList.Builder<Dirent> expected = ImmutableList.builder();
    for (int i = 0; i < 2000; i++) {
      FileSystemUtils.createEmptyFile(dir.getRelative("entry-" + i));
      expected.add(new Dirent("entry-" + i, Dirent.Type.FILE));
    }

    // The contents and not only the count: a batch boundary that repeated or dropped one name
    // would keep the count right.
    assertThat(dir.readdir(Symlinks.NOFOLLOW)).containsExactlyElementsIn(expected.build());
  }

  @Test
  public void testReaddirOnDanglingSymlinkToFileThrowsFileNotFound() throws Exception {
    assumeTrue(createSymbolicLinks);

    Path doomed = scratchRoot.getRelative("doomed.txt");
    FileSystemUtils.writeContentAsLatin1(doomed, "hello");
    Path link = scratchRoot.getRelative("dangling-link");
    link.createSymbolicLink(doomed, SymlinkTargetType.FILE);
    doomed.delete();

    // The case a Win32 error code cannot decide on its own. Listing this path and listing a
    // regular file both fail with ERROR_DIRECTORY, and the two have to be told apart, because
    // File#exists follows the link and finds nothing while the file is there.
    //
    // RemoteActionFileSystem catches FileNotFoundException from readdir and reads the other source
    // instead, so an IOException here turns a fall-through into a failure.
    assertThrows(FileNotFoundException.class, () -> link.readdir(Symlinks.NOFOLLOW));
  }

  @Test
  public void testReaddirOnUnlistableDirectoryThrowsNotADirectory() throws Exception {
    Path dir = scratchRoot.getRelative("denied");
    dir.createDirectory();
    FileSystemUtils.createEmptyFile(dir.getRelative("unreachable.txt"));
    AclEntry deny = denyListing(dir);
    assumeTrue(deny != null);
    try {
      // File#list returns null for a directory the user may not list, and File#exists is true, so
      // the inherited getDirectoryEntries reports "not a directory". A java.nio
      // AccessDeniedException must not reach the caller: it carries neither that message nor a
      // type readdir's callers are written against.
      IOException e = assertThrows(IOException.class, () -> dir.readdir(Symlinks.NOFOLLOW));
      assertThat(e).isNotInstanceOf(FileNotFoundException.class);
      assertThat(e).hasMessageThat().endsWith(" (Not a directory)");
    } finally {
      // Without this the scratch tree cannot be deleted. FileSystem#deleteTreesBelow answers a
      // failed listing by calling setReadable and setExecutable, and both are no-ops on Windows,
      // so @After would fail and leave behind a directory that only an ACL edit can remove.
      restoreListing(dir, deny);
    }
  }

  @Test
  public void testReaddirAndGetDirectoryEntriesAgree() throws Exception {
    Path dir = scratchRoot.getRelative("dir");
    dir.createDirectory();
    FileSystemUtils.writeContentAsLatin1(dir.getRelative("file.txt"), "hello");
    dir.getRelative("subdir").createDirectory();
    testUtil.createJunctions(ImmutableMap.of("dir\\junction", "dir\\subdir"));

    // The other side is File#list, which is what JavaIoFileSystem#getDirectoryEntries calls.
    // Comparing the two overrides against each other would prove nothing, because both answer from
    // the same enumeration - the test has to leave the class to say anything at all.
    //
    // Path#getDirectoryEntries returns children, not names, so both sides are reduced to names.
    ImmutableList<String> fromFileList =
        Arrays.stream(new File(dir.getPathString()).list())
            .map(StringEncoding::platformToInternal)
            .collect(toImmutableList());
    assertThat(
            dir.getDirectoryEntries().stream()
                .map(Path::getBaseName)
                .collect(toImmutableList()))
        .containsExactlyElementsIn(fromFileList);
    assertThat(
            dir.readdir(Symlinks.NOFOLLOW).stream()
                .map(Dirent::getName)
                .collect(toImmutableList()))
        .containsExactlyElementsIn(fromFileList);
  }

  @Test
  public void testReaddirOnDanglingJunctionThrowsFileNotFound() throws Exception {
    // The same case as the dangling file symlink, without needing the Create symbolic links right,
    // so this one runs in every configuration. FindFirstFileExW reports ERROR_PATH_NOT_FOUND here
    // and ERROR_DIRECTORY for a live junction to a file, and neither code decides the question:
    // what decides it is that File#exists follows the junction and finds nothing.
    Path doomed = scratchRoot.getRelative("doomed");
    doomed.createDirectory();
    testUtil.createJunctions(ImmutableMap.of("dangling-junction", "doomed"));
    doomed.delete();

    assertThrows(
        FileNotFoundException.class,
        () -> scratchRoot.getRelative("dangling-junction").readdir(Symlinks.NOFOLLOW));
  }

  @Test
  public void testReaddirOnJunctionToAFileThrowsFileNotFound() throws Exception {
    // A junction whose target is a file that is there. It is still reported missing, and not as
    // "not a directory", because a junction to a file cannot be followed: the open that resolves
    // the final reparse point fails, and File#exists reports every such failure as absent. This
    // was measured, against a junction built exactly as below - File#list returns null and
    // File#exists returns false, so JavaIoFileSystem#getDirectoryEntries raises this same
    // exception. The target existing is not what the question turns on.
    //
    // This is the case an ordinary file separates it from: a file exists, so it is "not a
    // directory" - see testReadDirectoryThrowsNotDirectoryForRegularFile. A Win32 error code
    // cannot tell the two apart, because FindFirstFileExW answers ERROR_DIRECTORY for both.
    Path target = scratchRoot.getRelative("target.txt");
    FileSystemUtils.writeContentAsLatin1(target, "hello");
    testUtil.createJunctions(ImmutableMap.of("junction-to-file", "target.txt"));

    Path junction = scratchRoot.getRelative("junction-to-file");
    IOException e =
        assertThrows(
            FileNotFoundException.class, () -> junction.readdir(Symlinks.NOFOLLOW));
    assertThat(e).hasMessageThat().endsWith(" (No such file or directory)");
  }

  /**
   * Denies the owner of `dir` the right to list it, and returns the entry that was added.
   *
   * <p>NOFOLLOW throughout, so that the descriptor of the named path is read and written directly.
   * Following the link would mean opening the directory, and the entry this adds is what stops
   * that, so the entry could not be taken off again.
   *
   * @return null if the file system carries no ACLs, in which case the caller skips
   */
  @Nullable
  private static AclEntry denyListing(Path dir) {
    AclFileAttributeView view = aclView(dir);
    if (view == null) {
      return null;
    }
    try {
      AclEntry deny =
          AclEntry.newBuilder()
              .setType(AclEntryType.DENY)
              .setPrincipal(
                  Files.getOwner(Paths.get(dir.getPathString()), LinkOption.NOFOLLOW_LINKS))
              .setPermissions(AclEntryPermission.LIST_DIRECTORY, AclEntryPermission.READ_DATA)
              .build();
      List<AclEntry> acl = new ArrayList<>(view.getAcl());
      // First: Windows reads the list in order and stops at the first entry that matches.
      acl.add(0, deny);
      view.setAcl(acl);
      // An entry that changed nothing is no fixture. An account with SeBackupPrivilege, or a
      // filesystem that keeps the descriptor and ignores it, would leave the listing readable.
      if (new File(dir.getPathString()).list() != null) {
        restoreListing(dir, deny);
        return null;
      }
      return deny;
    } catch (IOException | UnsupportedOperationException e) {
      return null;
    }
  }

  private static void restoreListing(Path dir, AclEntry deny) throws IOException {
    AclFileAttributeView view = aclView(dir);
    List<AclEntry> acl = new ArrayList<>(view.getAcl());
    acl.remove(deny);
    view.setAcl(acl);
  }

  @Nullable
  private static AclFileAttributeView aclView(Path dir) {
    return Files.getFileAttributeView(
        Paths.get(dir.getPathString()), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
  }
}
