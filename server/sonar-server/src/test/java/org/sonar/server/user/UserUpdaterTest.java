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

package org.sonar.server.user;

import com.google.common.base.Strings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.api.platform.NewUserHandler;
import org.sonar.api.utils.System2;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.core.user.GroupDto;
import org.sonar.core.user.GroupMembershipDao;
import org.sonar.core.user.GroupMembershipQuery;
import org.sonar.core.user.UserDto;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.Message;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.db.GroupDao;
import org.sonar.server.user.db.UserDao;
import org.sonar.server.user.db.UserGroupDao;
import org.sonar.server.util.Validation;
import org.sonar.test.DbTests;

import static com.google.common.collect.Lists.newArrayList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Category(DbTests.class)
@RunWith(MockitoJUnitRunner.class)
public class UserUpdaterTest {

  @Rule
  public DbTester db = new DbTester();

  @Mock
  System2 system2;

  @Mock
  NewUserNotifier newUserNotifier;

  @Captor
  ArgumentCaptor<NewUserHandler.Context> newUserHandler;

  Settings settings;
  UserDao userDao;
  GroupDao groupDao;
  GroupMembershipFinder groupMembershipFinder;
  DbSession session;

  UserUpdater userUpdater;

  @Before
  public void setUp() throws Exception {
    settings = new Settings();
    session = db.myBatis().openSession(false);
    userDao = new UserDao(db.myBatis(), system2);
    groupDao = new GroupDao(system2);
    UserGroupDao userGroupDao = new UserGroupDao();
    GroupMembershipDao groupMembershipDao = new GroupMembershipDao(db.myBatis());
    groupMembershipFinder = new GroupMembershipFinder(userDao, groupMembershipDao);

    userUpdater = new UserUpdater(newUserNotifier, settings, userGroupDao, new DbClient(db.database(), db.myBatis(), userDao, groupDao), system2);
  }

  @After
  public void tearDown() throws Exception {
    session.close();
  }

