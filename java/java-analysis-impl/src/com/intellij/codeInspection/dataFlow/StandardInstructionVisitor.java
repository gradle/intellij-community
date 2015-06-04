/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.instructions.*;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.codeInspection.dataFlow.value.java.DfaValueFactoryJava;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FactoryMap;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.codeInspection.dataFlow.value.DfaRelation.*;

/**
 * @author peter
 */
public class StandardInstructionVisitor extends JavaInstructionVisitor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.dataFlow.StandardInstructionVisitor");
  private static final Object ANY_VALUE = new Object();
  private final Set<BinopInstruction> myReachable = new THashSet<BinopInstruction>();
  private final Set<BinopInstruction> myCanBeNullInInstanceof = new THashSet<BinopInstruction>();
  private final MultiMap<PushInstruction, Object> myPossibleVariableValues = MultiMap.createSet();
  private final Set<PsiElement> myNotToReportReachability = new THashSet<PsiElement>();
  private final Set<InstanceofInstruction> myUsefulInstanceofs = new THashSet<InstanceofInstruction>();
  private final JavaMethodCallHelper myHelper;

  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private final FactoryMap<MethodCallInstruction, Boolean> myOptionOfNullable = new FactoryMap<MethodCallInstruction, Boolean>() {
    @Nullable
    @Override
    protected Boolean create(MethodCallInstruction key) {
      PsiCallExpression expression = key.getCallExpression();
      return expression instanceof PsiMethodCallExpression && DfaOptionalSupport.resolveOfNullable(expression) != null;
    }
  };

  public StandardInstructionVisitor(DataFlowRunner runner) {
    super(runner);
    myHelper = new JavaMethodCallHelper(runner.getFactory());
  }

  @Override
  public DfaInstructionState[] visitAssign(AssignInstruction instruction, DfaMemoryState memState) {
    DfaValue dfaSource = memState.pop();
    DfaValue dfaDest = memState.pop();

    if (dfaDest instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue) dfaDest;

      DfaValueFactory factory = myRunner.getFactory();
      if (dfaSource instanceof DfaVariableValue && factory.getVarFactory().getAllQualifiedBy(var).contains(dfaSource)) {
        Nullness nullability = memState.isNotNull(dfaSource) ? Nullness.NOT_NULL
                                                             : ((DfaVariableValue)dfaSource).getInherentNullability();
        dfaSource = factory.createTypeValue(((DfaVariableValue)dfaSource).getVariableType(), nullability);
      }

      if (var.getInherentNullability() == Nullness.NOT_NULL) {
        checkNotNullable(memState, dfaSource, NullabilityProblem.assigningToNotNull, instruction.getRExpression());
      }
      final PsiModifierListOwner psi = var.getPsiVariable();
      if (!(psi instanceof PsiField) || !psi.hasModifierProperty(PsiModifier.VOLATILE)) {
        memState.setVarValue(var, dfaSource);
      }
      if (var.getInherentNullability() == Nullness.NULLABLE && !memState.isNotNull(dfaSource) && instruction.isVariableInitializer()) {
        DfaMemoryStateImpl stateImpl = (DfaMemoryStateImpl)memState;
        stateImpl.setVariableState(var, stateImpl.getVariableState(var).withNullability(Nullness.NULLABLE));
      }

    } else if (dfaDest instanceof DfaTypeValue && ((DfaTypeValue)dfaDest).isNotNull()) {
      checkNotNullable(memState, dfaSource, NullabilityProblem.assigningToNotNull, instruction.getRExpression());
    }

    memState.push(dfaDest);

    return nextInstruction(instruction, memState);
  }

  @Override
  public DfaInstructionState[] visitCheckReturnValue(CheckReturnValueInstruction instruction,
                                                     DfaMemoryState memState) {
    final DfaValue retValue = memState.pop();
    checkNotNullable(memState, retValue, NullabilityProblem.nullableReturn, instruction.getReturn());
    return nextInstruction(instruction, memState);
  }

  @Override
  public DfaInstructionState[] visitFieldReference(FieldReferenceInstruction instruction, DfaMemoryState memState) {
    final DfaValue qualifier = memState.pop();
    if (!checkNotNullable(memState, qualifier, NullabilityProblem.fieldAccessNPE, instruction.getElementToAssert())) {
      forceNotNull(myRunner, memState, qualifier);
    }
    return nextInstruction(instruction, memState);
  }

  @Override
  public DfaInstructionState[] visitPush(PushInstruction instruction, DfaMemoryState memState) {
    if (!instruction.isReferenceWrite() && instruction.getPlace() instanceof PsiReferenceExpression) {
      DfaValue dfaValue = instruction.getValue();
      if (dfaValue instanceof DfaVariableValue) {
        DfaConstValue constValue = memState.getConstantValue((DfaVariableValue)dfaValue);
        myPossibleVariableValues.putValue(instruction, constValue != null && (constValue.getValue() == null || constValue.getValue() instanceof Boolean) ? constValue : ANY_VALUE);
      }
    }
    return super.visitPush(instruction, memState);
  }

  public List<Pair<PsiReferenceExpression, DfaConstValue>> getConstantReferenceValues() {
    List<Pair<PsiReferenceExpression, DfaConstValue>> result = ContainerUtil.newArrayList();
    for (PushInstruction instruction : myPossibleVariableValues.keySet()) {
      Collection<Object> values = myPossibleVariableValues.get(instruction);
      if (values.size() == 1) {
        Object singleValue = values.iterator().next();
        if (singleValue != ANY_VALUE) {
          result.add(Pair.create((PsiReferenceExpression)instruction.getPlace(), (DfaConstValue)singleValue));
        }
      }
    }
    return result;
  }

  @Override
  public DfaInstructionState[] visitTypeCast(TypeCastInstruction instruction, DfaMemoryState memState) {
    final DfaValueFactoryJava factory = myRunner.getFactory();
    DfaValue dfaExpr = factory.createValue(instruction.getCasted());
    if (dfaExpr != null) {
      DfaTypeValue dfaType = (DfaTypeValue)factory.createTypeValue(instruction.getCastTo(), Nullness.UNKNOWN);
      DfaRelationValue dfaInstanceof = factory.getRelationFactory().createRelation(dfaExpr, dfaType, INSTANCEOF, false);
      if (dfaInstanceof != null && !memState.applyInstanceofOrNull(dfaInstanceof)) {
        onInstructionProducesCCE(instruction);
      }
    }

    if (instruction.getCastTo() instanceof PsiPrimitiveType) {
      memState.push(myRunner.getFactory().getBoxedFactory().createUnboxed(memState.pop()));
    }

    return nextInstruction(instruction, memState);
  }

  protected void onInstructionProducesCCE(TypeCastInstruction instruction) {}

  @Override
  public DfaInstructionState[] visitMethodCall(final MethodCallInstruction instruction, final DfaMemoryState memState) {
    final DfaValue[] argValues = popCallArguments(instruction, memState);
    final DfaValue qualifier = popQualifier(instruction, memState);

    LinkedHashSet<DfaMemoryState> currentStates = ContainerUtil.newLinkedHashSet(memState);
    Set<DfaMemoryState> finalStates = ContainerUtil.newLinkedHashSet();
    if (argValues != null) {
      for (MethodContract contract : instruction.getContracts()) {
        currentStates = myHelper.addContractResults(argValues, contract, currentStates, instruction, finalStates);
        if (currentStates.size() + finalStates.size() > AbstractDataFlowRunner.MAX_STATES_PER_BRANCH) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Too complex contract on " + instruction.getContext() + ", skipping contract processing");
          }
          finalStates.clear();
          currentStates = ContainerUtil.newLinkedHashSet(memState);
          break;
        }
      }
    }
    for (DfaMemoryState state : currentStates) {
      state.push(myHelper.getMethodResultValue(instruction, qualifier));
      finalStates.add(state);
    }

    DfaInstructionState[] result = new DfaInstructionState[finalStates.size()];
    int i = 0;
    for (DfaMemoryState state : finalStates) {
      if (instruction.shouldFlushFields()) {
        state.flushFields();
      }
      result[i++] = new DfaInstructionState(myRunner.getInstruction(instruction.getIndex() + 1), state);
    }
    return result;
  }

  @Nullable
  private DfaValue[] popCallArguments(MethodCallInstruction instruction, DfaMemoryState memState) {
    final PsiExpression[] args = instruction.getArgs();

    PsiMethod method = instruction.getTargetMethod();
    boolean varargCall = instruction.isVarArgCall();
    DfaValue[] argValues;
    if (method == null || instruction.getContracts().isEmpty()) {
      argValues = null;
    } else {
      int paramCount = method.getParameterList().getParametersCount();
      if (paramCount == args.length || method.isVarArgs() && args.length >= paramCount - 1) {
        argValues = new DfaValue[paramCount];
        if (varargCall) {
          argValues[paramCount - 1] = DfaUnknownValue.getInstance();
        }
      } else {
        argValues = null;
      }
    }

    for (int i = 0; i < args.length; i++) {
      final DfaValue arg = memState.pop();
      int paramIndex = args.length - i - 1;
      if (argValues != null && (paramIndex < argValues.length - 1 || !varargCall)) {
        argValues[paramIndex] = arg;
      }

      PsiExpression expr = args[paramIndex];
      Nullness requiredNullability = instruction.getArgRequiredNullability(expr);
      if (requiredNullability == Nullness.NOT_NULL) {
        if (!checkNotNullable(memState, arg, NullabilityProblem.passingNullableToNotNullParameter, expr)) {
          forceNotNull(myRunner, memState, arg);
        }
      }
      else if (myOptionOfNullable.get(instruction)) {
        checkNotNullable(memState, arg, NullabilityProblem.passingNotNullToOptional, expr);
        checkNotNullable(memState, arg, NullabilityProblem.passingNullToOptional, expr);
      }
      else if (requiredNullability == Nullness.UNKNOWN) {
        checkNotNullable(memState, arg, NullabilityProblem.passingNullableArgumentToNonAnnotatedParameter, expr);
      }
    }
    return argValues;
  }

  private DfaValue popQualifier(MethodCallInstruction instruction, DfaMemoryState memState) {
    @NotNull final DfaValue qualifier = memState.pop();
    boolean unboxing = instruction.getMethodType() == MethodCallInstruction.MethodType.UNBOXING;
    NullabilityProblem problem = unboxing ? NullabilityProblem.unboxingNullable : NullabilityProblem.callNPE;
    PsiExpression anchor = unboxing ? instruction.getContext() : instruction.getCallExpression();
    if (!checkNotNullable(memState, qualifier, problem, anchor)) {
      forceNotNull(myRunner, memState, qualifier);
    }
    return qualifier;
  }

  public static void forceNotNull(AbstractDataFlowRunner runner, DfaMemoryState memState, DfaValue arg) {
    if (arg instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)arg;
      memState.setVarValue(var, runner.getFactory().createTypeValue(var.getVariableType(), Nullness.NOT_NULL));
    }
  }

  protected boolean checkNotNullable(DfaMemoryState state,
                                     DfaValue value, NullabilityProblem problem,
                                     PsiElement anchor) {
    if (problem == NullabilityProblem.passingNotNullToOptional) {
      return !state.isNotNull(value);
    }

    boolean notNullable = state.checkNotNullable(value);
    if (notNullable &&
        problem != NullabilityProblem.passingNullableArgumentToNonAnnotatedParameter &&
        problem != NullabilityProblem.passingNullToOptional) {
      DfaValueFactory factory = ((DfaMemoryStateImpl)state).getFactory();
      state.applyCondition(factory.getRelationFactory().createRelation(value, factory.getConstFactory().getNull(), NE, false));
    }
    return notNullable;
  }

  @Override
  public DfaInstructionState[] visitBinop(BinopInstruction instruction, DfaMemoryState memState) {
    myReachable.add(instruction);

    DfaValue dfaRight = memState.pop();
    DfaValue dfaLeft = memState.pop();

    final DfaRelation opSign = instruction.getOperationSign();
    if (opSign != UNDEFINED) {
      DfaInstructionState[] states = handleConstantComparison(instruction, myRunner, memState, dfaRight, dfaLeft, opSign);
      if (states == null) {
        states = handleRelationBinop(instruction, myRunner, memState, dfaRight, dfaLeft);
      }
      if (states != null) {
        return states;
      }

      if (PLUS == opSign) {
        memState.push(myRunner.getFactory().getTypeFactory().getNonNullStringValue(instruction.getPsiAnchor(), instruction.getProject()));
      }
      else {
        if (instruction instanceof InstanceofInstruction) {
          handleInstanceof((InstanceofInstruction)instruction, dfaRight, dfaLeft);
        }
        memState.push(DfaUnknownValue.getInstance());
      }
    }
    else {
      memState.push(DfaUnknownValue.getInstance());
    }

    instruction.setTrueReachable();  // Not a branching instruction actually.
    instruction.setFalseReachable();

    return nextInstruction(instruction, memState);
  }

  @Nullable
  private DfaInstructionState[] handleRelationBinop(BinopInstruction instruction,
                                                    DataFlowRunner runner,
                                                    DfaMemoryState memState,
                                                    DfaValue dfaRight, DfaValue dfaLeft) {
    DfaValueFactory factory = runner.getFactory();
    final Instruction next = runner.getInstruction(instruction.getIndex() + 1);
    DfaRelationValue dfaRelation = factory.getRelationFactory().createRelation(dfaLeft, dfaRight, instruction.getOperationSign(), false);
    if (dfaRelation == null) {
      return null;
    }

    myCanBeNullInInstanceof.add(instruction);

    ArrayList<DfaInstructionState> states = new ArrayList<DfaInstructionState>();

    final DfaMemoryState trueCopy = memState.createCopy();
    if (trueCopy.applyCondition(dfaRelation)) {
      trueCopy.push(factory.getConstFactory().getTrue());
      instruction.setTrueReachable();
      states.add(new DfaInstructionState(next, trueCopy));
    }

    //noinspection UnnecessaryLocalVariable
    DfaMemoryState falseCopy = memState;
    if (falseCopy.applyCondition(dfaRelation.createNegated())) {
      falseCopy.push(factory.getConstFactory().getFalse());
      instruction.setFalseReachable();
      states.add(new DfaInstructionState(next, falseCopy));
      if (instruction instanceof InstanceofInstruction && !falseCopy.isNull(dfaLeft)) {
        myUsefulInstanceofs.add((InstanceofInstruction)instruction);
      }
    }

    return states.toArray(new DfaInstructionState[states.size()]);
  }

  public void skipConstantConditionReporting(@Nullable PsiElement anchor) {
    ContainerUtil.addIfNotNull(myNotToReportReachability, anchor);
  }

  private void handleInstanceof(InstanceofInstruction instruction, DfaValue dfaRight, DfaValue dfaLeft) {
    if (dfaLeft instanceof DfaTypeValue && dfaRight instanceof DfaTypeValue) {
      if (!((DfaTypeValue)dfaLeft).isNotNull()) {
        myCanBeNullInInstanceof.add(instruction);
      }

      if (((DfaTypeValue)dfaRight).getDfaType().isAssignableFrom(((DfaTypeValue)dfaLeft).getDfaType())) {
        return;
      }
    }
    myUsefulInstanceofs.add(instruction);
  }

  @Nullable
  public static DfaInstructionState[] handleConstantComparison(BinopInstruction instruction,
                                                               AbstractDataFlowRunner runner,
                                                                DfaMemoryState memState,
                                                                DfaValue dfaRight,
                                                                DfaValue dfaLeft, DfaRelation opSign) {
    if (dfaRight instanceof DfaConstValue && dfaLeft instanceof DfaVariableValue) {
      Object value = ((DfaConstValue)dfaRight).getValue();
      if (value instanceof Number) {
        DfaInstructionState[] result = checkComparingWithConstant(instruction, runner, memState, (DfaVariableValue)dfaLeft, opSign,
                                                                  ((Number)value).doubleValue());
        if (result != null) {
          return result;
        }
      }
    }
    if (dfaRight instanceof DfaVariableValue && dfaLeft instanceof DfaConstValue) {
      return handleConstantComparison(instruction, runner, memState, dfaLeft, dfaRight, DfaRelationValue.getSymmetricOperation(opSign));
    }

    if (EQ != opSign && NE != opSign) {
      return null;
    }

    if (dfaLeft instanceof DfaConstValue && dfaRight instanceof DfaConstValue ||
        dfaLeft == runner.getFactory().getConstFactory().getContractFail() ||
        dfaRight == runner.getFactory().getConstFactory().getContractFail()) {
      boolean negated = (NE == opSign) ^ (DfaMemoryStateImpl.isNaN(dfaLeft) || DfaMemoryStateImpl.isNaN(dfaRight));
      if (dfaLeft == dfaRight ^ negated) {
        return alwaysTrue(instruction, runner, memState);
      }
      return alwaysFalse(instruction, runner, memState);
    }

    return null;
  }

  @Nullable
  public static DfaInstructionState[] checkComparingWithConstant(BinopInstruction instruction,
                                                                 AbstractDataFlowRunner runner,
                                                                 DfaMemoryState memState,
                                                                 DfaVariableValue var,
                                                                 DfaRelation opSign, double comparedWith) {
    DfaConstValue knownConstantValue = memState.getConstantValue(var);
    Object knownValue = knownConstantValue == null ? null : knownConstantValue.getValue();
    if (knownValue instanceof Number) {
      double knownDouble = ((Number)knownValue).doubleValue();
      return checkComparisonWithKnownRange(instruction, runner, memState, opSign, comparedWith, knownDouble, knownDouble);
    }

    PsiType varType = var.getVariableType();
    if (!(varType instanceof PsiPrimitiveType)) return null;
    
    if (varType == PsiType.FLOAT || varType == PsiType.DOUBLE) return null;

    double minValue = varType == PsiType.BYTE ? Byte.MIN_VALUE :
                      varType == PsiType.SHORT ? Short.MIN_VALUE :
                      varType == PsiType.INT ? Integer.MIN_VALUE :
                      varType == PsiType.CHAR ? Character.MIN_VALUE :
                      Long.MIN_VALUE;
    double maxValue = varType == PsiType.BYTE ? Byte.MAX_VALUE :
                      varType == PsiType.SHORT ? Short.MAX_VALUE :
                      varType == PsiType.INT ? Integer.MAX_VALUE :
                      varType == PsiType.CHAR ? Character.MAX_VALUE :
                      Long.MAX_VALUE;

    return checkComparisonWithKnownRange(instruction, runner, memState, opSign, comparedWith, minValue, maxValue);
  }

  @Nullable
  public static DfaInstructionState[] checkComparisonWithKnownRange(BinopInstruction instruction,
                                                                    AbstractDataFlowRunner runner,
                                                                    DfaMemoryState memState,
                                                                    DfaRelation opSign,
                                                                    double comparedWith,
                                                                    double rangeMin,
                                                                    double rangeMax) {
    if (comparedWith < rangeMin || comparedWith > rangeMax) {
      if (opSign == EQ) return alwaysFalse(instruction, runner, memState);
      if (opSign == NE) return alwaysTrue(instruction, runner, memState);
    }

    if (opSign == LT && comparedWith <= rangeMin) return alwaysFalse(instruction, runner, memState);
    if (opSign == LT && comparedWith > rangeMax) return alwaysTrue(instruction, runner, memState);
    if (opSign == LE && comparedWith >= rangeMax) return alwaysTrue(instruction, runner, memState);

    if (opSign == GT && comparedWith >= rangeMax) return alwaysFalse(instruction, runner, memState);
    if (opSign == GT && comparedWith < rangeMin) return alwaysTrue(instruction, runner, memState);
    if (opSign == GE && comparedWith <= rangeMin) return alwaysTrue(instruction, runner, memState);

    return null;
  }

  public static DfaInstructionState[] alwaysFalse(BinopInstruction instruction, AbstractDataFlowRunner runner, DfaMemoryState memState) {
    memState.push(runner.getFactory().getConstFactory().getFalse());
    instruction.setFalseReachable();
    return nextInstruction(instruction, runner, memState);
  }

  public static DfaInstructionState[] alwaysTrue(BinopInstruction instruction, AbstractDataFlowRunner runner, DfaMemoryState memState) {
    memState.push(runner.getFactory().getConstFactory().getTrue());
    instruction.setTrueReachable();
    return nextInstruction(instruction, runner, memState);
  }

  public boolean isInstanceofRedundant(InstanceofInstruction instruction) {
    return !myUsefulInstanceofs.contains(instruction) && !instruction.isConditionConst() && myReachable.contains(instruction);
  }

  public boolean canBeNull(BinopInstruction instruction) {
    return myCanBeNullInInstanceof.contains(instruction);
  }

  public boolean silenceConstantCondition(@Nullable PsiElement element) {
    for (PsiElement skipped : myNotToReportReachability) {
      if (PsiTreeUtil.isAncestor(element, skipped, false)) {
        return true;
      }
    }
    if (PsiTreeUtil.findChildOfType(element, PsiAssignmentExpression.class) != null) {
      return true;
    }
    return false;
  }
}
