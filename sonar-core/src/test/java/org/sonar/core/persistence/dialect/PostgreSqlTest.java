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
package org.sonar.core.persistence.dialect;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class PostgreSqlTest {

  PostgreSql dialect = new PostgreSql();

  @Test
  public void matchesJdbcURL() {
    assertThat(dialect.matchesJdbcURL("jdbc:postgresql://localhost/sonar")).isTrue();
    assertThat(dialect.matchesJdbcURL("jdbc:hsql:foo")).isFalse();
  }

  @Test
  public void should_set_connection_properties() {
    assertThat(dialect.getConnectionInitStatements()).isEqualTo(PostgreSql.INIT_STATEMENTS);
  }


  @Test
  public void testBooleanSqlValues() {
    assertThat(dialect.getTrueSqlValue()).isEqualTo("true");
    assertThat(dialect.getFalseSqlValue()).isEqualTo("false");
  }

  @Test
  public void should_configure() {
    assertThat(dialect.getId()).isEqualTo("postgresql");
    assertThat(dialect.getActiveRecordDialectCode()).isEqualTo("postgre");
    assertThat(dialect.getDefaultDriverClassName()).isEqualTo("org.postgresql.Driver");
    assertThat(dialect.getValidationQuery()).isEqualTo("SELECT 1");
  }

  @Test
  public void testFetchSizeForScrolling() throws Exception {
    assertThat(dialect.getScrollDefaultFetchSize()).isEqualTo(200);
  }
}
