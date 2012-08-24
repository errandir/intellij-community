/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.designer.componentTree;

import com.intellij.ide.util.treeView.NodeDescriptor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * @author Alexander Lobas
 */
public final class TreeNodeDescriptor extends NodeDescriptor {
  private final Object myElement;

  public TreeNodeDescriptor(@Nullable NodeDescriptor parentDescriptor, Object element) {
    super(null, parentDescriptor);
    myElement = element;
  }

  public TreeNodeDescriptor(@Nullable NodeDescriptor parentDescriptor, Object element, String name) {
    this(parentDescriptor, element);
    myName = name;
  }

  @Override
  public boolean update() {
    return false;
  }

  @Override
  public Object getElement() {
    return myElement;
  }

  public void setIcon(Icon icon) {
    myIcon = icon;
  }
}
