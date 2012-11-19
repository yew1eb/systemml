package com.ibm.bi.dml.test.integration.functions.binary.scalar;

import org.junit.Test;

import com.ibm.bi.dml.parser.ParseException;
import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;



/**
 * <p><b>Positive tests:</b></p>
 * <ul>
 * 	<li>operand 1 constant, operand 2 constant (int int, int double, double double, double int)</li>
 * 	<li>operand 1 variable, operand 2 constant (int int, int double, double double, double int)</li>
 * 	<li>operand 1 constant, operand 2 variable (int int, int double, double double, double int)</li>
 * 	<li>operand 1 variable, operand 2 variable (int int, int double, double double, double int)</li>
 * </ul>
 * <p><b>Negative tests:</b></p>
 * <ul>
 * 	<li>boolean operand</li>
 * </ul>
 * 
 * 
 */
public class AdditionTest extends AutomatedTestBase {

	@Override
	public void setUp() {
		baseDirectory = SCRIPT_DIR + "functions/binary/scalar/";
		
		// positive tests
		availableTestConfigurations.put("ConstConstTest", new TestConfiguration("AdditionTest",
				new String[] { "int_int", "int_double", "double_double", "double_double"}));
		availableTestConfigurations.put("VarConstTest", new TestConfiguration("AdditionTest",
				new String[] { "int_int", "int_double", "double_double", "double_double"}));
		availableTestConfigurations.put("ConstVarTest", new TestConfiguration("AdditionTest",
				new String[] { "int_int", "int_double", "double_double", "double_double"}));
		availableTestConfigurations.put("VarVarTest", new TestConfiguration("AdditionTest",
				new String[] { "int_int", "int_double", "double_double", "double_double"}));
		
		// negative tests
		availableTestConfigurations.put("BooleanTest", new TestConfiguration("AdditionSingleTest",
				new String[] { "out" }));
	}
	
	@Test
	public void testConstConst() {
		int intIntValue1 = 2;
		int intIntValue2 = 3;
		int intDoubleValue1 = 2;
		double intDoubleValue2 = 3;
		double doubleDoubleValue1 = 2;
		double doubleDoubleValue2 = 3;
		double doubleIntValue1 = 2;
		int doubleIntValue2 = 3;
		
		TestConfiguration config = availableTestConfigurations.get("ConstConstTest");
		config.addVariable("intintvardeclaration", "");
		config.addVariable("intintop1", intIntValue1);
		config.addVariable("intintop2", intIntValue2);
		config.addVariable("intdoublevardeclaration", "");
		config.addVariable("intdoubleop1", intDoubleValue1);
		config.addVariable("intdoubleop2", intDoubleValue2);
		config.addVariable("doubledoublevardeclaration", "");
		config.addVariable("doubledoubleop1", doubleDoubleValue1);
		config.addVariable("doubledoubleop2", doubleDoubleValue2);
		config.addVariable("doubleintvardeclaration", "");
		config.addVariable("doubleintop1", doubleIntValue1);
		config.addVariable("doubleintop2", doubleIntValue2);
		
		loadTestConfiguration("ConstConstTest");
		
		double computedIntIntValue = intIntValue1 + intIntValue2;
		double computedIntDoubleValue = intDoubleValue1 + intDoubleValue2;
		double computedDoubleDoubleValue = doubleDoubleValue1 + doubleDoubleValue2;
		double computedDoubleIntValue = doubleIntValue1 + doubleIntValue2;
		
		createHelperMatrix();
		writeExpectedHelperMatrix("int_int", computedIntIntValue);
		writeExpectedHelperMatrix("int_double", computedIntDoubleValue);
		writeExpectedHelperMatrix("double_double", computedDoubleDoubleValue);
		writeExpectedHelperMatrix("double_int", computedDoubleIntValue);
		
		runTest();
		
		compareResults();
	}
	
	@Test
	public void testVarConst() {
		int intIntValue1 = 2;
		int intIntValue2 = 3;
		int intDoubleValue1 = 2;
		double intDoubleValue2 = 3;
		double doubleDoubleValue1 = 2;
		double doubleDoubleValue2 = 3;
		double doubleIntValue1 = 2;
		int doubleIntValue2 = 3;
		
		TestConfiguration config = availableTestConfigurations.get("VarConstTest");
		config.addVariable("intintvardeclaration", "IntIntVar = " + intIntValue1 + ";");
		config.addVariable("intintop1", "IntIntVar");
		config.addVariable("intintop2", intIntValue2);
		config.addVariable("intdoublevardeclaration", "IntDoubleVar = " + intDoubleValue1 + ";");
		config.addVariable("intdoubleop1", "IntDoubleVar");
		config.addVariable("intdoubleop2", intDoubleValue2);
		config.addVariable("doubledoublevardeclaration", "DoubleDoubleVar = " + doubleDoubleValue1 + ";");
		config.addVariable("doubledoubleop1", "DoubleDoubleVar");
		config.addVariable("doubledoubleop2", doubleDoubleValue2);
		config.addVariable("doubleintvardeclaration", "DoubleIntVar = " + doubleIntValue1 + ";");
		config.addVariable("doubleintop1", "DoubleIntVar");
		config.addVariable("doubleintop2", doubleIntValue2);
		
		loadTestConfiguration("VarConstTest");
		
		double computedIntIntValue = intIntValue1 + intIntValue2;
		double computedIntDoubleValue = intDoubleValue1 + intDoubleValue2;
		double computedDoubleDoubleValue = doubleDoubleValue1 + doubleDoubleValue2;
		double computedDoubleIntValue = doubleIntValue1 + doubleIntValue2;
		
		createHelperMatrix();
		writeExpectedHelperMatrix("int_int", computedIntIntValue);
		writeExpectedHelperMatrix("int_double", computedIntDoubleValue);
		writeExpectedHelperMatrix("double_double", computedDoubleDoubleValue);
		writeExpectedHelperMatrix("double_int", computedDoubleIntValue);
		
		runTest();
		
		compareResults();
	}
	
