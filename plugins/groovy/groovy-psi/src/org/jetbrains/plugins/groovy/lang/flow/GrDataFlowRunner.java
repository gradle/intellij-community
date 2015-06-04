/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.lang.flow;

import com.intellij.codeInspection.dataFlow.AbstractDataFlowRunner;
import com.intellij.codeInspection.dataFlow.DfaMemoryState;
import com.intellij.codeInspection.dataFlow.InstructionVisitor;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.flow.value.GrDfaValueFactory;

import java.util.Collection;
import java.util.Collections;

public class GrDataFlowRunner extends AbstractDataFlowRunner {

  private final GrDfaValueFactory myValueFactory;

  public GrDataFlowRunner(boolean honorFieldInitializers, boolean unknownMembersAreNullable) {
    myValueFactory = new GrDfaValueFactory(honorFieldInitializers, unknownMembersAreNullable);
  }

  @Nullable
  @Override
  protected Collection<DfaMemoryState> createInitialStates(@NotNull PsiElement psiBlock, @NotNull InstructionVisitor visitor) {
    return Collections.singletonList(createMemoryState());
  }

  @NotNull
  @Override
  protected DfaMemoryState createMemoryState() {
    return new GrDfaMemoryState(myValueFactory);
  }

  @NotNull
  @Override
  public GrDfaValueFactory getFactory() {
    return myValueFactory;
  }

  @NotNull
  @Override
  protected GrControlFlowAnalyzerImpl createControlFlowAnalyzer(@NotNull PsiElement block) {
    return new GrControlFlowAnalyzerImpl(myValueFactory, block);
  }
}
