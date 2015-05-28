/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops;

import com.ibm.bi.dml.lops.Aggregate;
import com.ibm.bi.dml.lops.DataPartition;
import com.ibm.bi.dml.lops.Group;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.RepMat;
import com.ibm.bi.dml.lops.Transform;
import com.ibm.bi.dml.lops.UnaryCP;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.lops.WeightedSquaredLoss;
import com.ibm.bi.dml.lops.WeightedSquaredLoss.WeightsType;
import com.ibm.bi.dml.lops.WeightedSquaredLossR;
import com.ibm.bi.dml.parser.DMLTranslator;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.PDataPartitionFormat;
import com.ibm.bi.dml.runtime.matrix.mapred.DistributedCacheInput;
import com.ibm.bi.dml.sql.sqllops.SQLLops;

/** 
 * Note: this hop should be called AggQuaternaryOp in consistency with AggUnaryOp and AggBinaryOp;
 * however, since there does not exist a real QuaternaryOp yet - we can leave it as is for now. 
 */
public class QuaternaryOp extends Hop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";

	//config influencing mr operator selection (for testing purposes only) 
	public static boolean FORCE_REP_WSLOSS = false;
	
	private OpOp4 _op = null;
	private boolean _postWeights = false;
	
	private QuaternaryOp() {
		//default constructor for clone
	}
	
	public QuaternaryOp(String l, DataType dt, ValueType vt, Hop.OpOp4 o,
			Hop inX, Hop inU, Hop inV, Hop inW, boolean post) {
		super(Hop.Kind.QuaternaryOp, l, dt, vt);
		_op = o;
		getInput().add(0, inX);
		getInput().add(1, inU);
		getInput().add(2, inV);
		getInput().add(3, inW);
		inX.getParent().add(this);
		inU.getParent().add(this);
		inV.getParent().add(this);
		inW.getParent().add(this);
		
		_postWeights = post;
	}
	
	public OpOp4 getOp(){
		return _op;
	}
	
	@Override
	public Lop constructLops() 
		throws HopsException, LopsException 
	{	
		if (getLops() == null) 
		{
			try 
			{
				ExecType et = optFindExecType();
				WeightsType wtype = checkWeightsType();
				
				switch( _op ) {
					case WSLOSS:
						if( et == ExecType.CP )
							constructCPLopsWeightedSquaredLoss(wtype);
						else if( et == ExecType.MR )
							constructMRLopsWeightedSquaredLoss(wtype);
						else
							throw new HopsException("Unsupported quaternaryop exec type: "+et);
						break;
			
					default:
						throw new HopsException(this.printErrorLocation() + "Unknown QuaternaryOp (" + _op + ") while constructing Lops");
				}
			} 
			catch(LopsException e) {
				throw new HopsException(this.printErrorLocation() + "error constructing lops for QuaternaryOp." , e);
			}
		}
	
		return getLops();
	}

	@Override
	public String getOpString() {
		String s = new String("");
		s += "q(" + HopsOpOp4String.get(_op) + ")";
		return s;
	}

	public void printMe() throws HopsException {
		if (LOG.isDebugEnabled()){
			if (getVisited() != VisitStatus.DONE) {
				super.printMe();
				LOG.debug("  Operation: " + _op);
				for (Hop h : getInput()) {
					h.printMe();
				}
			}
			setVisited(VisitStatus.DONE);
		}
	}

	@Override
	public SQLLops constructSQLLOPs() 
		throws HopsException 
	{
		throw new HopsException("QuaternaryOp does not support SQL lops yet!");
	}

	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}

	/**
	 * 
	 * @param wtype 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructCPLopsWeightedSquaredLoss(WeightsType wtype) 
		throws HopsException, LopsException
	{
		Lop wsloss = new WeightedSquaredLoss(
				getInput().get(0).constructLops(),
				getInput().get(1).constructLops(),
				getInput().get(2).constructLops(),
				getInput().get(3).constructLops(),
				getDataType(), getValueType(), wtype, ExecType.CP);
		
		setOutputDimensions( wsloss );
		setLineNumbers( wsloss );
		setLops( wsloss );
	}
	
	/**
	 * 
	 * @param wtype 
	 * @throws HopsException
	 * @throws LopsException
	 */
	private void constructMRLopsWeightedSquaredLoss(WeightsType wtype) 
		throws HopsException, LopsException
	{
		//NOTE: the common case for wsloss are factors U/V with a rank of 10s to 100s; the current runtime only
		//supports single block outer products (U/V rank <= blocksize, i.e., 1000 by default); we enforce this
		//by applying the hop rewrite for Weighted Squared Loss only if this constraint holds. 
		
		Hop X = getInput().get(0);
		Hop U = getInput().get(1);
		Hop V = getInput().get(2);
		Hop W = getInput().get(3);
		
		//MR operator selection, part1
		double m1Size = OptimizerUtils.estimateSize(U.getDim1(), U.getDim2()); //size U
		double m2Size = OptimizerUtils.estimateSize(V.getDim1(), V.getDim2()); //size V
		boolean isMapWsloss = (wtype == WeightsType.NONE && m1Size+m2Size < OptimizerUtils.getRemoteMemBudgetMap(true)); 
		
		if( !FORCE_REP_WSLOSS && isMapWsloss ) //broadcast
		{
			//partitioning of U
			boolean needPartU = !U.dimsKnown() || U.getDim1() * U.getDim2() > DistributedCacheInput.PARTITION_SIZE;
			Lop lU = U.constructLops();
			if( needPartU ){ //requires partitioning
				lU = new DataPartition(lU, DataType.MATRIX, ValueType.DOUBLE, (m1Size>OptimizerUtils.getLocalMemBudget())?ExecType.MR:ExecType.CP, PDataPartitionFormat.ROW_BLOCK_WISE_N);
				lU.getOutputParameters().setDimensions(U.getDim1(), U.getDim2(), getRowsInBlock(), getColsInBlock(), U.getNnz());
				setLineNumbers(lU);	
			}
			
			//partitioning of V
			boolean needPartV = !V.dimsKnown() || V.getDim1() * V.getDim2() > DistributedCacheInput.PARTITION_SIZE;
			Lop lV = V.constructLops();
			if( needPartV ){ //requires partitioning
				lV = new DataPartition(lV, DataType.MATRIX, ValueType.DOUBLE, (m2Size>OptimizerUtils.getLocalMemBudget())?ExecType.MR:ExecType.CP, PDataPartitionFormat.ROW_BLOCK_WISE_N);
				lV.getOutputParameters().setDimensions(V.getDim1(), V.getDim2(), getRowsInBlock(), getColsInBlock(), V.getNnz());
				setLineNumbers(lV);	
			}
			
			//map-side wsloss always with broadcast
			Lop wsloss = new WeightedSquaredLoss( 
					X.constructLops(), lU, lV, W.constructLops(), 
					DataType.MATRIX, ValueType.DOUBLE, wtype, ExecType.MR);
			wsloss.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(wsloss);
			
			Group grp = new Group(wsloss, Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
			grp.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(grp);
			
			Aggregate agg1 = new Aggregate(grp, HopsAgg2Lops.get(AggOp.SUM), DataType.MATRIX, ValueType.DOUBLE, ExecType.MR);
			agg1.setupCorrectionLocation(CorrectionLocationType.NONE); // aggregation uses kahanSum 
			agg1.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(agg1);
			
			UnaryCP unary1 = new UnaryCP(agg1, HopsOpOp1LopsUS.get(OpOp1.CAST_AS_SCALAR), getDataType(), getValueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			setLineNumbers(unary1);
			setLops(unary1);
		}
		else //general case
		{
			//MR operator selection part 2
			boolean cacheU = !FORCE_REP_WSLOSS && (m1Size < OptimizerUtils.getRemoteMemBudgetReduce());
			boolean cacheV = !FORCE_REP_WSLOSS && ((!cacheU && m2Size < OptimizerUtils.getRemoteMemBudgetReduce()) 
					        || (cacheU && m1Size+m2Size < OptimizerUtils.getRemoteMemBudgetReduce()));
			
			Group grpX = new Group(X.constructLops(), Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
			grpX.getOutputParameters().setDimensions(X.getDim1(), X.getDim2(), X.getRowsInBlock(), X.getColsInBlock(), -1);
			setLineNumbers(grpX);
			
			Lop grpW = W.constructLops();
			if( grpW.getDataType()==DataType.MATRIX ) {
				grpW = new Group(W.constructLops(), Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
				grpW.getOutputParameters().setDimensions(W.getDim1(), W.getDim2(), W.getRowsInBlock(), W.getColsInBlock(), -1);
				setLineNumbers(grpW);
			}
			
			Lop lU = null;
			if( cacheU ) {
				//partitioning of U for read through distributed cache
				boolean needPartU = !U.dimsKnown() || U.getDim1() * U.getDim2() > DistributedCacheInput.PARTITION_SIZE;
				lU = U.constructLops();
				if( needPartU ){ //requires partitioning
					lU = new DataPartition(lU, DataType.MATRIX, ValueType.DOUBLE, (m1Size>OptimizerUtils.getLocalMemBudget())?ExecType.MR:ExecType.CP, PDataPartitionFormat.ROW_BLOCK_WISE_N);
					lU.getOutputParameters().setDimensions(U.getDim1(), U.getDim2(), getRowsInBlock(), getColsInBlock(), U.getNnz());
					setLineNumbers(lU);	
				}
			}
			else {
				//replication of U for shuffle to target block
				Lop offset = createOffsetLop(V, false); //ncol of t(V) -> nrow of V determines num replicates
				lU = new RepMat(U.constructLops(), offset, true, V.getDataType(), V.getValueType());
				lU.getOutputParameters().setDimensions(U.getDim1(), U.getDim2(), 
						U.getRowsInBlock(), U.getColsInBlock(), U.getNnz());
				setLineNumbers(lU);
				
				Group grpU = new Group(lU, Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
				grpU.getOutputParameters().setDimensions(U.getDim1(), U.getDim2(), U.getRowsInBlock(), U.getColsInBlock(), -1);
				setLineNumbers(grpU);
				lU = grpU;
			}
			
			Lop lV = null;
			if( cacheV ) {
				//partitioning of V for read through distributed cache
				boolean needPartV = !V.dimsKnown() || V.getDim1() * V.getDim2() > DistributedCacheInput.PARTITION_SIZE;
				lV = V.constructLops();
				if( needPartV ){ //requires partitioning
					lV = new DataPartition(lV, DataType.MATRIX, ValueType.DOUBLE, (m2Size>OptimizerUtils.getLocalMemBudget())?ExecType.MR:ExecType.CP, PDataPartitionFormat.ROW_BLOCK_WISE_N);
					lV.getOutputParameters().setDimensions(V.getDim1(), V.getDim2(), getRowsInBlock(), getColsInBlock(), V.getNnz());
					setLineNumbers(lV);	
				}
			}
			else {
				//replication of t(V) for shuffle to target block
				Transform ltV = new Transform( V.constructLops(), HopsTransf2Lops.get(ReOrgOp.TRANSPOSE), getDataType(), getValueType(), ExecType.MR);
				ltV.getOutputParameters().setDimensions(V.getDim2(), V.getDim1(), 
						V.getColsInBlock(), V.getRowsInBlock(), V.getNnz());
				setLineNumbers(ltV);
				
				Lop offset = createOffsetLop(U, false); //nrow of U determines num replicates
				lV = new RepMat(ltV, offset, false, V.getDataType(), V.getValueType());
				lV.getOutputParameters().setDimensions(V.getDim2(), V.getDim1(), 
						V.getColsInBlock(), V.getRowsInBlock(), V.getNnz());
				setLineNumbers(lV);
				
				Group grpV = new Group(lV, Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
				grpV.getOutputParameters().setDimensions(V.getDim2(), V.getDim1(), V.getColsInBlock(), V.getRowsInBlock(), -1);
				setLineNumbers(grpV);
				lV = grpV;
			}
			
			//reduce-side wsloss w/ or without broadcast
			Lop wsloss = new WeightedSquaredLossR( 
					grpX, lU, lV, grpW, DataType.MATRIX, ValueType.DOUBLE, wtype, cacheU, cacheV, ExecType.MR);
			wsloss.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(wsloss);
			
			Group grp = new Group(wsloss, Group.OperationTypes.Sort, DataType.MATRIX, ValueType.DOUBLE);
			grp.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(grp);
			
			Aggregate agg1 = new Aggregate(grp, HopsAgg2Lops.get(AggOp.SUM), DataType.MATRIX, ValueType.DOUBLE, ExecType.MR);
			agg1.setupCorrectionLocation(CorrectionLocationType.NONE); // aggregation uses kahanSum 
			agg1.getOutputParameters().setDimensions(1, 1, DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, -1);
			setLineNumbers(agg1);
			
			UnaryCP unary1 = new UnaryCP(agg1, HopsOpOp1LopsUS.get(OpOp1.CAST_AS_SCALAR), getDataType(), getValueType());
			unary1.getOutputParameters().setDimensions(0, 0, 0, 0, -1);
			setLineNumbers(unary1);
			setLops(unary1);
		}
	}
	
	/**
	 * 
	 * @return
	 */
	private WeightsType checkWeightsType()
	{
		WeightsType ret = WeightsType.NONE;
		if( !(getInput().get(3) instanceof LiteralOp) ){
			if( _postWeights )
				ret = WeightsType.POST;
			else
				ret = WeightsType.PRE;
		}
		
		return ret;
	}
	
	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{
		switch( _op ) {
			case WSLOSS: //always scalar output 
				return OptimizerUtils.DOUBLE_SIZE;
			default:
				return 0;
		}
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz ) 
	{
		switch( _op ) {
			case WSLOSS: //no intermediates 
				return 0;
			default:
				return 0;
		}
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
		//MatrixCharacteristics[] mc = memo.getAllInputStats(getInput());
		
		switch( _op ) {
			case WSLOSS: //always scalar output
				ret = null;
				break;
				
			default:
				throw new RuntimeException("Memory for operation (" + _op + ") can not be estimated.");
		}
				
		return ret;
	}
	
	@Override
	protected ExecType optFindExecType() 
		throws HopsException 
	{	
		checkAndSetForcedPlatform();
		
		if( _etypeForced != null ) 			
		{
			_etype = _etypeForced;
		}
		else
		{	
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if ( (getInput().get(0).areDimsBelowThreshold() 
					&& getInput().get(1).areDimsBelowThreshold()
					&& getInput().get(2).areDimsBelowThreshold()
					&& getInput().get(3).areDimsBelowThreshold()) )
				_etype = ExecType.CP;
			else
				_etype = ExecType.MR;
			
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();

			//mark for recompile (forever)
			if( OptimizerUtils.ALLOW_DYN_RECOMPILATION && !dimsKnown(true) && _etype==ExecType.MR )
				setRequiresRecompile();
		}
		
		return _etype;
	}
	
	@Override
	public void refreshSizeInformation()
	{
		switch( _op ) {
			case WSLOSS: //do nothing
				break;
			default:
				break;
		}	
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		QuaternaryOp ret = new QuaternaryOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret._op = _op;
		ret._postWeights = _postWeights;
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( that._kind!=Kind.QuaternaryOp )
			return false;
		
		QuaternaryOp that2 = (QuaternaryOp)that;
		
		//compare basic inputs and weights (always existing)
		boolean ret = (_op == that2._op
				&& getInput().get(0) == that2.getInput().get(0)
				&& getInput().get(1) == that2.getInput().get(1)
				&& getInput().get(2) == that2.getInput().get(2)
				&& getInput().get(3) == that2.getInput().get(3));
		
		//compare parameters
		ret &= _postWeights == that2._postWeights;
		
		return ret;
	}
}