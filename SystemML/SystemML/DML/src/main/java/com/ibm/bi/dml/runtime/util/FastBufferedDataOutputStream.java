/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.util;

import java.io.DataOutput;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.ibm.bi.dml.runtime.matrix.io.MatrixBlockDSMDataOutput;
import com.ibm.bi.dml.runtime.matrix.io.SparseRow;

/**
 * This buffered output stream is essentially a merged copy of the IBM JDK
 * BufferedOutputStream and DataOutputStream, wrt SystemML requirements.
 * 
 * Micro-benchmarks showed a 25% performance improvement for local write binary block
 * due to the following advantages: 
 * - 1) unsynchronized buffered output stream (not required in SystemML since single writer)
 * - 2) single output buffer (avoid two-level buffers of individual streams)
 * - 3) specific support for writing double arrays in a blockwise fashion
 * 
 */
public class FastBufferedDataOutputStream extends FilterOutputStream implements DataOutput, MatrixBlockDSMDataOutput
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
	                                         "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
		
	protected byte[] _buff;
	protected int _bufflen;
	protected int _count;
	protected OutputStream _out;
	    
	public FastBufferedDataOutputStream(OutputStream out) 
	{
		this(out, 8192);
	}

	public FastBufferedDataOutputStream(OutputStream out, int size) 
	{
		super(out);
		
	    if (size <= 0) 
	    	throw new IllegalArgumentException("Buffer size <= 0");
	        
		_buff = new byte[size];
		_bufflen = size;
	}

    public void write(int b) 
    	throws IOException 
    {
		if (_count >= _bufflen) {
		    flushBuffer();
		}
		_buff[_count++] = (byte)b;
    }

    public void write(byte b[], int off, int len) 
    	throws IOException 
    {
		if (len >= _bufflen) {
		    flushBuffer();
		    out.write(b, off, len);
		    return;
		}
		if (len > _bufflen - _count) {
		    flushBuffer();
		}
		System.arraycopy(b, off, _buff, _count, len);
		_count += len;
    }
	    
    public void flush() 
    	throws IOException 
    {
        flushBuffer();
        out.flush();
    }
    
    private void flushBuffer() 
    	throws IOException 
    {
        if(_count > 0) 
        {
		    out.write(_buff, 0, _count);
		    _count = 0;
        }
    }

    
    /////////////////////////////
    // DataOutput Implementation
    /////////////////////////////
    
	@Override
	public void writeBoolean(boolean v) 
		throws IOException 
	{
		if (_count >= _bufflen) {
		    flushBuffer();
		}
		_buff[_count++] = (byte)(v ? 1 : 0);
	}


	@Override
	public void writeInt(int v) 
		throws IOException 
	{
		if (_count+4 > _bufflen) {
		    flushBuffer();
		}
		_buff[_count++] = (byte)((v >>> 24) & 0xFF);
		_buff[_count++] = (byte)((v >>> 16) & 0xFF);
		_buff[_count++] = (byte)((v >>>  8) & 0xFF);
		_buff[_count++] = (byte)((v >>>  0) & 0xFF);
	}
	

	@Override
	public void writeLong(long v) 
		throws IOException 
	{
		if (_count+8 > _bufflen) {
		    flushBuffer();
		}
		
		_buff[_count++] = (byte)((v >>> 56) & 0xFF);
		_buff[_count++] = (byte)((v >>> 48) & 0xFF);
		_buff[_count++] = (byte)((v >>> 40) & 0xFF);
		_buff[_count++] = (byte)((v >>> 32) & 0xFF);
		_buff[_count++] = (byte)((v >>> 24) & 0xFF);
		_buff[_count++] = (byte)((v >>> 16) & 0xFF);
		_buff[_count++] = (byte)((v >>>  8) & 0xFF);
		_buff[_count++] = (byte)((v >>>  0) & 0xFF);
	}
	
	@Override
	public void writeDouble(double v) 
		throws IOException 
	{
		if (_count+8 > _bufflen) {
		    flushBuffer();
		}
		
		long tmp = Double.doubleToRawLongBits(v);		
		_buff[_count++] = (byte)((tmp >>> 56) & 0xFF);
		_buff[_count++] = (byte)((tmp >>> 48) & 0xFF);
		_buff[_count++] = (byte)((tmp >>> 40) & 0xFF);
		_buff[_count++] = (byte)((tmp >>> 32) & 0xFF);
		_buff[_count++] = (byte)((tmp >>> 24) & 0xFF);
		_buff[_count++] = (byte)((tmp >>> 16) & 0xFF);
		_buff[_count++] = (byte)((tmp >>>  8) & 0xFF);
		_buff[_count++] = (byte)((tmp >>>  0) & 0xFF);		
	}

	@Override
	public void writeByte(int v) throws IOException {
		if (_count+1 > _bufflen) {
		    flushBuffer();
		}
		_buff[_count++] = (byte) v;	
	}

	@Override
	public void writeBytes(String s) throws IOException {
		throw new IOException("Not supported.");
	}

	@Override
	public void writeChar(int v) throws IOException {
		throw new IOException("Not supported.");
	}

	@Override
	public void writeChars(String s) throws IOException {
		throw new IOException("Not supported.");
	}
	
	@Override
	public void writeFloat(float v) throws IOException {
		throw new IOException("Not supported.");
	}

	@Override
	public void writeShort(int v) throws IOException {
		throw new IOException("Not supported.");
	}

	@Override
	public void writeUTF(String s) throws IOException {
		throw new IOException("Not supported.");
	}


    ///////////////////////////////////////////////
    // Implementation of MatrixBlockDSMDataOutput
    ///////////////////////////////////////////////	
	
	private static final int BLOCK_NVALS = 512;
	private static final int BLOCK_NBYTES = BLOCK_NVALS*8; //4KB
	
	@Override
	public void writeDoubleArray(int len, double[] varr) 
		throws IOException
	{
		if( _bufflen >=  BLOCK_NBYTES) //blockwise if buffer large enough
		{
			long tmp = -1;
			int i, j;
			
			//process full blocks of BLOCK_NVALS values 
			for( i=0; i<len-BLOCK_NVALS; i+=BLOCK_NVALS )
			{
				if (_count+BLOCK_NBYTES > _bufflen) 
				    flushBuffer();
				
				for( j=0; j<BLOCK_NVALS; j++ )
				{
					tmp = Double.doubleToRawLongBits(varr[i+j]);
					_buff[_count++] = (byte)((tmp >>> 56) & 0xFF);
					_buff[_count++] = (byte)((tmp >>> 48) & 0xFF);
					_buff[_count++] = (byte)((tmp >>> 40) & 0xFF);
					_buff[_count++] = (byte)((tmp >>> 32) & 0xFF);
					_buff[_count++] = (byte)((tmp >>> 24) & 0xFF);
					_buff[_count++] = (byte)((tmp >>> 16) & 0xFF);
					_buff[_count++] = (byte)((tmp >>>  8) & 0xFF);
					_buff[_count++] = (byte)((tmp >>>  0) & 0xFF);	
				}
			}
			
			//process remaining values of the last block
			//(not relevant for performance, since at most BLOCK_NVALS-1 values)
			for(  ; i<len; i++ )
				writeDouble(varr[i]);
		}
		else //value wise (general case for small buffers)
		{
			for( int i=0; i<len; i++ )
				writeDouble(varr[i]);
		}
	}

	@Override
	public void writeSparseRows(int rlen, SparseRow[] rows) 
		throws IOException
	{
		int lrlen = Math.min(rows.length, rlen);
		int i; //used for two consecutive loops
		
		//process existing rows
		for( i=0; i<lrlen; i++ )
		{
			SparseRow arow = rows[i];
			if( arow!=null && arow.size()>0 )
			{
				int alen = arow.size();
				int alen2 = alen*12;
				int[] aix = arow.getIndexContainer();
				double[] avals = arow.getValueContainer();
				
				writeInt( alen );
				
				if( alen2 < _bufflen )
				{
					if (_count+alen2 > _bufflen) 
					    flushBuffer();
					
					for( int j=0; j<alen; j++ )
					{
						int tmp1 = aix[j];
						_buff[_count+0 ] = (byte)((tmp1 >>> 24) & 0xFF);
						_buff[_count+1 ] = (byte)((tmp1 >>> 16) & 0xFF);
						_buff[_count+2 ] = (byte)((tmp1 >>>  8) & 0xFF);
						_buff[_count+3 ] = (byte)((tmp1 >>>  0) & 0xFF);
						
						long tmp2 = Double.doubleToRawLongBits(avals[j]);
						_buff[_count+4 ] = (byte)((tmp2 >>> 56) & 0xFF);
						_buff[_count+5 ] = (byte)((tmp2 >>> 48) & 0xFF);
						_buff[_count+6 ] = (byte)((tmp2 >>> 40) & 0xFF);
						_buff[_count+7 ] = (byte)((tmp2 >>> 32) & 0xFF);
						_buff[_count+8 ] = (byte)((tmp2 >>> 24) & 0xFF);
						_buff[_count+9 ] = (byte)((tmp2 >>> 16) & 0xFF);
						_buff[_count+10] = (byte)((tmp2 >>>  8) & 0xFF);
						_buff[_count+11] = (byte)((tmp2 >>>  0) & 0xFF);
						
						_count += 12;
					}
				}
				else
				{
					//row does not fit in buffer
					for( int j=0; j<alen; j++ )
					{
						writeInt( aix[j] );
						writeDouble( avals[j] );
					}
				}	
			}
			else 
				writeInt( 0 );
		}
		
		//process remaining empty rows
		for( ; i<rlen; i++ )
			writeInt( 0 );
	}
	
}