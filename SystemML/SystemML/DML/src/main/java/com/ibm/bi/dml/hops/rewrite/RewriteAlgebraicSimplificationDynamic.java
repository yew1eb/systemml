/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops.rewrite;

import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.bi.dml.hops.AggBinaryOp;
import com.ibm.bi.dml.hops.AggUnaryOp;
import com.ibm.bi.dml.hops.BinaryOp;
import com.ibm.bi.dml.hops.DataGenOp;
import com.ibm.bi.dml.hops.Hop;
import com.ibm.bi.dml.hops.Hop.AggOp;
import com.ibm.bi.dml.hops.Hop.Direction;
import com.ibm.bi.dml.hops.Hop.OpOp1;
import com.ibm.bi.dml.hops.Hop.ReOrgOp;
import com.ibm.bi.dml.hops.HopsException;
import com.ibm.bi.dml.hops.IndexingOp;
import com.ibm.bi.dml.hops.LeftIndexingOp;
import com.ibm.bi.dml.hops.LiteralOp;
import com.ibm.bi.dml.hops.Hop.OpOp2;
import com.ibm.bi.dml.hops.ReorgOp;
import com.ibm.bi.dml.hops.UnaryOp;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;

/**
 * Rule: Algebraic Simplifications. Simplifies binary expressions
 * in terms of two major purposes: (1) rewrite binary operations
 * to unary operations when possible (in CP this reduces the memory
 * estimate, in MR this allows map-only operations and hence prevents 
 * unnecessary shuffle and sort) and (2) remove binary operations that
 * are in itself are unnecessary (e.g., *1 and /1).
 * 
 */
