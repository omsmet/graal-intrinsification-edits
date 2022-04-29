package com.oracle.truffle.dsl.processor.operations.instructions;

import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;

public class StoreLocalInstruction extends Instruction {

    public StoreLocalInstruction(int id) {
        super("store.local", id, ResultType.SET_LOCAL, InputType.STACK_VALUE);
    }

    @Override
    public boolean standardPrologue() {
        return false;
    }

    @Override
    public CodeTree createExecuteCode(ExecutionVariables vars) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startAssert().startCall(vars.frame, "isObject");
        b.startGroup().variable(vars.sp).string(" - 1").end();
        b.end(2);

        b.startStatement().startCall(vars.frame, "copy");

        b.startGroup().variable(vars.sp).string(" - 1").end();

        b.startGroup();
        b.startCall("LE_BYTES", "getShort");
        b.variable(vars.bc);
        b.startGroup().variable(vars.bci).string(" + " + getArgumentOffset(1)).end();
        b.end();
        b.string(" + VALUES_OFFSET");
        b.end();

        b.end(2);

        b.startStatement().startCall(vars.frame, "clear");
        b.startGroup().string("--").variable(vars.sp).end();
        b.end(2);

        return b.build();

    }

    @Override
    public CodeTree createSetResultBoxed(ExecutionVariables vars, CodeVariableElement varBoxed, CodeVariableElement varTargetType) {
        return null;
    }

    @Override
    public CodeTree createPrepareAOT(ExecutionVariables vars, CodeTree language, CodeTree root) {
        return null;
    }

    @Override
    public CodeTree[] createTracingArguments(ExecutionVariables vars) {
        return new CodeTree[]{
                        CodeTreeBuilder.singleString("ExecutionTracer.INSTRUCTION_TYPE_STORE_LOCAL"),
                        CodeTreeBuilder.singleString("LE_BYTES.getShort(bc, bci + " + getArgumentOffset(1) + ")"),
                        CodeTreeBuilder.singleString("frame.getValue(sp - 1).getClass()")
        };
    }
}