/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.optimizer.rules;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;

import edu.uci.ics.asterix.om.functions.AsterixBuiltinFunctions;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression.FunctionKind;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AggregateFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

/**
 * When aggregates appear w/o group-by, a default group by a constant is
 * introduced.
 */

public class PushAggFuncIntoStandaloneAggregateRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        // Pattern to match: assign <-- aggregate <-- !(group-by)
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.ASSIGN) {
            return false;
        }
        Mutable<ILogicalOperator> opRef2 = op.getInputs().get(0);
        AbstractLogicalOperator op2 = (AbstractLogicalOperator) opRef2.getValue();
        if (op2.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
            return false;
        }
        // If there's a group by below the agg, then we want to have the agg pushed into the group by.
        Mutable<ILogicalOperator> opRef3 = op2.getInputs().get(0);
        AbstractLogicalOperator op3 = (AbstractLogicalOperator) opRef3.getValue();
        if (op3.getOperatorTag() == LogicalOperatorTag.GROUP) {
            return false;
        }
        
        AssignOperator assignOp = (AssignOperator) op;
        AggregateOperator aggOp = (AggregateOperator) op2;
        if (aggOp.getVariables().size() != 1) {
            return false;
        }
        LogicalVariable aggVar = aggOp.getVariables().get(0);
        List<LogicalVariable> used = new LinkedList<LogicalVariable>();
        VariableUtilities.getUsedVariables(assignOp, used);
        if (!used.contains(aggVar)) {
            return false;
        }
        
        Mutable<ILogicalExpression> srcAssignExprRef = fingAggFuncExprRef(assignOp.getExpressions(), aggVar);
        if (srcAssignExprRef == null) {
        	return false;
        }
        AbstractFunctionCallExpression assignFuncExpr = (AbstractFunctionCallExpression) srcAssignExprRef.getValue();
        FunctionIdentifier aggFuncIdent = AsterixBuiltinFunctions.getAggregateFunction(assignFuncExpr.getFunctionIdentifier());
        
        // Push the agg func into the agg op.                
        AbstractFunctionCallExpression aggOpExpr = (AbstractFunctionCallExpression) aggOp.getExpressions().get(0).getValue();
        aggOp.getExpressions().get(0).setValue(new AggregateFunctionCallExpression(AsterixBuiltinFunctions.getAsterixFunctionInfo(aggFuncIdent), false, aggOpExpr.getArguments().get(0)));
        
        // The assign now just "renames" the variable to make sure the upstream plan still works.
        srcAssignExprRef.setValue(new VariableReferenceExpression(aggVar));

        // Create a new assign for a TRUE variable.
        LogicalVariable trueVar = context.newVar();
        AssignOperator trueAssignOp = new AssignOperator(trueVar, new MutableObject<ILogicalExpression>(ConstantExpression.TRUE));
        
        ILogicalOperator aggInput = aggOp.getInputs().get(0).getValue();
        aggOp.getInputs().get(0).setValue(trueAssignOp);
        trueAssignOp.getInputs().add(new MutableObject<ILogicalOperator>(aggInput));
        
        // Set partitioning variable.
        aggOp.setPartitioningVariable(trueVar);
        
        context.computeAndSetTypeEnvironmentForOperator(trueAssignOp);
        context.computeAndSetTypeEnvironmentForOperator(aggOp);
        context.computeAndSetTypeEnvironmentForOperator(assignOp);
        
        return true;
    }
    
    private Mutable<ILogicalExpression> fingAggFuncExprRef(List<Mutable<ILogicalExpression>> exprRefs, LogicalVariable aggVar) {
    	for (Mutable<ILogicalExpression> exprRef : exprRefs) {
            ILogicalExpression expr = exprRef.getValue();
            if (expr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
                continue;
            }
            AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;
            FunctionIdentifier funcIdent = AsterixBuiltinFunctions.getAggregateFunction(funcExpr.getFunctionIdentifier());
            if (funcIdent == null) {
            	// Recursively look in func args.
            	return fingAggFuncExprRef(funcExpr.getArguments(), aggVar);
            }
            // Check if this is the expr that uses aggVar.
            Collection<LogicalVariable> usedVars = new HashSet<LogicalVariable>();
            funcExpr.getUsedVariables(usedVars);
            if (usedVars.contains(aggVar)) {
            	return exprRef;
            }
    	}
    	return null;
    }
}
