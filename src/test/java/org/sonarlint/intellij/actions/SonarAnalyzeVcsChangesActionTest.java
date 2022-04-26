/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2022 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.vcs.LocalFilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.FakeRevision;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.sonarlint.intellij.AbstractSonarLintLightTests;
import org.sonarlint.intellij.SonarLintMockProject;
import org.sonarlint.intellij.analysis.AnalysisCallback;
import org.sonarlint.intellij.analysis.AnalysisStatus;
import org.sonarlint.intellij.trigger.SonarLintSubmitter;
import org.sonarlint.intellij.trigger.TriggerType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SonarAnalyzeVcsChangesActionTest extends AbstractSonarLintLightTests {
  private SonarLintSubmitter submitter = mock(SonarLintSubmitter.class);
  private AnActionEvent event = mock(AnActionEvent.class);

  private Presentation presentation = new Presentation();
  private SonarAnalyzeVcsChangesAction editorFileAction = new SonarAnalyzeVcsChangesAction();

  @Before
  public void prepare() {
    replaceProjectService(SonarLintSubmitter.class, submitter);
    when(event.getProject()).thenReturn(getProject());
    when(event.getPresentation()).thenReturn(presentation);
  }

  @Test
  public void should_submit_file() {
    VirtualFile f1 = myFixture.copyFileToProject("src/foo.php", "foo.php");
    mockSelectedFiles(f1);
    editorFileAction.actionPerformed(event);
    verify(submitter).submitFiles(anyCollection(), eq(TriggerType.ACTION), any(AnalysisCallback.class), eq(false));
  }

  @Test
  public void should_submit_folder() {
    VirtualFile f1 = myFixture.copyDirectoryToProject("src", "src");
    mockSelectedFiles(f1);
    editorFileAction.actionPerformed(event);
    verify(submitter).submitFiles(anyCollection(), eq(TriggerType.ACTION), any(AnalysisCallback.class), eq(false));
  }

  private void mockSelectedFiles(VirtualFile file) {
    Change[] changes = new Change[1];
    changes[0] = new MockChange(file);
    when(event.getData(VcsDataKeys.CHANGES)).thenReturn(changes);
  }

  @Test
  public void should_do_nothing_if_no_change() {
    editorFileAction.actionPerformed(event);
    verifyZeroInteractions(submitter);
  }

  @Test
  public void should_do_nothing_if_no_file() {
    when(event.getData(VcsDataKeys.CHANGES)).thenReturn(new Change[0]);
    editorFileAction.actionPerformed(event);
    verifyZeroInteractions(submitter);
  }

  @Test
  public void should_do_nothing_if_no_project() {
    when(event.getProject()).thenReturn(null);
    editorFileAction.actionPerformed(event);
    verifyZeroInteractions(submitter);
  }

  @Test
  public void should_be_hidden_if_commit_has_no_changes() {
    when(event.getData(VcsDataKeys.CHANGES)).thenReturn(null);

    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();
    assertThat(presentation.isVisible()).isFalse();
  }

  @Test
  public void should_be_hidden_if_changes_contain_no_files() {
    // if the file of the selected commit has been moved or deleted,
    // the change won't contain files
    // when(event.getData(VcsDataKeys.CHANGES)).thenReturn(new Change[0]);
    mockSelectedFiles(null);

    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();
    assertThat(presentation.isVisible()).isFalse();
  }

  @Test
  public void should_be_disabled_if_no_project() {
    VirtualFile f1 = myFixture.copyFileToProject("src/foo.php", "foo.php");
    mockSelectedFiles(f1);
    when(event.getProject()).thenReturn(null);

    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();
  }

  @Test
  public void should_be_disabled_if_project_not_initialized() {
    VirtualFile f1 = myFixture.copyFileToProject("src/foo.php", "foo.php");
    mockSelectedFiles(f1);
    when(event.getProject()).thenReturn(createProject(false, false));
    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();
  }

  @Test
  public void should_be_disabled_if_project_is_disposed() {
    VirtualFile f1 = myFixture.copyFileToProject("src/foo.php", "foo.php");
    mockSelectedFiles(f1);
    when(event.getProject()).thenReturn(createProject(true, true));
    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();
  }

  @Test
  public void should_be_enabled_if_file_and_not_running() {
    VirtualFile f1 = myFixture.copyFileToProject("src/foo.php", "foo.php");
    mockSelectedFiles(f1);

    AnalysisStatus.get(getProject()).tryRun();

    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isFalse();

    AnalysisStatus.get(getProject()).stopRun();
    editorFileAction.update(event);
    assertThat(presentation.isEnabled()).isTrue();
  }

  private SonarLintMockProject createProject(boolean initialized, boolean disposed) {
    SonarLintMockProject project = new SonarLintMockProject(null, getProject());
    project.setInitialized(initialized);
    project.setDisposed(disposed);
    return project;
  }

  private static class MockChange extends Change {

    private final VirtualFile file;

    public MockChange(VirtualFile file) {
      super(null, new FakeRevision(new LocalFilePath("fake/path", false)));
      this.file = file;
    }

    @Override
    public @Nullable VirtualFile getVirtualFile() {
      return file;
    }
  }
}