	@Test
	public void testConstVar() {
		int intIntValue1 = 2;
		int intIntValue2 = 3;
		int intDoubleValue1 = 2;
		double intDoubleValue2 = 3;
		double doubleDoubleValue1 = 2;
		double doubleDoubleValue2 = 3;
		double doubleIntValue1 = 2;
		int doubleIntValue2 = 3;
		
		TestConfiguration config = availableTestConfigurations.get("ConstVarTest");
		config.addVariable("intintvardeclaration", "IntIntVar = " + intIntValue2 + ";");
		config.addVariable("intintop1", intIntValue1);
		config.addVariable("intintop2", "IntIntVar");
		config.addVariable("intdoublevardeclaration", "IntDoubleVar = " + intDoubleValue2 + ";");
		config.addVariable("intdoubleop1", intDoubleValue1);
		config.addVariable("intdoubleop2", "IntDoubleVar");
		config.addVariable("doubledoublevardeclaration", "DoubleDoubleVar = " + doubleDoubleValue2 + ";");
		config.addVariable("doubledoubleop1", doubleDoubleValue1);
		config.addVariable("doubledoubleop2", "DoubleDoubleVar");
		config.addVariable("doubleintvardeclaration", "DoubleIntVar = " + doubleIntValue2 + ";");
		config.addVariable("doubleintop1", doubleIntValue1);
		config.addVariable("doubleintop2", "DoubleIntVar");
		
		loadTestConfiguration("ConstVarTest");
		
		double computedIntIntValue = intIntValue1 + intIntValue2;
		double computedIntDoubleValue = intDoubleValue1 + intDoubleValue2;
		double computedDoubleDoubleValue = doubleDoubleValue1 + doubleDoubleValue2;
		double computedDoubleIntValue = doubleIntValue1 + doubleIntValue2;
		
		createHelperMatrix();
		writeExpectedHelperMatrix("int_int", computedIntIntValue);
		writeExpectedHelperMatrix("int_double", computedIntDoubleValue);
		writeExpectedHelperMatrix("double_double", computedDoubleDoubleValue);
		writeExpectedHelperMatrix("double_int", computedDoubleIntValue);
		
		runTest();
		
		compareResults();
	}
	
	@Test
	public void testVarVar() {
		int intIntValue1 = 2;
		int intIntValue2 = 3;
		int intDoubleValue1 = 2;
		double intDoubleValue2 = 3;
		double doubleDoubleValue1 = 2;
		double doubleDoubleValue2 = 3;
		double doubleIntValue1 = 2;
		int doubleIntValue2 = 3;
		
		TestConfiguration config = availableTestConfigurations.get("VarVarTest");
		config.addVariable("intintvardeclaration", "IntIntVar1 = " + intIntValue1 + ";" +
				"IntIntVar2 = " + intIntValue2 + ";");
		config.addVariable("intintop1", "IntIntVar1");
		config.addVariable("intintop2", "IntIntVar2");
		config.addVariable("intdoublevardeclaration", "IntDoubleVar1 = " + intDoubleValue1 + ";" +
				"IntDoubleVar2 = " + intDoubleValue2 + ";");
		config.addVariable("intdoubleop1", "IntDoubleVar1");
		config.addVariable("intdoubleop2", "IntDoubleVar2");
		config.addVariable("doubledoublevardeclaration", "DoubleDoubleVar1 = " + doubleDoubleValue1 + ";" +
				"DoubleDoubleVar2 = " + doubleDoubleValue2 + ";");
		config.addVariable("doubledoubleop1", "DoubleDoubleVar1");
		config.addVariable("doubledoubleop2", "DoubleDoubleVar2");
		config.addVariable("doubleintvardeclaration", "DoubleIntVar1 = " + doubleIntValue1 + ";" +
				"DoubleIntVar2 = " + doubleIntValue2 + ";");
		config.addVariable("doubleintop1", "DoubleIntVar1");
		config.addVariable("doubleintop2", "DoubleIntVar2");
		
		loadTestConfiguration("VarVarTest");
		
		double computedIntIntValue = intIntValue1 + intIntValue2;
		double computedIntDoubleValue = intDoubleValue1 + intDoubleValue2;
		double computedDoubleDoubleValue = doubleDoubleValue1 + doubleDoubleValue2;
		double computedDoubleIntValue = doubleIntValue1 + doubleIntValue2;
		
		createHelperMatrix();
		writeExpectedHelperMatrix("int_int", computedIntIntValue);
		writeExpectedHelperMatrix("int_double", computedIntDoubleValue);
		writeExpectedHelperMatrix("double_double", computedDoubleDoubleValue);
		writeExpectedHelperMatrix("double_int", computedDoubleIntValue);
		
		runTest();
		
		compareResults();
	}
	
	@Test
	public void testBoolean() {
		TestConfiguration config = availableTestConfigurations.get("BooleanTest");
		config.addVariable("op1", "true");
		config.addVariable("op2", 1);
		
		loadTestConfiguration("BooleanTest");
		
		runTest(true, ParseException.class);
	}

}
