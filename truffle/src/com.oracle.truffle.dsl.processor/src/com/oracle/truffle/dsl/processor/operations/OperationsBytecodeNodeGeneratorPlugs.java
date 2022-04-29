package com.oracle.truffle.dsl.processor.operations;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.generator.BitSet;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.BoxingSplit;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.FrameState;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.LocalVariable;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.MultiStateBitSet;
import com.oracle.truffle.dsl.processor.generator.GeneratorUtils;
import com.oracle.truffle.dsl.processor.generator.NodeGeneratorPlugs;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.model.CacheExpression;
import com.oracle.truffle.dsl.processor.model.ExecutableTypeData;
import com.oracle.truffle.dsl.processor.model.NodeData;
import com.oracle.truffle.dsl.processor.model.NodeExecutionData;
import com.oracle.truffle.dsl.processor.model.Parameter;
import com.oracle.truffle.dsl.processor.model.SpecializationData;
import com.oracle.truffle.dsl.processor.model.TypeSystemData;
import com.oracle.truffle.dsl.processor.operations.instructions.FrameKind;
import com.oracle.truffle.dsl.processor.operations.instructions.CustomInstruction;
import com.oracle.truffle.dsl.processor.operations.instructions.CustomInstruction.DataKind;
import com.oracle.truffle.dsl.processor.operations.instructions.QuickenedInstruction;

public final class OperationsBytecodeNodeGeneratorPlugs implements NodeGeneratorPlugs {
    private final CodeVariableElement fldBc;
    private final CodeVariableElement fldChildren;
    private final List<Object> constIndices;
    private final Set<String> innerTypeNames;
    private final List<Object> additionalData;
    private final Set<String> methodNames;
    private final boolean isVariadic;
    private final List<DataKind> additionalDataKinds;
    private final CodeVariableElement fldConsts;
    private final CustomInstruction cinstr;
    private final List<Object> childIndices;

    private final ProcessorContext context;
    private final TruffleTypes types;
    private final Object resultUnboxedState;
    private List<Object> specializationStates;

    private MultiStateBitSet multiState;
    private List<BoxingSplit> boxingSplits;

    private static final boolean DO_BOXING_ELIM_IN_PE = true;
    private OperationsData m;

    OperationsBytecodeNodeGeneratorPlugs(OperationsData m, CodeVariableElement fldBc, CodeVariableElement fldChildren, List<Object> constIndices,
                    Set<String> innerTypeNames, List<Object> additionalData,
                    Set<String> methodNames, boolean isVariadic, List<DataKind> additionalDataKinds, CodeVariableElement fldConsts, CustomInstruction cinstr,
                    List<Object> childIndices) {
        this.m = m;
        this.fldBc = fldBc;
        this.fldChildren = fldChildren;
        this.constIndices = constIndices;
        this.innerTypeNames = innerTypeNames;
        this.additionalData = additionalData;
        this.methodNames = methodNames;
        this.isVariadic = isVariadic;
        this.additionalDataKinds = additionalDataKinds;
        this.fldConsts = fldConsts;
        this.cinstr = cinstr;
        this.childIndices = childIndices;

        this.context = ProcessorContext.getInstance();
        this.types = context.getTypes();

        if (cinstr.numPush() == 0) {
            resultUnboxedState = null;
        } else {
            resultUnboxedState = new Object() {
                @Override
                public String toString() {
                    return "RESULT-UNBOXED";
                }
            };
        }
    }

    @Override
    public void addAdditionalStateBits(List<Object> stateObjects) {
        if (!stateObjects.isEmpty()) {
            throw new AssertionError("stateObjects must be empty");
        }
        if (resultUnboxedState != null) {
            stateObjects.add(resultUnboxedState);
        }
    }

