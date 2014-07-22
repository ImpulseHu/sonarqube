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
package org.sonar.batch.mediumtest.xoo.plugin.rule;

import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.batch.mediumtest.xoo.plugin.base.Xoo;
import org.sonar.batch.mediumtest.xoo.plugin.base.XooConstants;

public class CreateIssueByInternalKeySensor implements Sensor {

  private static final String INTERNAL_KEY_PROPERTY = "sonar.xoo.internalKey";

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("CreateIssueByInternalKeySensor")
      .workOnLanguages(Xoo.KEY)
      .workOnFileTypes(InputFile.Type.MAIN, InputFile.Type.TEST);
  }

  @Override
  public void execute(SensorContext context) {
    for (InputFile file : context.fileSystem().inputFiles(context.fileSystem().predicates().hasLanguages(Xoo.KEY))) {
      createIssues(file, context);
    }
  }

  private void createIssues(InputFile file, SensorContext context) {
    ActiveRule rule = context.activeRules().findByInternalKey(XooConstants.REPOSITORY_KEY,
      context.settings().getString(INTERNAL_KEY_PROPERTY));
    if (rule != null) {
      context.addIssue(context.issueBuilder()
        .ruleKey(rule.ruleKey())
        .onFile(file)
        .message("This issue is generated on each file")
        .build());
    }
  }
}