  @Test
  public void create_user() throws Exception {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    boolean result = userUpdater.create(NewUser.create()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setPasswordConfirmation("password")
      .setScmAccounts(newArrayList("u1", "u_1")));

    UserDto dto = userDao.selectNullableByLogin(session, "user");
    assertThat(dto.getId()).isNotNull();
    assertThat(dto.getLogin()).isEqualTo("user");
    assertThat(dto.getName()).isEqualTo("User");
    assertThat(dto.getEmail()).isEqualTo("user@mail.com");
    assertThat(dto.getScmAccounts()).contains("u1,u_1");
    assertThat(dto.isActive()).isTrue();

    assertThat(dto.getSalt()).isNotNull();
    assertThat(dto.getCryptedPassword()).isNotNull();
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735482L);
    assertThat(result).isFalse();
  }

  @Test
  public void create_user_with_minimum_fields() throws Exception {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    userUpdater.create(NewUser.create()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .setPasswordConfirmation("password"));

    assertThat(userDao.selectNullableByLogin(session, "user")).isNotNull();
  }

  @Test
  public void create_user_with_scm_accounts_containing_blank_entry() throws Exception {
    when(system2.now()).thenReturn(1418215735482L);
    createDefaultGroup();

    userUpdater.create(NewUser.create()
      .setLogin("user")
      .setName("User")
      .setPassword("password")
      .setPasswordConfirmation("password")
      .setScmAccounts(newArrayList("u1", "", null)));

    assertThat(userDao.selectNullableByLogin(session, "user").getScmAccounts()).doesNotContain(",");
  }

  @Test
  public void fail_to_create_user_with_missing_login() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin(null)
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.CANT_BE_EMPTY_MESSAGE, "Login"));
    }
  }

  @Test
  public void fail_to_create_user_with_invalid_login() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("/marius/")
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of("user.bad_login"));
    }
  }

  @Test
  public void fail_to_create_user_with_too_short_login() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("ma")
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.IS_TOO_SHORT_MESSAGE, "Login", 2));
    }
  }

  @Test
  public void fail_to_create_user_with_too_long_login() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin(Strings.repeat("m", 256))
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.IS_TOO_LONG_MESSAGE, "Login", 255));
    }
  }

  @Test
  public void fail_to_create_user_with_missing_name() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName(null)
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.CANT_BE_EMPTY_MESSAGE, "Name"));
    }
  }

  @Test
  public void fail_to_create_user_with_too_long_name() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName(Strings.repeat("m", 201))
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.IS_TOO_LONG_MESSAGE, "Name", 200));
    }
  }

  @Test
  public void fail_to_create_user_with_too_long_email() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName("Marius")
        .setEmail(Strings.repeat("m", 101))
        .setPassword("password")
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of(Validation.IS_TOO_LONG_MESSAGE, "Email", 100));
    }
  }

  @Test
  public void fail_to_create_user_with_missing_password() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword(null)
        .setPasswordConfirmation("password"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).contains(Message.of(Validation.CANT_BE_EMPTY_MESSAGE, "Password"));
    }
  }

  @Test
  public void fail_to_create_user_with_missing_password_confirmation() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation(null));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).contains(Message.of(Validation.CANT_BE_EMPTY_MESSAGE, "Password confirmation"));
    }
  }

  @Test
  public void fail_to_create_user_with_password_not_matching_password_confirmation() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName("Marius")
        .setEmail("marius@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password2"));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).containsOnly(Message.of("user.password_doesnt_match_confirmation"));
    }
  }

  @Test
  public void fail_to_create_user_with_many_errors() throws Exception {
    try {
      userUpdater.create(NewUser.create()
        .setLogin("")
        .setName("")
        .setEmail("marius@mail.com")
        .setPassword("")
        .setPasswordConfirmation(""));
      fail();
    } catch (BadRequestException e) {
      assertThat(e.errors().messages()).hasSize(4);
    }
  }

  @Test
  public void notify_new_user() throws Exception {
    createDefaultGroup();

    userUpdater.create(NewUser.create()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setPasswordConfirmation("password")
      .setScmAccounts(newArrayList("u1", "u_1")));

    verify(newUserNotifier).onNewUser(newUserHandler.capture());
    assertThat(newUserHandler.getValue().getLogin()).isEqualTo("user");
    assertThat(newUserHandler.getValue().getName()).isEqualTo("User");
    assertThat(newUserHandler.getValue().getEmail()).isEqualTo("user@mail.com");
  }

  @Test
  public void associate_default_groups_when_creating_user() throws Exception {
    createDefaultGroup();

    userUpdater.create(NewUser.create()
      .setLogin("user")
      .setName("User")
      .setEmail("user@mail.com")
      .setPassword("password")
      .setPasswordConfirmation("password")
      .setScmAccounts(newArrayList("u1", "u_1")));

    GroupMembershipFinder.Membership membership = groupMembershipFinder.find(GroupMembershipQuery.builder().login("user").build());
    assertThat(membership.groups()).hasSize(1);
    assertThat(membership.groups().get(0).name()).isEqualTo("sonar-users");
    assertThat(membership.groups().get(0).isMember()).isTrue();
  }

  @Test
  public void fail_to_associate_default_groups_to_user_if_no_default_group() throws Exception {
    settings.setProperty(CoreProperties.CORE_DEFAULT_GROUP, (String) null);

    try {
      userUpdater.create(NewUser.create()
        .setLogin("user")
        .setName("User")
        .setEmail("user@mail.com")
        .setPassword("password")
        .setPasswordConfirmation("password")
        .setScmAccounts(newArrayList("u1", "u_1")));
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("The default group property 'sonar.defaultGroup' is null");
    }
  }

  @Test
  public void reactivate_user_when_creating_user_with_existing_login() throws Exception {
    db.prepareDbUnit(getClass(), "reactivate_user.xml");
    when(system2.now()).thenReturn(1418215735486L);
    createDefaultGroup();

    boolean result = userUpdater.create(NewUser.create()
      .setLogin("marius")
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setPasswordConfirmation("password2"));
    session.commit();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccounts()).isNull();

    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735486L);

    assertThat(result).isTrue();
  }

  @Test
  public void fail_to_reactivate_user_if_not_disabled() throws Exception {
    db.prepareDbUnit(getClass(), "fail_to_reactivate_user_if_not_disabled.xml");
    createDefaultGroup();

    try {
      userUpdater.create(NewUser.create()
        .setLogin("marius")
        .setName("Marius2")
        .setEmail("marius2@mail.com")
        .setPassword("password2")
        .setPasswordConfirmation("password2"));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("An active user with login 'marius' already exists");
    }
  }

  @Test
  public void associate_default_groups_when_reactivating_user() throws Exception {
    db.prepareDbUnit(getClass(), "associate_default_groups_when_reactivating_user.xml");
    createDefaultGroup();

    userUpdater.create(NewUser.create()
      .setLogin("marius")
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setPasswordConfirmation("password2"));
    session.commit();

    GroupMembershipFinder.Membership membership = groupMembershipFinder.find(GroupMembershipQuery.builder().login("marius").groupSearch("sonar-users").build());
    assertThat(membership.groups()).hasSize(1);
    assertThat(membership.groups().get(0).name()).isEqualTo("sonar-users");
    assertThat(membership.groups().get(0).isMember()).isTrue();
  }

  @Test
  public void update_user() throws Exception {
    db.prepareDbUnit(getClass(), "update_user.xml");
    when(system2.now()).thenReturn(1418215735486L);
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setPasswordConfirmation("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();
    session.clearCache();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.isActive()).isTrue();
    assertThat(dto.getName()).isEqualTo("Marius2");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");
    assertThat(dto.getScmAccounts()).contains("ma2");

    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
    assertThat(dto.getCreatedAt()).isEqualTo(1418215735482L);
    assertThat(dto.getUpdatedAt()).isEqualTo(1418215735486L);
  }

  @Test
  public void update_only_user_name() throws Exception {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setName("Marius2"));
    session.commit();
    session.clearCache();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.getName()).isEqualTo("Marius2");

    // Following fields has not changed
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
    assertThat(dto.getScmAccounts()).contains("ma,marius33");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_only_user_email() throws Exception {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setEmail("marius2@mail.com"));
    session.commit();
    session.clearCache();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.getEmail()).isEqualTo("marius2@mail.com");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getScmAccounts()).contains("ma,marius33");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_only_scm_accounts_email() throws Exception {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();
    session.clearCache();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.getScmAccounts()).contains("ma2");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
    assertThat(dto.getSalt()).isEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");
  }

  @Test
  public void update_only_user_password() throws Exception {
    db.prepareDbUnit(getClass(), "update_user.xml");
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setPassword("password2")
      .setPasswordConfirmation("password2"));
    session.commit();
    session.clearCache();

    UserDto dto = userDao.selectNullableByLogin(session, "marius");
    assertThat(dto.getSalt()).isNotEqualTo("79bd6a8e79fb8c76ac8b121cc7e8e11ad1af8365");
    assertThat(dto.getCryptedPassword()).isNotEqualTo("650d2261c98361e2f67f90ce5c65a95e7d8ea2fg");

    // Following fields has not changed
    assertThat(dto.getName()).isEqualTo("Marius");
    assertThat(dto.getScmAccounts()).contains("ma,marius33");
    assertThat(dto.getEmail()).isEqualTo("marius@lesbronzes.fr");
  }

  @Test
  public void associate_default_groups_when_updating_user() throws Exception {
    db.prepareDbUnit(getClass(), "associate_default_groups_when_updating_user.xml");
    createDefaultGroup();

    userUpdater.update(UpdateUser.create("marius")
      .setName("Marius2")
      .setEmail("marius2@mail.com")
      .setPassword("password2")
      .setPasswordConfirmation("password2")
      .setScmAccounts(newArrayList("ma2")));
    session.commit();

    GroupMembershipFinder.Membership membership = groupMembershipFinder.find(GroupMembershipQuery.builder().login("marius").groupSearch("sonar-users").build());
    assertThat(membership.groups()).hasSize(1);
    assertThat(membership.groups().get(0).name()).isEqualTo("sonar-users");
    assertThat(membership.groups().get(0).isMember()).isTrue();
  }

  @Test
  public void fail_to_update_unknown_user() throws Exception {
    try {
      userUpdater.update(UpdateUser.create("marius")
        .setName("Marius2")
        .setEmail("marius2@mail.com")
        .setPassword("password2")
        .setPasswordConfirmation("password2")
        .setScmAccounts(newArrayList("ma2")));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(NotFoundException.class).hasMessage("User 'marius' does not exists");
    }
  }

  private void createDefaultGroup() {
    settings.setProperty(CoreProperties.CORE_DEFAULT_GROUP, "sonar-users");
    groupDao.insert(session, new GroupDto().setName("sonar-users").setDescription("Sonar Users"));
    session.commit();
  }
}