    @Override
    public void setStateObjects(List<Object> stateObjects) {
        this.specializationStates = stateObjects.stream() //
                        .filter(x -> x instanceof SpecializationData) //
                        .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void setBoxingSplits(List<BoxingSplit> boxingSplits) {
        this.boxingSplits = boxingSplits;
    }

    @Override
    public void setMultiState(MultiStateBitSet multiState) {
        this.multiState = multiState;
    }

    @Override
    public int getRequiredStateBits(TypeSystemData typesData, Object object) {
        return 1;
    }

    @Override
    public String transformNodeMethodName(String name) {
        String result = cinstr.getUniqueName() + "_" + name + "_";
        methodNames.add(result);
        return result;
    }

    @Override
    public String transformNodeInnerTypeName(String name) {
        if (cinstr instanceof QuickenedInstruction) {
            return ((QuickenedInstruction) cinstr).getOrig().getUniqueName() + "_" + name;
        }
        String result = cinstr.getUniqueName() + "_" + name;
        innerTypeNames.add(result);
        return result;
    }

    @Override
    public void addNodeCallParameters(CodeTreeBuilder builder, boolean isBoundary, boolean isRemoveThis) {
        if (!isBoundary) {
            builder.string("$frame");
        }
        builder.string("$bci");
        builder.string("$sp");
    }

    @Override
    public Boolean needsFrameToExecute(List<SpecializationData> specializations) {
        return false;
    }

    public boolean shouldIncludeValuesInCall() {
        return true;
    }

    @Override
    public int getMaxStateBits(int defaultValue) {
        return 8;
    }

    @Override
    public TypeMirror getBitSetType(TypeMirror defaultType) {
        return new CodeTypeMirror(TypeKind.BYTE);
    }

    @Override
    public CodeTree createBitSetReference(BitSet bits) {
        int index = additionalData.indexOf(bits);
        if (index == -1) {
            index = additionalData.size();
            additionalData.add(bits);

            additionalDataKinds.add(DataKind.BITS);
        }

        return CodeTreeBuilder.createBuilder().variable(fldBc).string("[$bci + " + cinstr.lengthWithoutState() + " + " + index + "]").build();
    }

    @Override
    public CodeTree transformValueBeforePersist(CodeTree tree) {
        return CodeTreeBuilder.createBuilder().cast(new CodeTypeMirror(TypeKind.BYTE)).startParantheses().tree(tree).end().build();
    }

    private CodeTree createArrayReference(Object refObject, boolean doCast, TypeMirror castTarget, boolean isChild) {
        if (refObject == null) {
            throw new IllegalArgumentException("refObject is null");
        }

        List<Object> refList = isChild ? childIndices : constIndices;
        int index = refList.indexOf(refObject);
        int baseIndex = additionalData.indexOf(isChild ? OperationsBytecodeCodeGenerator.MARKER_CHILD : OperationsBytecodeCodeGenerator.MARKER_CONST);

        if (index == -1) {
            if (baseIndex == -1) {
                baseIndex = additionalData.size();
                additionalData.add(isChild ? OperationsBytecodeCodeGenerator.MARKER_CHILD : OperationsBytecodeCodeGenerator.MARKER_CONST);
                additionalData.add(null);

                additionalDataKinds.add(isChild ? DataKind.CHILD : DataKind.CONST);
                additionalDataKinds.add(DataKind.CONTINUATION);
            }

            index = refList.size();
            refList.add(refObject);
        }

        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        if (doCast) {
            b.startParantheses();
            b.cast(castTarget);
        }

        VariableElement targetField;
        if (isChild) {
            targetField = fldChildren;
        } else {
            targetField = fldConsts;
        }

        b.variable(targetField).string("[");
        b.startCall("LE_BYTES", "getShort");
        b.variable(fldBc);
        b.string("$bci + " + cinstr.lengthWithoutState() + " + " + baseIndex);
        b.end();
        b.string(" + " + index + "]");

        if (doCast) {
            b.end();
        }

        return b.build();
    }

    @Override
    public CodeTree createSpecializationFieldReference(SpecializationData s, String fieldName, boolean useSpecializationClass, TypeMirror fieldType) {
        Object refObject = useSpecializationClass ? s : fieldName;
        return createArrayReference(refObject, fieldType != null, fieldType, false);
    }

    @Override
    public CodeTree createNodeFieldReference(NodeExecutionData execution, String nodeFieldName, boolean forRead) {
        if (nodeFieldName.startsWith("$child")) {
            return CodeTreeBuilder.singleString("__INVALID__");
        }
        return createArrayReference(execution, forRead, execution.getNodeType(), true);
    }

    @Override
    public CodeTree createCacheReference(SpecializationData specialization, CacheExpression cache, String sharedName, boolean forRead) {
        Object refObject = sharedName != null ? sharedName : cache;
        boolean isChild = ElementUtils.isAssignable(cache.getParameter().getType(), types.Node);
        return createArrayReference(refObject, forRead, cache.getParameter().getType(), isChild);
    }

    public int getStackOffset(LocalVariable value) {
        String name = value.getName();
        while (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.startsWith("arg") && name.endsWith("Value")) {
            return cinstr.numPopStatic() - Integer.parseInt(name.substring(3, name.length() - 5));
        }
        if (name.startsWith("child") && name.endsWith("Value")) {
            return cinstr.numPopStatic() - Integer.parseInt(name.substring(5, name.length() - 5));
        }
        throw new UnsupportedOperationException("" + value);
    }

    @Override
    public CodeTree createThrowUnsupportedChild(NodeExecutionData execution) {
        return CodeTreeBuilder.singleString("null");
    }

    @Override
    public CodeTree[] createThrowUnsupportedValues(FrameState frameState, List<CodeTree> values, CodeTreeBuilder parent, CodeTreeBuilder builder) {
        if (isVariadic) {
            return values.toArray(new CodeTree[values.size()]);
        }
        CodeTree[] result = new CodeTree[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = CodeTreeBuilder.singleString("$frame.getValue($sp - " + (cinstr.numPopStatic() - i) + ")");
        }

        return result;
    }

    @Override
    public void initializeFrameState(FrameState frameState, CodeTreeBuilder builder) {
        frameState.set("frameValue", new LocalVariable(types.VirtualFrame, "$frame", null));
    }

    private void createPushResult(FrameState frameState, CodeTreeBuilder b, CodeTree specializationCall, TypeMirror retType) {
        if (cinstr.numPush() == 0) {
            b.statement(specializationCall);
            b.returnStatement();
            return;
        }

        assert cinstr.numPush() == 1;

        int destOffset = cinstr.numPopStatic();

        CodeTree value;
        String typeName;
        if (retType.getKind() == TypeKind.VOID) {
            // we need to push something, lets just push a `null`.
            // maybe this should be an error? DSL just returns default value

            b.statement(specializationCall);
            value = CodeTreeBuilder.singleString("null");
            typeName = "Object";
        } else {
            value = specializationCall;
            typeName = getFrameType(retType.getKind()).getFrameName();
        }

        CodeTree isResultBoxed = multiState.createNotContains(frameState, new Object[]{resultUnboxedState});

        if (typeName.equals("Object")) {
            b.startStatement();
            b.startCall("$frame", "setObject");
            b.string("$sp - " + destOffset);
            b.tree(value);
            b.end(2);
        } else {
            b.declaration(retType, "value", value);
            b.startIf().tree(isResultBoxed).end().startBlock();
            {
                b.startStatement();
                b.startCall("$frame", "setObject");
                b.string("$sp - " + destOffset);
                b.string("value");
                b.end(2);
            }
            b.end().startElseBlock();
            {
                b.startStatement();
                b.startCall("$frame", "set" + typeName);
                b.string("$sp - " + destOffset);
                b.string("value");
                b.end(2);
            }
            b.end();
        }

        b.returnStatement();

    }

    @Override
    public boolean createCallSpecialization(FrameState frameState, SpecializationData specialization, CodeTree specializationCall, CodeTreeBuilder b, boolean inBoundary, CodeTree[] bindings) {
        if (isVariadic || inBoundary)
            return false;

        if (m.isTracing()) {
            b.startStatement().startCall("tracer", "traceSpecialization");
            b.string("$bci");
            b.doubleQuote(cinstr.name);
            b.string("" + specialization.getIntrospectionIndex());
            for (int i = 0; i < bindings.length; i++) {
                Parameter parameter = specialization.getParameters().get(i);
                if (parameter.getSpecification().isSignature()) {
                    b.tree(bindings[i]);
                }
            }
            b.end(2);
        }

        createPushResult(frameState, b, specializationCall, specialization.getMethod().getReturnType());
        return true;
    }

    @Override
    public boolean createCallExecuteAndSpecialize(FrameState frameState, CodeTreeBuilder builder, CodeTree call) {
        String easName = transformNodeMethodName("executeAndSpecialize");
        if (cinstr instanceof QuickenedInstruction) {
            QuickenedInstruction qinstr = (QuickenedInstruction) cinstr;

            // unquicken and call parent EAS
            builder.tree(OperationGeneratorUtils.createWriteOpcode(
                            fldBc,
                            new CodeVariableElement(context.getType(int.class), "$bci"),
                            qinstr.getOrig().opcodeIdField));

            easName = qinstr.getOrig().getUniqueName() + "_executeAndSpecialize_";

        }

        if (isVariadic) {
            builder.startReturn();
        } else {
            builder.startStatement();
        }

        builder.startCall(easName);
        addNodeCallParameters(builder, false, false);
        frameState.addReferencesTo(builder);
        builder.end(2);

        if (!isVariadic) {
            builder.returnStatement();
        }

        return true;
    }

    @Override
    public void createCallBoundaryMethod(CodeTreeBuilder builder, FrameState frameState, CodeExecutableElement boundaryMethod, Consumer<CodeTreeBuilder> addArguments) {
        if (isVariadic) {
            builder.startReturn().startCall("this", boundaryMethod);
            builder.string("$bci");
            builder.string("$sp");
            addArguments.accept(builder);
            builder.end(2);
            return;
        }

        CodeTreeBuilder callBuilder = builder.create();

        callBuilder.startCall("this", boundaryMethod);
        callBuilder.string("$bci");
        callBuilder.string("$sp");
        addArguments.accept(callBuilder);
        callBuilder.end();

        createPushResult(frameState, builder, callBuilder.build(), boundaryMethod.getReturnType());
    }

    @Override
    public boolean createCallWrapInAMethod(FrameState frameState, CodeTreeBuilder parentBuilder, CodeExecutableElement method, Runnable addStateParameters) {
        parentBuilder.startStatement().startCall(method.getSimpleName().toString());
        addNodeCallParameters(parentBuilder, false, false);
        addStateParameters.run();
        parentBuilder.end(2);
        parentBuilder.returnStatement();
        return true;
    }

    private FrameKind getFrameType(TypeKind type) {
        if (!m.getBoxingEliminatedTypes().contains(type)) {
            return FrameKind.OBJECT;
        }

        return OperationsData.convertToFrameType(type);
    }

    @Override
    public CodeTree createAssignExecuteChild(NodeData node, FrameState originalFrameState, FrameState frameState, CodeTreeBuilder parent, NodeExecutionData execution, ExecutableTypeData forType,
                    LocalVariable targetValue, Function<FrameState, CodeTree> createExecuteAndSpecialize) {
        if (isVariadic) {
            throw new AssertionError("variadic instructions should not have children");
        }

        int childIndex = execution.getIndex();
        int offset = cinstr.numPopStatic() - childIndex;

        CodeTreeBuilder b = parent.create();

        b.tree(targetValue.createDeclaration(null));

        if (!DO_BOXING_ELIM_IN_PE) {
            b.startIf().tree(GeneratorUtils.createInCompiledCode()).end().startBlock();
            {
                b.startAssign(targetValue.getName());
                b.maybeCast(context.getType(Object.class), targetValue.getTypeMirror());
                b.startCall("$frame", "getValue");
                b.string("$sp - " + offset);
                b.end(2);
            }
            b.end().startElseBlock();
        }

        FrameKind typeName = getFrameType(targetValue.getTypeMirror().getKind());

        if (typeName == FrameKind.OBJECT) {
            b.startAssign(targetValue.getName());
            b.startCall("$frame", "getObject");
            b.string("$sp - " + offset);
            b.end(2);
        } else {
            b.startIf().startCall("$frame", "is" + typeName.getFrameName()).string("$sp - " + offset).end(2).startBlock();
            {
                b.startAssign(targetValue.getName());
                b.startCall("$frame", "get" + typeName.getFrameName());
                b.string("$sp - " + offset);
                b.end(3);
            }
            b.startElseIf();
            b.startCall("$frame", "isObject").string("$sp - " + offset).end();
            b.string(" && ");
            b.startCall("$frame", "getObject").string("$sp - " + offset).end().string(" instanceof ", typeName.getTypeNameBoxed());
            b.end().startBlock();
            {
                b.startAssign(targetValue.getName());
                b.cast(targetValue.getTypeMirror());
                b.startCall("$frame", "getObject");
                b.string("$sp - " + offset);
                b.end(3);
            }
            b.end().startElseBlock();
            {

                b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());

                // slow path
                FrameState slowPathFrameState = frameState.copy();

                CodeTreeBuilder accessBuilder = b.create();
                accessBuilder.startCall("$frame", "getValue");
                accessBuilder.string("$sp - " + offset);
                accessBuilder.end();

                slowPathFrameState.setValue(execution, targetValue.makeGeneric(context).accessWith(accessBuilder.build()));

                int curOffset = offset;
                boolean found = false;

                for (NodeExecutionData otherExecution : node.getChildExecutions()) {
                    if (found) {
                        LocalVariable childEvaluatedValue = slowPathFrameState.createValue(otherExecution, context.getType(Object.class));
                        b.declaration("Object", childEvaluatedValue.getName(), "$frame.getValue($sp - " + (--curOffset) + ")");
                        slowPathFrameState.setValue(otherExecution, childEvaluatedValue);
                    } else {
                        // skip forward already evaluated
                        found = execution == otherExecution;
                    }
                }

                b.tree(createExecuteAndSpecialize.apply(slowPathFrameState));

            }
            b.end();

        }

        if (!DO_BOXING_ELIM_IN_PE) {
            b.end();
        }

        return b.build();
    }

