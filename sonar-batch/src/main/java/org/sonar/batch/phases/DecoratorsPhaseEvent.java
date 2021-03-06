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
package org.sonar.batch.phases;

import org.sonar.api.batch.Decorator;
import org.sonar.api.batch.events.DecoratorsPhaseHandler;

import java.util.List;

class DecoratorsPhaseEvent extends AbstractPhaseEvent<DecoratorsPhaseHandler>
    implements org.sonar.api.batch.events.DecoratorsPhaseHandler.DecoratorsPhaseEvent {

  private List<Decorator> decorators;

  DecoratorsPhaseEvent(List<Decorator> decorators, boolean start) {
    super(start);
    this.decorators = decorators;
  }

  @Override
  public List<Decorator> getDecorators() {
    return decorators;
  }

  @Override
  protected void dispatch(DecoratorsPhaseHandler handler) {
    handler.onDecoratorsPhase(this);
  }

  @Override
  protected Class getType() {
    return DecoratorsPhaseHandler.class;
  }

}