public class RewriteAlgebraicSimplificationDynamic extends HopRewriteRule
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private static final Log LOG = LogFactory.getLog(RewriteAlgebraicSimplificationDynamic.class.getName());
	
	
	//valid aggregation operation types for rowOp to Op conversions (not all operations apply)
	private static AggOp[] LOOKUP_VALID_ROW_COL_AGGREGATE = new AggOp[]{AggOp.SUM, AggOp.MIN, AggOp.MAX, AggOp.MEAN};	
	
	//valid aggregation operation types for empty (sparse-safe) operations (not all operations apply)
	//AggOp.MEAN currently not due to missing count/corrections
	private static AggOp[] LOOKUP_VALID_EMPTY_AGGREGATE = new AggOp[]{AggOp.SUM, AggOp.MIN, AggOp.MAX, AggOp.PROD, AggOp.TRACE}; 
	
	//valid unary operation types for empty (sparse-safe) operations (not all operations apply)
	private static OpOp1[] LOOKUP_VALID_EMPTY_UNARY = new OpOp1[]{OpOp1.ABS, OpOp1.SIN, OpOp1.TAN, OpOp1.SQRT, OpOp1.ROUND}; 
	
	
	
	@Override
	public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( roots == null )
			return roots;

		//one pass rewrite-descend (rewrite created pattern)
		for( Hop h : roots )
			rule_AlgebraicSimplification( h, false );

		Hop.resetVisitStatus(roots);
		
		//one pass descend-rewrite (for rollup) 
		for( Hop h : roots )
			rule_AlgebraicSimplification( h, true );

		return roots;
	}

	@Override
	public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( root == null )
			return root;
		
		//one pass rewrite-descend (rewrite created pattern)
		rule_AlgebraicSimplification( root, false );
		
		root.resetVisitStatus();
		
		//one pass descend-rewrite (for rollup) 
		rule_AlgebraicSimplification( root, true );
		
		return root;
	}


	/**
	 * Note: X/y -> X * 1/y would be useful because * cheaper than / and sparsesafe; however,
	 * (1) the results would be not exactly the same (2 rounds instead of 1) and (2) it should 
	 * come before constant folding while the other simplifications should come after constant
	 * folding. Hence, not applied yet.
	 * 
	 * @throws HopsException
	 */
	private void rule_AlgebraicSimplification(Hop hop, boolean descendFirst) 
		throws HopsException 
	{
		if(hop.get_visited() == Hop.VISIT_STATUS.DONE)
			return;
		
		//recursively process children
		for( int i=0; i<hop.getInput().size(); i++)
		{
			Hop hi = hop.getInput().get(i);
			
			//process childs recursively first (to allow roll-up)
			if( descendFirst )
				rule_AlgebraicSimplification(hi, descendFirst); //see below
			
			//apply actual simplification rewrites (of childs incl checks)
			hi = removeEmptyRightIndexing(hop, hi, i);        //e.g., X[,1] -> matrix(0,ru-rl+1,cu-cl+1), if nnz(X)==0 
			hi = removeUnnecessaryRightIndexing(hop, hi, i);  //e.g., X[,1] -> X, if output == input size 
			hi = removeEmptyLeftIndexing(hop, hi, i);         //e.g., X[,1]=Y -> matrix(0,nrow(X),ncol(X)), if nnz(X)==0 and nnz(Y)==0 
			hi = removeUnnecessaryLeftIndexing(hop, hi, i);   //e.g., X[,1]=Y -> Y, if output == input size 
			hi = simplifyColwiseAggregate(hop, hi, i);        //e.g., colsums(X) -> sum(X) or X, if col/row vector
			hi = simplifyRowwiseAggregate(hop, hi, i);        //e.g., rowsums(X) -> sum(X) or X, if row/col vector
			hi = simplifyEmptyAggregate(hop, hi, i);          //e.g., sum(X) -> 0, if nnz(X)==0
			hi = simplifyEmptyUnaryOperation(hop, hi, i);     //e.g., round(X) -> matrix(0,nrow(X),ncol(X)), if nnz(X)==0			
			hi = simplifyEmptyReorgOperation(hop, hi, i);     //e.g., t(X) -> matrix(0, ncol(X), nrow(X)) 
			hi = simplifyEmptyMatrixMult(hop, hi, i);         //e.g., X%*%Y -> matrix(0,...), if nnz(Y)==0 | X if Y==matrix(1,1,1)
			hi = simplifyIdentityRepMatrixMult(hop, hi, i);   //e.g., X%*%y -> X if y matrix(1,1,1);
			hi = simplifyScalarMatrixMult(hop, hi, i);        //e.g., X%*%y -> X*as.scalar(y), if y is a 1-1 matrix
			hi = simplifyMatrixMultDiag(hop, hi, i);          //e.g., diag(X)%*%Y -> X*Y, if ncol(Y)==1 / -> Y*X if ncol(Y)>1 
			hi = simplifyDiagMatrixMult(hop, hi, i);          //e.g., diag(X%*%Y)->rowSums(X*t(Y));, if col vector
			hi = reorderMinusMatrixMult(hop, hi, i);          //e.g., (-t(X))%*%y->-(t(X)%*%y), TODO size 
			hi = simplifyEmptyBinaryOperation(hop, hi, i);    //e.g., X*Y -> matrix(0,nrow(X), ncol(X)) / X+Y->X / X-Y -> X
			
			//process childs recursively after rewrites (to investigate pattern newly created by rewrites)
			if( !descendFirst )
				rule_AlgebraicSimplification(hi, descendFirst);
		}

		hop.set_visited(Hop.VISIT_STATUS.DONE);
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop removeEmptyRightIndexing(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof IndexingOp  ) //indexing op
		{	
			Hop input = hi.getInput().get(0);
			if( input.getNnz()==0 && //nnz input known and empty
			    HopRewriteUtils.isDimsKnown(hi)) //output dims known
			{
				//remove unnecessary right indexing
				HopRewriteUtils.removeChildReference(parent, hi);
				
				Hop hnew = HopRewriteUtils.createDataGenOpByVal( new LiteralOp(String.valueOf(hi.get_dim1()), hi.get_dim1()), 
						                                         new LiteralOp(String.valueOf(hi.get_dim2()), hi.get_dim2()), 0);
				HopRewriteUtils.addChildReference(parent, hnew, pos);
				parent.refreshSizeInformation();
				hi = hnew;
				
				LOG.debug("Applied removeEmptyRightIndexing");
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop removeUnnecessaryRightIndexing(Hop parent, Hop hi, int pos)
	{
		if( hi instanceof IndexingOp  ) //indexing op
		{
			Hop input = hi.getInput().get(0);
			if(   HopRewriteUtils.isDimsKnown(hi)  //dims output known
			   && HopRewriteUtils.isDimsKnown(input)  //dims input known
		       && HopRewriteUtils.isEqualSize(hi, input)) //equal dims
			{
				//equal dims of right indexing input and output -> no need for indexing
				
				//remove unnecessary right indexing
				HopRewriteUtils.removeChildReference(parent, hi);
				HopRewriteUtils.addChildReference(parent, input, pos);
				parent.refreshSizeInformation();
				hi = input;
				
				LOG.debug("Applied removeUnnecessaryRightIndexing");
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop removeEmptyLeftIndexing(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof LeftIndexingOp  ) //left indexing op
		{
			Hop input1 = hi.getInput().get(0); //lhs matrix
			Hop input2 = hi.getInput().get(1); //rhs matrix
			
			if(   input1.getNnz()==0 //nnz original known and empty
			   && input2.getNnz()==0  ) //nnz input known and empty
			{
				//remove unnecessary right indexing
				HopRewriteUtils.removeChildReference(parent, hi);		
				Hop hnew = HopRewriteUtils.createDataGenOp( input1, 0);
				HopRewriteUtils.addChildReference(parent, hnew, pos);
				parent.refreshSizeInformation();
				hi = hnew;
				
				LOG.debug("Applied removeEmptyLeftIndexing");
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop removeUnnecessaryLeftIndexing(Hop parent, Hop hi, int pos)
	{
		if( hi instanceof LeftIndexingOp  ) //left indexing op
		{
			Hop input = hi.getInput().get(1); //rhs matrix
			
			if(   HopRewriteUtils.isDimsKnown(hi)  //dims output known
			   && HopRewriteUtils.isDimsKnown(input)  //dims input known
		       && HopRewriteUtils.isEqualSize(hi, input)) //equal dims
			{
				//equal dims of left indexing input and output -> no need for indexing
				
				//remove unnecessary right indexing
				HopRewriteUtils.removeChildReference(parent, hi);				
				HopRewriteUtils.addChildReference(parent, input, pos);
				parent.refreshSizeInformation();
				hi = input;
				
				LOG.debug("Applied removeUnnecessaryLeftIndexing");
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException 
	 */
	private Hop simplifyColwiseAggregate( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof AggUnaryOp  ) 
		{
			AggUnaryOp uhi = (AggUnaryOp)hi;
			Hop input = uhi.getInput().get(0);
			
			if( HopRewriteUtils.isValidOp(uhi.getOp(), LOOKUP_VALID_ROW_COL_AGGREGATE) ) {
				if( uhi.getDirection() == Direction.Col  )
				{
					if( input.get_dim1() == 1 )
					{
						//remove unnecessary col aggregation for 1 row
						HopRewriteUtils.removeChildReference(parent, hi);
						HopRewriteUtils.addChildReference(parent, input, pos);
						parent.refreshSizeInformation();
						hi = input;
						
						LOG.debug("Applied simplifyColwiseAggregate1");
					}
					else if( input.get_dim2() == 1 )
					{
						//get old parents (before creating cast over aggregate)
						ArrayList<Hop> parents = (ArrayList<Hop>) hi.getParent().clone();

						//simplify col-aggregate to full aggregate
						uhi.setDirection(Direction.RowCol);
						uhi.set_dataType(DataType.SCALAR);
						
						//create cast to keep same output datatype
						UnaryOp cast = new UnaryOp(uhi.get_name(), DataType.MATRIX, ValueType.DOUBLE, 
				                   OpOp1.CAST_AS_MATRIX, uhi);
						
						//rehang cast under all parents
						for( Hop p : parents ) {
							int ix = HopRewriteUtils.getChildReferencePos(p, hi);
							HopRewriteUtils.removeChildReference(p, hi);
							HopRewriteUtils.addChildReference(p, cast, ix);
						}
						
						hi = cast;
						
						LOG.debug("Applied simplifyColwiseAggregate2");
					}
				}			
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyRowwiseAggregate( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof AggUnaryOp  ) 
		{
			AggUnaryOp uhi = (AggUnaryOp)hi;
			Hop input = uhi.getInput().get(0);
			
			if( HopRewriteUtils.isValidOp(uhi.getOp(), LOOKUP_VALID_ROW_COL_AGGREGATE) ) {
				if( uhi.getDirection() == Direction.Row  )
				{
					if( input.get_dim2() == 1 )
					{
						//remove unnecessary row aggregation for 1 col
						HopRewriteUtils.removeChildReference(parent, hi);
						HopRewriteUtils.addChildReference(parent, input, pos);
						parent.refreshSizeInformation();
						hi = input;
						
						LOG.debug("Applied simplifyRowwiseAggregate1");
					}
					else if( input.get_dim1() == 1 )
					{
						//get old parents (before creating cast over aggregate)
						ArrayList<Hop> parents = (ArrayList<Hop>) hi.getParent().clone();

						//simplify row-aggregate to full aggregate
						uhi.setDirection(Direction.RowCol);
						uhi.set_dataType(DataType.SCALAR);
						
						//create cast to keep same output datatype
						UnaryOp cast = new UnaryOp(uhi.get_name(), DataType.MATRIX, ValueType.DOUBLE, 
				                   OpOp1.CAST_AS_MATRIX, uhi);
						
						//rehang cast under all parents
						for( Hop p : parents ) {
							int ix = HopRewriteUtils.getChildReferencePos(p, hi);
							HopRewriteUtils.removeChildReference(p, hi);
							HopRewriteUtils.addChildReference(p, cast, ix);
						}
						
						hi = cast;
						
						LOG.debug("Applied simplifyRowwiseAggregate2");
					}
				}	
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyEmptyAggregate(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof AggUnaryOp  ) 
		{
			AggUnaryOp uhi = (AggUnaryOp)hi;
			Hop input = uhi.getInput().get(0);
			
			if( HopRewriteUtils.isValidOp(uhi.getOp(), LOOKUP_VALID_EMPTY_AGGREGATE) ){		
				
				if( HopRewriteUtils.isEmpty(input) )
				{
					//remove unnecessary aggregation 
					HopRewriteUtils.removeChildReference(parent, hi);
				
					Hop hnew = null;
					if( uhi.getDirection() == Direction.RowCol ) 
						hnew = new LiteralOp("0", 0.0);
					else if( uhi.getDirection() == Direction.Col ) 
						hnew = HopRewriteUtils.createDataGenOp(uhi, input, 0); //nrow(uhi)=1
					else if( uhi.getDirection() == Direction.Row ) 
						hnew = HopRewriteUtils.createDataGenOp(input, uhi, 0); //ncol(uhi)=1
					
					//add new child to parent input
					HopRewriteUtils.addChildReference(parent, hnew, pos);
					parent.refreshSizeInformation();
					hi = hnew;
					
					LOG.debug("Applied simplifyEmptyAggregate");
				}
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyEmptyUnaryOperation(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof UnaryOp  ) 
		{
			UnaryOp uhi = (UnaryOp)hi;
			Hop input = uhi.getInput().get(0);
			
			if( HopRewriteUtils.isValidOp(uhi.get_op(), LOOKUP_VALID_EMPTY_UNARY) ){		
				
				if( HopRewriteUtils.isEmpty(input) )
				{
					//remove unnecessary aggregation 
					HopRewriteUtils.removeChildReference(parent, hi);
					
					//create literal add it to parent
					Hop hnew = HopRewriteUtils.createDataGenOp(input, 0);
					HopRewriteUtils.addChildReference(parent, hnew, pos);
					parent.refreshSizeInformation();
					
					hi = hnew;
					
					LOG.debug("Applied simplifyEmptyUnaryOperation");
				}
			}			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyEmptyReorgOperation(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof ReorgOp  ) 
		{
			ReorgOp rhi = (ReorgOp)hi;
			Hop input = rhi.getInput().get(0);
			
			if( HopRewriteUtils.isEmpty(input) ) //empty input
			{
				//reorg-operation-specific rewrite  
				Hop hnew = null;
				if( rhi.getOp() == ReOrgOp.TRANSPOSE )
					hnew = HopRewriteUtils.createDataGenOp(input, true, input, true, 0);
				else if( rhi.getOp() == ReOrgOp.DIAG ){
					if( HopRewriteUtils.isDimsKnown(input) ){
						if( input.get_dim1()==1 ) //diagv2m
							hnew = HopRewriteUtils.createDataGenOp(input, false, input, true, 0);
						else //diagm2v
							hnew = HopRewriteUtils.createDataGenOpByVal(
									HopRewriteUtils.createValueHop(input,true), new LiteralOp("1",1), 0);
					}
				}
				else if( rhi.getOp() == ReOrgOp.RESHAPE )
					hnew = HopRewriteUtils.createDataGenOpByVal(rhi.getInput().get(1), rhi.getInput().get(2), 0);
			
				//modify dag if one of the above rules applied
				if( hnew != null ){ 
					HopRewriteUtils.removeChildReference(parent, hi);
					HopRewriteUtils.addChildReference(parent, hnew, pos);
					parent.refreshSizeInformation();
					hi = hnew;
					
					LOG.debug("Applied simplifyEmptyReorgOperation");
				}
			}
			
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyEmptyMatrixMult(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof AggBinaryOp && ((AggBinaryOp)hi).isMatrixMultiply() ) //X%*%Y -> matrix(0, )
		{
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
		
			if(    HopRewriteUtils.isEmpty(left)  //one input empty
				|| HopRewriteUtils.isEmpty(right) )
			{
				//remove unnecessary matrix mult 
				HopRewriteUtils.removeChildReference(parent, hi);
				
				//create datagen and add it to parent
				Hop hnew = HopRewriteUtils.createDataGenOp(left, right, 0);
				HopRewriteUtils.addChildReference(parent, hnew, pos);
				parent.refreshSizeInformation();
				
				hi = hnew;	
				
				LOG.debug("Applied simplifyEmptyMatrixMult");
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyIdentityRepMatrixMult(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof AggBinaryOp && ((AggBinaryOp)hi).isMatrixMultiply() ) //X%*%Y -> X, if y is matrix(1,1,1)
		{
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
			
			// X %*% y -> X
			if( HopRewriteUtils.isDimsKnown(right) && right.get_dim1()==1 && right.get_dim2()==1 && //scalar right
				right instanceof DataGenOp && ((DataGenOp)right).hasConstantValue(1.0)) //matrix(1,)
			{
				HopRewriteUtils.removeChildReference(parent, hi);			
				HopRewriteUtils.addChildReference(parent, left, pos);			
				hi = left;
				
				LOG.debug("Applied simplifyIdentiyMatrixMult");
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyScalarMatrixMult(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof AggBinaryOp && ((AggBinaryOp)hi).isMatrixMultiply() ) //X%*%Y
		{
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
			
			// y %*% X -> as.scalar(y) * X
			if( HopRewriteUtils.isDimsKnown(left) && left.get_dim1()==1 && left.get_dim2()==1 ) //scalar left
			{
				//remove link from parent to matrix mult
				HopRewriteUtils.removeChildReference(parent, hi);
			
				UnaryOp cast = new UnaryOp(left.get_name(), DataType.SCALAR, ValueType.DOUBLE, 
						                   OpOp1.CAST_AS_SCALAR, left);
				HopRewriteUtils.setOutputParameters(cast, 0, 0, 0, 0, 0);
				BinaryOp mult = new BinaryOp(cast.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, cast, right);
				HopRewriteUtils.setOutputParameters(mult, right.get_dim1(), right.get_dim2(), right.get_rows_in_block(), right.get_cols_in_block(), -1);
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				
				//add mult to parent
				HopRewriteUtils.addChildReference(parent, mult, pos);			
				parent.refreshSizeInformation();
				
				hi = mult;
				
				LOG.debug("Applied simplifyScalarMatrixMult1");
			}
			// X %*% y -> X * as.scalar(y)
			else if( HopRewriteUtils.isDimsKnown(right) && right.get_dim1()==1 && right.get_dim2()==1 ) //scalar right
			{
				//remove link from parent to matrix mult
				HopRewriteUtils.removeChildReference(parent, hi);
			
				UnaryOp cast = new UnaryOp(right.get_name(), DataType.SCALAR, ValueType.DOUBLE, 
						                   OpOp1.CAST_AS_SCALAR, right);
				HopRewriteUtils.setOutputParameters(cast, 0, 0, 0, 0, 0);
				BinaryOp mult = new BinaryOp(cast.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, cast, left);
				HopRewriteUtils.setOutputParameters(mult, left.get_dim1(), left.get_dim2(), left.get_rows_in_block(), left.get_cols_in_block(), -1);
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				
				//add mult to parent
				HopRewriteUtils.addChildReference(parent, mult, pos);			
				parent.refreshSizeInformation();
				
				hi = mult;
				
				LOG.debug("Applied simplifyScalarMatrixMult2");
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyMatrixMultDiag(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		Hop hnew = null;
		
		if( hi instanceof AggBinaryOp && ((AggBinaryOp)hi).isMatrixMultiply() ) //X%*%Y
		{
			
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
		
			// diag(X) %*% Y -> X * Y / diag(X) %*% Y -> Y * X 
			// previously rep required for the second case: diag(X) %*% Y -> (X%*%ones) * Y
			if( left instanceof ReorgOp && ((ReorgOp)left).getOp()==ReOrgOp.DIAG //left diag
				&& HopRewriteUtils.isDimsKnown(left) && left.get_dim2()>1 ) //diagV2M
			{
				//System.out.println("diag mm rewrite: dim2(right)="+right.get_dim2());
				
				if( right.get_dim2()==1 ) //right column vector
				{
					//remove link from parent to matrix mult
					HopRewriteUtils.removeChildReference(parent, hi);
					
					//create binary operation over input and right
					Hop input = left.getInput().get(0); //diag input
					hnew = new BinaryOp(input.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, input, right);
					HopRewriteUtils.setOutputParameters(hnew, left.get_dim1(), right.get_dim2(), left.get_rows_in_block(), left.get_cols_in_block(), -1);
				
					LOG.debug("Applied simplifyMatrixMultDiag1");
				}
				else if( right.get_dim2()>1 ) //multi column vector 
				{
					//remove link from parent to matrix mult
					HopRewriteUtils.removeChildReference(parent, hi);
					
					//create binary operation over input and right; in contrast to above rewrite,
					//we need to switch the order because MV binary cell operations require vector on the right
					Hop input = left.getInput().get(0); //diag input
					hnew = new BinaryOp(input.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, right, input);
					HopRewriteUtils.setOutputParameters(hnew, left.get_dim1(), right.get_dim2(), left.get_rows_in_block(), left.get_cols_in_block(), -1);
					
					//NOTE: previously to MV binary cell operations we replicated the left (if moderate number of columns: 2)
					//create binary operation over input and right
					//Hop input = left.getInput().get(0);
					//Hop ones = HopRewriteUtils.createDataGenOpByVal(new LiteralOp("1",1), new LiteralOp(String.valueOf(right.get_dim2()),right.get_dim2()), 1);
					//Hop repmat = new AggBinaryOp( input.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, AggOp.SUM, input, ones );
					//HopRewriteUtils.setOutputParameters(repmat, input.get_dim1(), ones.get_dim2(), input.get_rows_in_block(), input.get_cols_in_block(), -1);
					//hnew = new BinaryOp(input.get_name(), DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, repmat, right);
					//HopRewriteUtils.setOutputParameters(hnew, right.get_dim1(), right.get_dim2(), right.get_rows_in_block(), right.get_cols_in_block(), -1);
				
					LOG.debug("Applied simplifyMatrixMultDiag2");
				}
			}
			
			//notes: similar rewrites would be possible for the right side as well, just transposed into the right alignment
		}
		
		//if one of the above rewrites applied
		if( hnew !=null ){
			//cleanup if only consumer of intermediate
			if( hi.getParent().size()<1 ) 
				HopRewriteUtils.removeAllChildReferences( hi );
			
			//add mult to parent
			HopRewriteUtils.addChildReference(parent, hnew, pos);			
			parent.refreshSizeInformation();
			
			hi = hnew;	
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop simplifyDiagMatrixMult(Hop parent, Hop hi, int pos)
	{
		if( hi instanceof ReorgOp && ((ReorgOp)hi).getOp()==ReOrgOp.DIAG && hi.get_dim2()==1 ) //diagM2V
		{
			Hop hi2 = hi.getInput().get(0);
			if( hi2 instanceof AggBinaryOp && ((AggBinaryOp)hi2).isMatrixMultiply() ) //X%*%Y
			{
				Hop left = hi2.getInput().get(0);
				Hop right = hi2.getInput().get(1);
				
				//remove link from parent to diag
				HopRewriteUtils.removeChildReference(parent, hi);
				
				//remove links to inputs to matrix mult
				//removeChildReference(hi2, left);
				//removeChildReference(hi2, right);
				
				//create new operators (incl refresh size inside for transpose)
				ReorgOp trans = new ReorgOp(right.get_name(), right.get_dataType(), right.get_valueType(), ReOrgOp.TRANSPOSE, right);
				trans.set_rows_in_block(right.get_rows_in_block());
				trans.set_cols_in_block(right.get_cols_in_block());
				BinaryOp mult = new BinaryOp(right.get_name(), right.get_dataType(), right.get_valueType(), OpOp2.MULT, left, trans);
				mult.set_rows_in_block(right.get_rows_in_block());
				mult.set_cols_in_block(right.get_cols_in_block());
				mult.refreshSizeInformation();
				AggUnaryOp rowSum = new AggUnaryOp(right.get_name(), right.get_dataType(), right.get_valueType(), AggOp.SUM, Direction.Row, mult);
				rowSum.set_rows_in_block(right.get_rows_in_block());
				rowSum.set_cols_in_block(right.get_cols_in_block());
				rowSum.refreshSizeInformation();
				
				//rehang new subdag under parent node
				HopRewriteUtils.addChildReference(parent, rowSum, pos);				
				parent.refreshSizeInformation();
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				if( hi2.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hi2 );
				
				hi = rowSum;
				
				LOG.debug("Applied simplifyDiagMatrixMult");
			}	
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyEmptyBinaryOperation(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof BinaryOp ) //b(?) X Y
		{
			BinaryOp bop = (BinaryOp) hi;
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
		
			if( left.get_dataType()==DataType.MATRIX && right.get_dataType()==DataType.MATRIX )
			{
				Hop hnew = null;
				
				//NOTE: these rewrites of binary cell operations need to be aware that right is 
				//potentially a vector but the result is of the size of left
				
				switch( bop.getOp() ){
				    //X * Y -> matrix(0,nrow(X),ncol(X));
					case MULT: {
						if( HopRewriteUtils.isEmpty(left) ) //empty left and size known
							hnew = HopRewriteUtils.createDataGenOp(left, left, 0);
						else if( HopRewriteUtils.isEmpty(right) //empty right and right not a vector
								&& right.get_dim2()>1  ) {
							hnew = HopRewriteUtils.createDataGenOp(right, right, 0);
						}
						else if( HopRewriteUtils.isEmpty(right) )//empty right and right potentially a vector
							hnew = HopRewriteUtils.createDataGenOp(left, left, 0);
						break;
					}
					case PLUS: {
						if( HopRewriteUtils.isEmpty(left) && HopRewriteUtils.isEmpty(right) ) //empty left/right and size known
							hnew = HopRewriteUtils.createDataGenOp(left, left, 0);
						else if( HopRewriteUtils.isEmpty(left) && (left.get_dim2()==1 || right.get_dim2()>1) ) //empty left
							hnew = right;
						else if( HopRewriteUtils.isEmpty(right) ) //empty right
							hnew = left;
						break;
					}
					case MINUS: {
						if( HopRewriteUtils.isEmpty(left) && (left.get_dim2()==1 || right.get_dim2()>1) ) { //empty left
							HopRewriteUtils.removeChildReference(hi, left);
							HopRewriteUtils.addChildReference(hi, new LiteralOp("0",0), 0);
							hnew = hi;
						}
						else if( HopRewriteUtils.isEmpty(right) ) //empty and size known
							hnew = left;
					}
				}
				
				if( hnew != null )
				{
					//remove unnecessary matrix mult 
					HopRewriteUtils.removeChildReference(parent, hi);
					
					//create datagen and add it to parent
					HopRewriteUtils.addChildReference(parent, hnew, pos);
					parent.refreshSizeInformation();
					
					hi = hnew;
					
					LOG.debug("Applied simplifyEmptyBinaryOperation");
				}
			}
		}
		
		return hi;
	}
	
	/**
	 * This is rewrite tries to reorder minus operators from inputs of matrix
	 * multiply to its output because the output is (except for outer products)
	 * usually significantly smaller. Furthermore, this rewrite is a precondition
	 * for the important hops-lops rewrite of transpose-matrixmult if the transpose
	 * is hidden under the minus. 
	 * 
	 * NOTE: in this rewrite we need to modify the links to all parents because we 
	 * remove existing links of subdags and hence affect all consumers.
	 * 
	 * TODO select up or down based on size
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException 
	 */
	private Hop reorderMinusMatrixMult(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof AggBinaryOp && ((AggBinaryOp)hi).isMatrixMultiply() ) //X%*%Y
		{
			Hop hileft = hi.getInput().get(0);
			Hop hiright = hi.getInput().get(1);
			
			if( hileft instanceof BinaryOp && ((BinaryOp)hileft).getOp()==OpOp2.MINUS  //X=-Z
				&& hileft.getInput().get(0) instanceof LiteralOp 
				&& HopRewriteUtils.getDoubleValue((LiteralOp)hileft.getInput().get(0))==0.0 ) 
			{
				Hop hi2 = hileft.getInput().get(1);
				
				//remove link from matrixmult to minus
				HopRewriteUtils.removeChildReference(hi, hileft);
				
				//get old parents (before creating minus over matrix mult)
				ArrayList<Hop> parents = (ArrayList<Hop>) hi.getParent().clone();
				
				//create new operators 
				BinaryOp minus = new BinaryOp(hi.get_name(), hi.get_dataType(), hi.get_valueType(), OpOp2.MINUS, new LiteralOp("0",0), hi);			
				minus.set_rows_in_block(hi.get_rows_in_block());
				minus.set_cols_in_block(hi.get_cols_in_block());
				
				//rehang minus under all parents
				for( Hop p : parents ) {
					int ix = HopRewriteUtils.getChildReferencePos(p, hi);
					HopRewriteUtils.removeChildReference(p, hi);
					HopRewriteUtils.addChildReference(p, minus, ix);
				}
				
				//rehang child of minus under matrix mult
				HopRewriteUtils.addChildReference(hi, hi2, 0);
				
				//cleanup if only consumer of minus
				if( hileft.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hileft );
				
				hi = minus;
				
				LOG.debug("Applied reorderMinusMatrixMult");
			}
			else if( hiright instanceof BinaryOp && ((BinaryOp)hiright).getOp()==OpOp2.MINUS  //X=-Z
					&& hiright.getInput().get(0) instanceof LiteralOp 
					&& HopRewriteUtils.getDoubleValue((LiteralOp)hiright.getInput().get(0))==0.0 ) 
			{
				Hop hi2 = hiright.getInput().get(1);
				
				//remove link from matrixmult to minus
				HopRewriteUtils.removeChildReference(hi, hiright);
				
				//get old parents (before creating minus over matrix mult)
				ArrayList<Hop> parents = (ArrayList<Hop>) hi.getParent().clone();
				
				//create new operators 
				BinaryOp minus = new BinaryOp(hi.get_name(), hi.get_dataType(), hi.get_valueType(), OpOp2.MINUS, new LiteralOp("0",0), hi);			
				minus.set_rows_in_block(hi.get_rows_in_block());
				minus.set_cols_in_block(hi.get_cols_in_block());
				
				//rehang minus under all parents
				for( Hop p : parents ) {
					int ix = HopRewriteUtils.getChildReferencePos(p, hi);
					HopRewriteUtils.removeChildReference(p, hi);
					HopRewriteUtils.addChildReference(p, minus, ix);
				}
				
				//rehang child of minus under matrix mult
				HopRewriteUtils.addChildReference(hi, hi2, 1);
				
				//cleanup if only consumer of minus
				if( hiright.getParent().size()<1 ) 
					HopRewriteUtils.removeAllChildReferences( hiright );
				
				hi = minus;
				
				LOG.debug("Applied reorderMinusMatrixMult");
			}	
		}
		
		return hi;
	}

}
