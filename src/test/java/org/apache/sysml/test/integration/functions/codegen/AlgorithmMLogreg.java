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

package org.apache.sysml.test.integration.functions.codegen;

import java.io.File;
import java.util.HashMap;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.DMLScript.RUNTIME_PLATFORM;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.runtime.matrix.data.MatrixValue.CellIndex;
import org.apache.sysml.test.integration.AutomatedTestBase;
import org.apache.sysml.test.integration.TestConfiguration;
import org.apache.sysml.test.utils.TestUtils;

public class AlgorithmMLogreg extends AutomatedTestBase 
{	
	private final static String TEST_NAME1 = "Algorithm_MLogreg";
	private final static String TEST_DIR = "functions/codegen/";
	private final static String TEST_CLASS_DIR = TEST_DIR + AlgorithmMLogreg.class.getSimpleName() + "/";
	private final static String TEST_CONF = "SystemML-config-codegen.xml";
	private final static File   TEST_CONF_FILE = new File(SCRIPT_DIR + TEST_DIR, TEST_CONF);
	
	private final static double eps = 1e-5;
	
	private final static int rows = 3468;
	private final static int cols = 327;
		
	private final static double sparsity1 = 0.7; //dense
	private final static double sparsity2 = 0.1; //sparse
	
	private final static int intercept = 0;
	private final static double epsilon = 0.000000001;
	private final static double maxiter = 10;
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] { "w" })); 
	}

	@Test
	public void testMlogregBinDenseRewritesCP() {
		runMlogregTest(TEST_NAME1, 2, true, false, ExecType.CP);
	}
	
	@Test
	public void testMlogregBinSparseRewritesCP() {
		runMlogregTest(TEST_NAME1, 2, true, true, ExecType.CP);
	}
	
	@Test
	public void testMlogregBinDenseCP() {
		runMlogregTest(TEST_NAME1, 2, false, false, ExecType.CP);
	}
	
	@Test
	public void testMlogregBinSparseCP() {
		runMlogregTest(TEST_NAME1, 2, false, true, ExecType.CP);
	}
	
	@Test
	public void testMlogregMulDenseRewritesCP() {
		runMlogregTest(TEST_NAME1, 5, true, false, ExecType.CP);
	}
	
	@Test
	public void testMlogregMulSparseRewritesCP() {
		runMlogregTest(TEST_NAME1, 5, true, true, ExecType.CP);
	}
	
	@Test
	public void testMlogregMulDenseCP() {
		runMlogregTest(TEST_NAME1, 5, false, false, ExecType.CP);
	}
	
	@Test
	public void testMlogregMulSparseCP() {
		runMlogregTest(TEST_NAME1, 5, false, true, ExecType.CP);
	}

	@Test
	public void testMlogregBinDenseRewritesSP() {
		runMlogregTest(TEST_NAME1, 2, true, false, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregBinSparseRewritesSP() {
		runMlogregTest(TEST_NAME1, 2, true, true, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregBinDenseSP() {
		runMlogregTest(TEST_NAME1, 2, false, false, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregBinSparseSP() {
		runMlogregTest(TEST_NAME1, 2, false, true, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregMulDenseRewritesSP() {
		runMlogregTest(TEST_NAME1, 5, true, false, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregMulSparseRewritesSP() {
		runMlogregTest(TEST_NAME1, 5, true, true, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregMulDenseSP() {
		runMlogregTest(TEST_NAME1, 5, false, false, ExecType.SPARK);
	}
	
	@Test
	public void testMlogregMulSparseSP() {
		runMlogregTest(TEST_NAME1, 5, false, true, ExecType.SPARK);
	}
	
	private void runMlogregTest( String testname, int classes, boolean rewrites, boolean sparse, ExecType instType)
	{
		boolean oldFlag = OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION;
		RUNTIME_PLATFORM platformOld = rtplatform;
		switch( instType ){
			case MR: rtplatform = RUNTIME_PLATFORM.HADOOP; break;
			case SPARK: rtplatform = RUNTIME_PLATFORM.SPARK; break;
			default: rtplatform = RUNTIME_PLATFORM.HYBRID_SPARK; break;
		}
	
		boolean sparkConfigOld = DMLScript.USE_LOCAL_SPARK_CONFIG;
		if( rtplatform == RUNTIME_PLATFORM.SPARK || rtplatform == RUNTIME_PLATFORM.HYBRID_SPARK )
			DMLScript.USE_LOCAL_SPARK_CONFIG = true;

		try
		{
			String TEST_NAME = testname;
			TestConfiguration config = getTestConfiguration(TEST_NAME);
			loadTestConfiguration(config);
			
			/* This is for running the junit test the new way, i.e., construct the arguments directly */
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + TEST_NAME + ".dml";
			programArgs = new String[]{ "-explain", "-stats",
				"-args", input("X"), input("Y"),
				String.valueOf(intercept), String.valueOf(epsilon),
				String.valueOf(maxiter), output("w")};

			rCmd = getRCmd(inputDir(), String.valueOf(intercept),String.valueOf(epsilon),
				String.valueOf(maxiter), expectedDir());

			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = rewrites;
			
			//generate actual datasets
			double[][] X = getRandomMatrix(rows, cols, 0, 1, sparse?sparsity2:sparsity1, 2384);
			writeInputMatrixWithMTD("X", X, true);
			double[][] y = TestUtils.round(getRandomMatrix(rows, 1, 0.51, classes+0.49, 1.0, 9283));
			writeInputMatrixWithMTD("Y", y, true);
			
			runTest(true, false, null, -1); 
			runRScript(true); 
			
			//compare matrices 
			HashMap<CellIndex, Double> dmlfile = readDMLMatrixFromHDFS("w");
			HashMap<CellIndex, Double> rfile  = readRMatrixFromFS("w");
			TestUtils.compareMatrices(dmlfile, rfile, eps, "Stat-DML", "Stat-R");
			Assert.assertTrue(heavyHittersContainsSubString("spoof") || heavyHittersContainsSubString("sp_spoof"));
		}
		finally {
			rtplatform = platformOld;
			DMLScript.USE_LOCAL_SPARK_CONFIG = sparkConfigOld;
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = oldFlag;
			OptimizerUtils.ALLOW_AUTO_VECTORIZATION = true;
			OptimizerUtils.ALLOW_OPERATOR_FUSION = true;
		}
	}

	/**
	 * Override default configuration with custom test configuration to ensure
	 * scratch space and local temporary directory locations are also updated.
	 */
	@Override
	protected File getConfigTemplateFile() {
		// Instrumentation in this test's output log to show custom configuration file used for template.
		System.out.println("This test case overrides default configuration with " + TEST_CONF_FILE.getPath());
		return TEST_CONF_FILE;
	}
}