    @Override
    public void createSpecialize(FrameState frameState, SpecializationData specialization, CodeTreeBuilder b) {

        // quickening
        if (!(cinstr instanceof QuickenedInstruction)) {
            List<QuickenedInstruction> quickened = cinstr.getQuickenedVariants();

            boolean elseIf = false;
            for (QuickenedInstruction qinstr : quickened) {
                if (qinstr.getActiveSpecs().contains(specialization)) {
                    elseIf = b.startIf(elseIf);
                    b.tree(multiState.createIs(frameState, qinstr.getActiveSpecs().toArray(), specializationStates.toArray()));
                    b.end().startBlock();
                    {
                        b.tree(OperationGeneratorUtils.createWriteOpcode(fldBc, "$bci", qinstr.opcodeIdField));
                    }
                    b.end();
                }
            }
        }

        // boxing elimination
        if (!isVariadic && boxingSplits != null) {
            boolean elseIf = false;
            for (BoxingSplit split : boxingSplits) {
                List<SpecializationData> specializations = split.getGroup().collectSpecializations();
                if (!specializations.contains(specialization)) {
                    continue;
                }

                elseIf = b.startIf(elseIf);

                b.startGroup();
                CodeTree tree = multiState.createContainsOnly(frameState, 0, -1, specializations.toArray(), specializationStates.toArray());
                if (!tree.isEmpty()) {
                    b.tree(tree);
                    b.string(" && ");
                }
                b.tree(multiState.createIsNotAny(frameState, specializationStates.toArray()));
                b.end();
                b.end().startBlock();

                for (int i = 0; i < cinstr.numPopStatic(); i++) {
                    FrameKind frameType = getFrameType(split.getPrimitiveSignature()[i].getKind());
                    b.tree(OperationGeneratorUtils.callSetResultBoxed("bc[$bci + " + cinstr.getStackValueArgumentOffset(i) + "] & 0xff", frameType));
                }

                b.end();
            }

            if (elseIf) {
                b.startElseBlock();
            }

            for (int i = 0; i < cinstr.numPopStatic(); i++) {
                b.tree(OperationGeneratorUtils.callSetResultBoxed("bc[$bci + " + cinstr.getStackValueArgumentOffset(i) + "] & 0xff", FrameKind.OBJECT));
            }

            if (elseIf) {
                b.end();
            }
        }

    }

