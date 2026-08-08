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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.windows.util.WindowsTestUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WindowsFileOperations}. */
@RunWith(JUnit4.class)
@TestSpec(supportedOs = OS.WINDOWS)
public class WindowsFileOperationsTest {

  private String scratchRoot;
  private WindowsTestUtil testUtil;

  @Before
  public void setUp() throws Exception {
    scratchRoot = new File(System.getenv("TEST_TMPDIR"), "x").getAbsolutePath();
    testUtil = new WindowsTestUtil(scratchRoot);
    cleanupScratchDir();
  }

  @After
  public void cleanupScratchDir() throws Exception {
    testUtil.deleteAllUnder("");
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
  public void testSymlinkCreation() throws Exception {
    File helloFile = testUtil.scratchFile("file.txt", "hello").toFile();
    File symlinkFile = new File(scratchRoot, "symlink");
    testUtil.createSymlinks(ImmutableMap.of("symlink", "file.txt"));

    assertThat(WindowsFileOperations.isSymlinkOrJunction(symlinkFile.toString())).isTrue();
    assertThat(symlinkFile.exists()).isTrue();

    // Assert deleting the symlink does not remove the target file.
    assertThat(WindowsFileOperations.deletePath(symlinkFile.toString())).isTrue();
    assertThat(helloFile.exists()).isTrue();
    assertThrows(
        FileNotFoundException.class,
        () -> WindowsFileOperations.isSymlinkOrJunction(symlinkFile.toString()));
  }

  @Test
  public void testSymlinkCreationFailsForDirectory() throws Exception {
    testUtil.scratchDir("dir").toFile();

    try {
      testUtil.createSymlinks(ImmutableMap.of("symlink", "dir"));
      fail("Expected to throw: Symlinks to a directory should fail.");
    } catch (IOException e) {
      assertThat(e).hasMessageThat().contains("target is a directory");
    }
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

    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\a")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\b")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\c")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\a")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\b")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\c")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\a")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\b")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\c")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\a")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\b")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\c")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\a")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\b")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\c")).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\a")).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\b")).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\c")).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrttrgt\\file1.txt")).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longtargetpath\\file2.txt"))
        .isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longta~1\\file2.txt")).isFalse();
    assertThrows(
        FileNotFoundException.class,
        () -> WindowsFileOperations.isSymlinkOrJunction(root + "\\non-existent"));
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
    assertThat(WindowsFileOperations.isSymlinkOrJunction(linkPath.getAbsolutePath())).isTrue();

    assertThat(helloPath.toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().exists()).isFalse();
    assertThat(Arrays.asList(linkPath.getParentFile().list())).containsExactly("link");

    assertThat(WindowsFileOperations.isSymlinkOrJunction(linkPath.getAbsolutePath())).isTrue();
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
    File helloFile =
        testUtil.scratchFile("target\\helloworld.txt", "hello").toAbsolutePath().toFile();

    // Assert that a file is identified as not a junction.
    String longPath = helloFile.getAbsolutePath();
    String shortPath = new File(helloFile.getParentFile(), "hellow~1.txt").getAbsolutePath();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isFalse();

    // Assert that after deleting the file and creating a junction with the same path, it is
    // identified as a junction.
    assertThat(helloFile.delete()).isTrue();
    testUtil.createJunctions(ImmutableMap.of("target\\helloworld.txt", "target"));
    assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isTrue();

    // Assert that after deleting the file and creating a directory with the same path, it is
    // identified as not a junction.
    assertThat(helloFile.delete()).isTrue();
    assertThat(helloFile.mkdir()).isTrue();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isFalse();
    assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isFalse();
  }

  @Test
  public void testStatDeclinesAFailureThatIsNotAMissingFile() throws Exception {
    // "<" cannot occur in a Windows file name, so the call fails for a reason that is not "there
    // is nothing there". Only the latter is answered here; everything else is handed back to the
    // caller to resolve the path its own way, and reporting it as missing would tell Bazel that a
    // file it cannot describe was deleted.
    //
    // The directory has to be there, or the failure would be the missing parent.
    testUtil.scratchDir("");
    String path = scratchRoot + "\\in<valid";

    assertThat(WindowsFileOperations.statIfSupported(path, /* followReparsePoints= */ true))
        .isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // readDirectory
  //
  // These cases test the enumeration itself: the names, the attribute bits, and the error map.
  // WindowsFileSystemTest tests the readdir override above it, which turns those bits into a
  // Dirent.Type. The two levels fail for different reasons, so they need separate tests.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testReadDirectoryReturnsNamesAndAttributes() throws Exception {
    testUtil.scratchFile("dir/file.txt", "hello");
    testUtil.scratchDir("dir/subdir");
    testUtil.scratchDir("target");
    testUtil.createJunctions(ImmutableMap.of("dir/junc", "target"));

    WindowsFileOperations.Dirents entries =
        WindowsFileOperations.readDirectory(new File(scratchRoot, "dir").toString());

    assertThat(entries.names()).hasLength(3);
    assertThat(entries.attributes()).hasLength(3);
    // "." and ".." are not entries.
    assertThat(Arrays.asList(entries.names())).containsExactly("file.txt", "subdir", "junc");

    Map<String, Integer> byName = new HashMap<>();
    for (int i = 0; i < entries.names().length; i++) {
      byName.put(entries.names()[i], entries.attributes()[i]);
    }

    assertThat(byName.get("file.txt") & WindowsFileOperations.FILE_ATTRIBUTE_DIRECTORY)
        .isEqualTo(0);
    assertThat(byName.get("file.txt") & WindowsFileOperations.FILE_ATTRIBUTE_REPARSE_POINT)
        .isEqualTo(0);

    assertThat(byName.get("subdir") & WindowsFileOperations.FILE_ATTRIBUTE_DIRECTORY)
        .isNotEqualTo(0);
    assertThat(byName.get("subdir") & WindowsFileOperations.FILE_ATTRIBUTE_REPARSE_POINT)
        .isEqualTo(0);

    // A junction sets both bits. A caller that wants to tell a link from a directory must
    // therefore test FILE_ATTRIBUTE_REPARSE_POINT first.
    assertThat(byName.get("junc") & WindowsFileOperations.FILE_ATTRIBUTE_REPARSE_POINT)
        .isNotEqualTo(0);
    assertThat(byName.get("junc") & WindowsFileOperations.FILE_ATTRIBUTE_DIRECTORY).isNotEqualTo(0);
  }

  @Test
  public void testReadDirectoryOnEmptyDirectory() throws Exception {
    testUtil.scratchDir("empty");

    WindowsFileOperations.Dirents entries =
        WindowsFileOperations.readDirectory(new File(scratchRoot, "empty").toString());

    assertThat(entries.names()).isEmpty();
    assertThat(entries.attributes()).isEmpty();
  }

  @Test
  public void testReadDirectoryThrowsFileNotFoundForMissingPath() {
    assertThrows(
        FileNotFoundException.class,
        () -> WindowsFileOperations.readDirectory(new File(scratchRoot, "nope").toString()));
  }

  @Test
  public void testReadDirectoryThrowsFileNotFoundForMissingParent() {
    assertThrows(
        FileNotFoundException.class,
        () ->
            WindowsFileOperations.readDirectory(
                new File(scratchRoot, "nope/child").toString()));
  }

  @Test
  public void testReadDirectoryThrowsNotDirectoryForRegularFile() throws Exception {
    testUtil.scratchFile("file.txt", "hello");

    // A separate exception from FileNotFoundException: the path exists, and it is not a directory.
    // FileSystem#readdir maps the two to different errors, so the enumeration must separate them.
    assertThrows(
        NotDirectoryException.class,
        () -> WindowsFileOperations.readDirectory(new File(scratchRoot, "file.txt").toString()));
  }

  @Test
  public void testReadDirectoryRejectsPathThatIsNotNormalized() throws Exception {
    testUtil.scratchFile("dir/file.txt", "hello");

    // WindowsPathOperations#asLongPath adds the "\\?\" prefix, and Win32 passes an
    // extended-length path through without a change. A path with "\.\" in it therefore reaches
    // the filesystem as it is. readDirectory applies the same IsAbsoluteNormalizedWindowsPath test
    // that each other native in this class applies, and fails with the path in the message.
    //
    // PathFragment normalizes on construction, so Bazel cannot produce such a path. This case
    // holds the behaviour, and it is not a limit that a caller can reach.
    IOException e =
        assertThrows(
            IOException.class,
            () ->
                WindowsFileOperations.readDirectory(
                    new File(scratchRoot, ".\\dir").toString()));
    assertThat(e).hasMessageThat().contains("dir");
  }

  @Test
  public void testReadDirectoryWithPathLongerThanMaxPath() throws Exception {
    // MAX_PATH is 260. Build a path past it from segments that are each ordinary.
    StringBuilder relative = new StringBuilder("longpath");
    for (int i = 0; i < 8; i++) {
      relative.append("/0123456789abcdefghijklmnopqrstuvwxyz");
    }
    testUtil.scratchFile(relative + "/leaf.txt", "hello");
    File dir = new File(scratchRoot, relative.toString().replace('/', '\\'));
    assertThat(dir.toString().length()).isGreaterThan(260);

    WindowsFileOperations.Dirents entries = WindowsFileOperations.readDirectory(dir.toString());

    assertThat(Arrays.asList(entries.names())).containsExactly("leaf.txt");
  }

  @Test
  public void testReadDirectoryWithManyEntries() throws Exception {
    // Enough entries to need more than one FindNextFileW batch. A listing that stopped early
    // would show up here, and nowhere else.
    testUtil.scratchDir("many");
    for (int i = 0; i < 2000; i++) {
      testUtil.scratchFile("many/entry-" + i, "x");
    }

    WindowsFileOperations.Dirents entries =
        WindowsFileOperations.readDirectory(new File(scratchRoot, "many").toString());

    assertThat(entries.names()).hasLength(2000);
    assertThat(entries.attributes()).hasLength(2000);
  }
}
