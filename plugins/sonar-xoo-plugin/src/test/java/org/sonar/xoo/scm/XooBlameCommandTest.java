/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.xoo.scm;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.scm.BlameCommand.BlameInput;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.DateUtils;
import org.sonar.xoo.Xoo;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class XooBlameCommandTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private DefaultFileSystem fs;
  private File baseDir;
  private BlameInput input;

  @Before
  public void prepare() throws IOException {
    baseDir = temp.newFolder();
    fs = new DefaultFileSystem();
    input = mock(BlameInput.class);
    when(input.fileSystem()).thenReturn(fs);
  }

  @Test
  public void testBlame() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    File scm = new File(baseDir, "src/foo.xoo.scm");
    FileUtils.write(scm, "123,julien,2014-12-12\n234,julien,2014-12-24");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath()).setLanguage(Xoo.KEY);
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));
    new XooBlameCommand().blame(input, result);
    verify(result).blameResult(inputFile, Arrays.asList(
      new BlameLine().revision("123").author("julien").date(DateUtils.parseDate("2014-12-12")),
      new BlameLine().revision("234").author("julien").date(DateUtils.parseDate("2014-12-24"))));
  }
}