    public CodeTree createSetResultBoxed(CodeVariableElement varUnboxed) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();
        b.startIf().variable(varUnboxed).end().startBlock();
        b.tree(multiState.createSet(FrameState.createEmpty(), new Object[]{resultUnboxedState}, true, true));
        b.end().startElseBlock();
        b.tree(multiState.createSet(FrameState.createEmpty(), new Object[]{resultUnboxedState}, false, true));
        b.end();
        return b.build();
    }

    @SuppressWarnings("unchecked")
    public CodeTree createGetSpecializationBits() {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();
        FrameState frame = FrameState.createEmpty();

        b.tree(multiState.createLoad(frame, specializationStates.toArray()));

        b.declaration("boolean[]", "result", "new boolean[" + specializationStates.size() + "]");

        for (int i = 0; i < specializationStates.size(); i++) {
            b.startAssign("result[" + i + "]");
            b.tree(multiState.createContains(frame, new Object[]{specializationStates.get(i)}));
            b.end();
        }

        b.startReturn().string("result").end();

        return b.build();
    }

    public boolean needsRewrites() {
        return true;
    }

    @Override
    public List<SpecializationData> filterSpecializations(List<SpecializationData> implementedSpecializations) {
        if (!(cinstr instanceof QuickenedInstruction)) {
            return implementedSpecializations;
        }

        QuickenedInstruction qinstr = (QuickenedInstruction) cinstr;
        return qinstr.getActiveSpecs();
    }

    @Override
    public boolean isStateGuaranteed(boolean stateGuaranteed) {
        if (stateGuaranteed) {
            return true;
        }

        if (cinstr instanceof QuickenedInstruction && ((QuickenedInstruction) cinstr).getActiveSpecs().size() == 1) {
            return true;
        }

        return false;
    }
}