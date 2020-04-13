/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.instructions.cp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.sysds.common.Builtins;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.rewrite.ProgramRewriter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.dml.DmlSyntacticValidator;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.apache.sysds.runtime.util.DataConverter;

/**
 * Eval built-in function instruction
 * Note: it supports only single matrix[double] output
 */
public class EvalNaryCPInstruction extends BuiltinNaryCPInstruction {

	public EvalNaryCPInstruction(Operator op, String opcode, String istr, CPOperand output, CPOperand... inputs) {
		super(op, opcode, istr, output, inputs);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		//1. get the namespace and func
		String funcName = ec.getScalarInput(inputs[0]).getStringValue();
		if( funcName.contains(Program.KEY_DELIM) )
			throw new DMLRuntimeException("Eval calls to '"+funcName+"', i.e., a function outside "
				+ "the default "+ "namespace, are not supported yet. Please call the function directly.");
		
		// bound the inputs to avoiding being deleted after the function call
		CPOperand[] boundInputs = Arrays.copyOfRange(inputs, 1, inputs.length);
		ArrayList<String> boundOutputNames = new ArrayList<>();
		boundOutputNames.add(output.getName());
		ArrayList<String> boundInputNames = new ArrayList<>();
		for (CPOperand input : boundInputs) {
			boundInputNames.add(input.getName());
		}

		//2. copy the created output matrix
		MatrixObject outputMO = new MatrixObject(ec.getMatrixObject(output.getName()));

		//3. lazy loading of dml-bodied builtin functions (incl. rename 
		// of function name to dml-bodied builtin scheme (data-type-specific) 
		if( !ec.getProgram().containsFunctionProgramBlock(null, funcName) ) {
			compileFunctionProgramBlock(funcName, boundInputs[0].getDataType(), ec.getProgram());
			funcName = Builtins.getInternalFName(funcName, boundInputs[0].getDataType());
		}
		
		//4. call the function
		FunctionProgramBlock fpb = ec.getProgram().getFunctionProgramBlock(null, funcName);
		FunctionCallCPInstruction fcpi = new FunctionCallCPInstruction(null, funcName,
			boundInputs, boundInputNames, fpb.getInputParamNames(), boundOutputNames, "eval func");
		fcpi.processInstruction(ec);

		//5. convert the result to matrix
		Data newOutput = ec.getVariable(output);
		if (newOutput instanceof MatrixObject) {
			return;
		}
		MatrixBlock mb = null;
		if (newOutput instanceof ScalarObject) {
			//convert scalar to matrix
			mb = new MatrixBlock(((ScalarObject) newOutput).getDoubleValue());
		} else if (newOutput instanceof FrameObject) {
			//convert frame to matrix
			mb = DataConverter.convertToMatrixBlock(((FrameObject) newOutput).acquireRead());
			ec.cleanupCacheableData((FrameObject) newOutput);
		}
		outputMO.acquireModify(mb);
		outputMO.release();
		ec.setVariable(output.getName(), outputMO);
	}
	
	private static void compileFunctionProgramBlock(String name, DataType dt, Program prog) {
		//load builtin file and parse function statement block
		Map<String,FunctionStatementBlock> fsbs = DmlSyntacticValidator
			.loadAndParseBuiltinFunction(name, DMLProgram.DEFAULT_NAMESPACE);
		if( fsbs.isEmpty() )
			throw new DMLRuntimeException("Failed to compile function '"+name+"'.");
		
		// prepare common data structures, including a consolidated dml program
		// to facilitate function validation which tries to inline lazily loaded
		// and existing functions.
		DMLProgram dmlp = (prog.getDMLProg() != null) ? prog.getDMLProg() :
			fsbs.get(Builtins.getInternalFName(name, dt)).getDMLProg();
		for( Entry<String,FunctionStatementBlock> fsb : fsbs.entrySet() ) {
			if( !dmlp.containsFunctionStatementBlock(fsb.getKey()) )
				dmlp.addFunctionStatementBlock(fsb.getKey(), fsb.getValue());
			fsb.getValue().setDMLProg(dmlp);
		}
		DMLTranslator dmlt = new DMLTranslator(dmlp);
		ProgramRewriter rewriter = new ProgramRewriter(true, false);
		ProgramRewriter rewriter2 = new ProgramRewriter(false, true);
		
		// validate functions, in two passes for cross references
		for( FunctionStatementBlock fsb : fsbs.values() ) {
			dmlt.liveVariableAnalysisFunction(dmlp, fsb);
			dmlt.validateFunction(dmlp, fsb);
		}
		
		// compile hop dags, rewrite hop dags and compile lop dags
		for( FunctionStatementBlock fsb : fsbs.values() ) {
			dmlt.constructHops(fsb);
			rewriter.rewriteHopDAGsFunction(fsb, false); //rewrite and merge
			DMLTranslator.resetHopsDAGVisitStatus(fsb);
			rewriter.rewriteHopDAGsFunction(fsb, true); //rewrite and split
			DMLTranslator.resetHopsDAGVisitStatus(fsb);
			rewriter2.rewriteHopDAGsFunction(fsb, true);
			DMLTranslator.resetHopsDAGVisitStatus(fsb);
			DMLTranslator.refreshMemEstimates(fsb);
			dmlt.constructLops(fsb);
		}
		
		// compile runtime program
		for( Entry<String,FunctionStatementBlock> fsb : fsbs.entrySet() ) {
			FunctionProgramBlock fpb = (FunctionProgramBlock) dmlt
				.createRuntimeProgramBlock(prog, fsb.getValue(), ConfigurationManager.getDMLConfig());
			prog.addFunctionProgramBlock(null, fsb.getKey(), fpb);
		}
	}
}