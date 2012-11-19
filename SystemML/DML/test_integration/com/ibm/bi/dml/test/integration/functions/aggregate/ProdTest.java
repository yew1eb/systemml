package com.ibm.bi.dml.test.integration.functions.aggregate;

import org.junit.Test;

import com.ibm.bi.dml.parser.ParseException;
import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;


/**
 * <p><b>Positive tests:</b></p>
 * <ul>
 *  <li>general test</li>
 * </ul>
 * <p><b>Negative tests:</b></p>
 * <ul>
 *  <li>scalar test</li>
 * </ul>
 * 
 * 
 */
public class ProdTest extends AutomatedTestBase {

    private final static String TEST_DIR = "functions/aggregate/";
    private final static String TEST_GENERAL = "General";
    private final static String TEST_SCALAR = "Scalar";


    @Override
    public void setUp() {
        // positive tests
        addTestConfiguration(TEST_GENERAL, new TestConfiguration(TEST_DIR, "ProdTest", new String[] { "vector_prod",
                "matrix_prod" }));
        
        // negative tests
        addTestConfiguration(TEST_SCALAR, new TestConfiguration(TEST_DIR, "ProdScalarTest", new String[] {
                "vector_prod", "matrix_prod" }));
    }

    @Test
    public void testGeneral() {
        int rows = 10;
        int cols = 10;

        TestConfiguration config = getTestConfiguration(TEST_GENERAL);
        config.addVariable("rows", rows);
        config.addVariable("cols", cols);

        loadTestConfiguration(TEST_GENERAL);

        createHelperMatrix();
        double[][] vector = getRandomMatrix(rows, 1, 0, 1, 1, -1);
        double vectorSum = 1;
        for (int i = 0; i < rows; i++) {
            vectorSum *= vector[i][0];
        }
        writeInputMatrix("vector", vector);
        writeExpectedHelperMatrix("vector_prod", vectorSum);

        double[][] matrix = getRandomMatrix(rows, cols, 0, 1, 1, -1);
        double matrixSum = 1;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrixSum *= matrix[i][j];
            }
        }
        writeInputMatrix("matrix", matrix);
        writeExpectedHelperMatrix("matrix_prod", matrixSum);

        runTest();

        compareResults();
    }

    @Test
    public void testScalar() {
        int scalar = 12;

        TestConfiguration config = getTestConfiguration(TEST_SCALAR);
        config.addVariable("scalar", scalar);

        createHelperMatrix();

        loadTestConfiguration(TEST_SCALAR);

        runTest(true, ParseException.class);
    }

}
