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
package org.sonar.server.issue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.issue.DefaultTransitions;
import org.sonar.api.issue.Issue;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.Severity;
import org.sonar.api.security.DefaultGroups;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.issue.db.ActionPlanDto;
import org.sonar.core.issue.db.IssueDto;
import org.sonar.core.issue.workflow.Transition;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.permission.PermissionFacade;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.user.UserDto;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.component.SnapshotTesting;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.component.db.SnapshotDao;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.issue.db.IssueDao;
import org.sonar.server.issue.index.IssueDoc;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.permission.InternalPermissionService;
import org.sonar.server.permission.PermissionChange;
import org.sonar.server.rule.RuleTesting;
import org.sonar.server.rule.db.RuleDao;
import org.sonar.server.search.IndexClient;
import org.sonar.server.search.QueryContext;
import org.sonar.server.tester.ServerTester;
import org.sonar.server.user.MockUserSession;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

public class IssueServiceMediumTest {

  @ClassRule
  public static ServerTester tester = new ServerTester();

  DbClient db;
  IndexClient indexClient;
  DbSession session;
  IssueService service;

  RuleDto rule;
  ComponentDto project;
  ComponentDto file;
  UserDto connectedUser;

  @Before
  public void setUp() throws Exception {
    tester.clearDbAndIndexes();
    db = tester.get(DbClient.class);
    indexClient = tester.get(IndexClient.class);
    session = db.openSession(false);
    service = tester.get(IssueService.class);

    rule = RuleTesting.newXooX1();
    tester.get(RuleDao.class).insert(session, rule);

    project = ComponentTesting.newProjectDto();
    tester.get(ComponentDao.class).insert(session, project);
    SnapshotDto projectSnapshot = SnapshotTesting.createForProject(project);
    tester.get(SnapshotDao.class).insert(session, projectSnapshot);

    file = ComponentTesting.newFileDto(project);
    tester.get(ComponentDao.class).insert(session, file);
    tester.get(SnapshotDao.class).insert(session, SnapshotTesting.createForComponent(file, projectSnapshot));


    // workaround for the test to have sufficient privileges
    connectedUser = new UserDto().setLogin("gandalf").setName("Gandalf");
    db.userDao().insert(session, connectedUser);
    tester.get(PermissionFacade.class).insertUserPermission(project.getId(), connectedUser.getId(), UserRole.USER, session);
    MockUserSession.set()
      .setLogin(connectedUser.getLogin())
      .setUserId(connectedUser.getId().intValue())
      .setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN)
      .addProjectPermissions(UserRole.USER, project.key())
      .addProjectPermissions(UserRole.ISSUE_ADMIN, project.key());
    session.commit();

