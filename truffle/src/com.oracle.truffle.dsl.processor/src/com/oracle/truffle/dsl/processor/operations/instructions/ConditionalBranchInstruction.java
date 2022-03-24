package com.oracle.truffle.dsl.processor.operations.instructions;

import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction.ExecutionVariables;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction.InputType;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction.ResultType;

public class ConditionalBranchInstruction extends Instruction {
    public ConditionalBranchInstruction(int id) {
        super("brfalse", id, ResultType.BRANCH, InputType.BRANCH_TARGET, InputType.STACK_VALUE);
    }

    @Override
    public TypeMirror[] expectedInputTypes(ProcessorContext context) {
        return new TypeMirror[]{context.getType(short.class), context.getType(boolean.class)};
    }

    @Override
    public CodeTree createExecuteCode(ExecutionVariables vars) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startIf().variable(vars.inputs[1]).end();
        b.startBlock();
        b.startAssign(vars.results[0]).variable(vars.bci).string(" + " + length()).end();
        b.end().startElseBlock();
        b.startAssign(vars.results[0]).variable(vars.inputs[0]).end();
        b.end();

        return b.build();
    }
}