    // project can be seen by group "anyone"
    tester.get(InternalPermissionService.class).addPermission(new PermissionChange().setComponentKey(project.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.USER));
  }

  @After
  public void after() {
    session.close();
  }

  private void index() {
    tester.get(IssueIndexer.class).indexAll();
  }

  @Test
  public void get_by_key() throws Exception {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    assertThat(service.getByKey(issue.getKey())).isNotNull();
  }

  @Test
  public void can_facet() throws Exception {
    IssueDto issue1 = newIssue().setActionPlanKey("P1");
    IssueDto issue2 = newIssue().setActionPlanKey("P2").setResolution("NONE");
    tester.get(IssueDao.class).insert(session, issue1, issue2);
    session.commit();
    index();

    org.sonar.server.search.Result<Issue> result = service.search(IssueQuery.builder().build(), new QueryContext());
    assertThat(result.getHits()).hasSize(2);
    assertThat(result.getFacets()).isEmpty();

    result = service.search(IssueQuery.builder().build(), new QueryContext().addFacets(Arrays.asList("actionPlans", "assignees")));
    assertThat(result.getFacets().keySet()).hasSize(2);
    assertThat(result.getFacetKeys("actionPlans")).hasSize(2);
    assertThat(result.getFacetKeys("assignees")).hasSize(1);
  }

  @Test
  public void list_status() {
    assertThat(service.listStatus()).containsExactly("OPEN", "CONFIRMED", "REOPENED", "RESOLVED", "CLOSED");
  }

  @Test
  public void list_transitions() {
    IssueDto issue = newIssue().setStatus(Issue.STATUS_RESOLVED).setResolution(Issue.RESOLUTION_FALSE_POSITIVE);
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    List<Transition> result = service.listTransitions(issue.getKey());
    assertThat(result).hasSize(1);
    assertThat(result.get(0).key()).isEqualTo("reopen");
  }

  @Test
  public void do_transition() {
    IssueDto issue = newIssue().setStatus(Issue.STATUS_OPEN);
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    assertThat(db.issueDao().selectByKey(session, issue.getKey())).isNotNull();
    IssueTesting.assertIsEquivalent(issue, (IssueDoc) indexClient.get(IssueIndex.class).getByKey(issue.getKey()));

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).status()).isEqualTo(Issue.STATUS_OPEN);

    service.doTransition(issue.getKey(), DefaultTransitions.CONFIRM);

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).status()).isEqualTo(Issue.STATUS_CONFIRMED);
  }

  @Test
  public void assign() {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);

    UserDto user = new UserDto().setLogin("perceval").setName("Perceval");
    db.userDao().insert(session, user);
    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).assignee()).isNull();

    service.assign(issue.getKey(), user.getLogin());

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).assignee()).isEqualTo("perceval");
  }

  @Test
  public void unassign() {
    IssueDto issue = newIssue().setAssignee("perceval");
    tester.get(IssueDao.class).insert(session, issue);

    UserDto user = new UserDto().setLogin("perceval").setName("Perceval");
    db.userDao().insert(session, user);
    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).assignee()).isEqualTo("perceval");

    service.assign(issue.getKey(), "");

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).assignee()).isNull();
  }

  @Test
  public void fail_to_assign_on_unknown_user() {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    try {
      service.assign(issue.getKey(), "unknown");
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(NotFoundException.class).hasMessage("Unknown user: unknown");
    }
  }

  @Test
  public void plan() {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);

    String actionPlanKey = "EFGH";
    db.actionPlanDao().save(new ActionPlanDto().setKey(actionPlanKey).setProjectId(project.getId()));
    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).actionPlanKey()).isNull();

    service.plan(issue.getKey(), actionPlanKey);

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).actionPlanKey()).isEqualTo(actionPlanKey);
  }

  @Test
  public void un_plan() {
    String actionPlanKey = "EFGH";
    db.actionPlanDao().save(new ActionPlanDto().setKey(actionPlanKey).setProjectId(project.getId()));

    IssueDto issue = newIssue().setActionPlanKey(actionPlanKey);
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).actionPlanKey()).isEqualTo(actionPlanKey);

    service.plan(issue.getKey(), null);

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).actionPlanKey()).isNull();
  }

  @Test
  public void fail_plan_if_action_plan_not_found() {
    tester.get(IssueDao.class).insert(session, newIssue());
    session.commit();
    index();

    try {
      service.plan("ABCD", "unknown");
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(NotFoundException.class).hasMessage("Unknown action plan: unknown");
    }
  }

  @Test
  public void set_severity() {
    IssueDto issue = newIssue().setSeverity(Severity.BLOCKER);
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).severity()).isEqualTo(Severity.BLOCKER);

    service.setSeverity(issue.getKey(), Severity.MINOR);

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).severity()).isEqualTo(Severity.MINOR);
  }

  @Test
  public void create_manual_issue() {
    RuleDto manualRule = RuleTesting.newManualRule("manualRuleKey");
    tester.get(RuleDao.class).insert(session, manualRule);
    session.commit();

    Issue result = service.createManualIssue(file.key(), manualRule.getKey(), 10, "Fix it", Severity.MINOR, 2d);

    IssueDoc manualIssue = (IssueDoc) indexClient.get(IssueIndex.class).getByKey(result.key());
    assertThat(manualIssue.componentUuid()).isEqualTo(file.uuid());
    assertThat(manualIssue.projectUuid()).isEqualTo(project.uuid());
    assertThat(manualIssue.ruleKey()).isEqualTo(manualRule.getKey());
    assertThat(manualIssue.message()).isEqualTo("Fix it");
    assertThat(manualIssue.line()).isEqualTo(10);
    assertThat(manualIssue.severity()).isEqualTo(Severity.MINOR);
    assertThat(manualIssue.effortToFix()).isEqualTo(2d);
    assertThat(manualIssue.reporter()).isEqualTo(connectedUser.getLogin());
  }

  @Test
  public void create_manual_issue_with_major_severity_when_no_severity() {
    RuleDto manualRule = RuleTesting.newManualRule("manualRuleKey");
    tester.get(RuleDao.class).insert(session, manualRule);
    session.commit();

    Issue result = service.createManualIssue(file.key(), manualRule.getKey(), 10, "Fix it", null, 2d);

    Issue manualIssue = indexClient.get(IssueIndex.class).getByKey(result.key());
    assertThat(manualIssue.severity()).isEqualTo(Severity.MAJOR);
  }

  @Test
  public void create_manual_issue_with_rule_name_when_no_message() {
    RuleDto manualRule = RuleTesting.newManualRule("manualRuleKey").setName("Manual rule name");
    tester.get(RuleDao.class).insert(session, manualRule);
    session.commit();

    Issue result = service.createManualIssue(file.key(), manualRule.getKey(), 10, null, null, 2d);

    Issue manualIssue = indexClient.get(IssueIndex.class).getByKey(result.key());
    assertThat(manualIssue.message()).isEqualTo("Manual rule name");
  }

  @Test
  public void fail_create_manual_issue_on_not_manual_rule() {
    try {
      service.createManualIssue(file.key(), rule.getKey(), 10, "Fix it", null, 2d);
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("Issues can be created only on rules marked as 'manual': xoo:x1");
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void fail_create_manual_issue_if_rule_does_not_exists() {
    service.createManualIssue(file.key(), RuleKey.of("rule", "unknown"), 10, "Fix it", null, 2d);
  }

  @Test(expected = ForbiddenException.class)
  public void fail_create_manual_issue_if_not_having_required_role() {
    // User has not the 'user' role on the project
    MockUserSession.set()
      .setLogin(connectedUser.getLogin())
      .setUserId(connectedUser.getId().intValue())
      .setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN)
      .addProjectPermissions(UserRole.CODEVIEWER, project.key());

    RuleDto manualRule = RuleTesting.newManualRule("manualRuleKey");
    tester.get(RuleDao.class).insert(session, manualRule);
    session.commit();

    service.createManualIssue(file.key(), rule.getKey(), 10, "Fix it", null, 2d);
  }

  @Test
  public void find_rules_by_component() throws Exception {
    // 2 issues on the same rule
    tester.get(IssueDao.class).insert(session, newIssue().setRule(rule));
    tester.get(IssueDao.class).insert(session, newIssue().setRule(rule));
    session.commit();

    RulesAggregation result = service.findRulesByComponent(file.key(), null, session);
    assertThat(result.rules()).hasSize(1);
  }

  @Test
  public void find_rules_by_severity() throws Exception {
    tester.get(IssueDao.class).insert(session, newIssue().setSeverity(Severity.MAJOR));
    tester.get(IssueDao.class).insert(session, newIssue().setSeverity(Severity.MAJOR));
    tester.get(IssueDao.class).insert(session, newIssue().setSeverity(Severity.INFO));
    session.commit();

    Multiset<String> result = service.findSeveritiesByComponent(file.key(), null, session);
    assertThat(result.count("MAJOR")).isEqualTo(2);
    assertThat(result.count("INFO")).isEqualTo(1);
    assertThat(result.count("UNKNOWN")).isEqualTo(0);
  }

  @Test
  public void search_issues() {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    index();

    List<Issue> result = service.search(IssueQuery.builder().build(), new QueryContext()).getHits();
    assertThat(result).hasSize(1);
  }

  @Test
  public void find_issue_assignees() throws Exception {
    db.issueDao().insert(session,
      IssueTesting.newDto(rule, file, project).setAssignee("steph"),
      IssueTesting.newDto(rule, file, project).setAssignee("simon"),
      IssueTesting.newDto(rule, file, project),
      IssueTesting.newDto(rule, file, project).setAssignee("steph"));
    session.commit();
    index();

    Map<String, Long> results = service.findIssueAssignees(IssueQuery.builder().build());

    assertThat(results).hasSize(3);
    assertThat(results.get("steph")).isEqualTo(2L);
    assertThat(results.get("simon")).isEqualTo(1L);
    assertThat(results.get(null)).isEqualTo(1L);

    assertThat(results.keySet().toArray()[0]).isEqualTo("steph");
    assertThat(results.keySet().toArray()[1]).isEqualTo("simon");
    assertThat(results.keySet().toArray()[2]).isNull();
  }

  private IssueDto newIssue() {
    return IssueTesting.newDto(rule, file, project);
  }

  @Test
  public void list_tags() {
    db.issueDao().insert(session,
      IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention", "java8", "bug")),
      IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention", "bug")),
      IssueTesting.newDto(rule, file, project),
      IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention")));
    session.commit();
    index();

    assertThat(service.listTags(null, 5)).containsOnly("convention", "java8", "bug");
    assertThat(service.listTags(null, 2)).containsOnly("bug", "convention");
    assertThat(service.listTags("vent", 5)).containsOnly("convention");
    assertThat(service.listTags(null, 1)).containsOnly("bug");
    assertThat(service.listTags(null, Integer.MAX_VALUE)).containsOnly("convention", "java8", "bug");
  }

  @Test
  public void set_tags() {
    IssueDto issue = newIssue();
    tester.get(IssueDao.class).insert(session, issue);

    session.commit();
    index();

    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).isEmpty();

    // Tags are lowercased
    service.setTags(issue.getKey(), ImmutableSet.of("bug", "Convention"));
    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).containsOnly("bug", "convention");

    // nulls and empty tags are ignored
    service.setTags(issue.getKey(), Sets.newHashSet("security", null, "", "convention"));
    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).containsOnly("security", "convention");

    // tag validation
    try {
      service.setTags(issue.getKey(), ImmutableSet.of("pol op"));
    } catch (Exception exception) {
      assertThat(exception).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).containsOnly("security", "convention");

    // unchanged tags
    service.setTags(issue.getKey(), ImmutableSet.of("convention", "security"));
    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).containsOnly("security", "convention");

    service.setTags(issue.getKey(), ImmutableSet.<String>of());
    assertThat(indexClient.get(IssueIndex.class).getByKey(issue.getKey()).tags()).isEmpty();
  }
}
