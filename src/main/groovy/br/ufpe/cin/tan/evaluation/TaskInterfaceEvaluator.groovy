package br.ufpe.cin.tan.evaluation

import br.ufpe.cin.tan.analysis.itask.IReal
import br.ufpe.cin.tan.analysis.itask.ITest

class TaskInterfaceEvaluator {

    private static calculateTruePositives(Set set1, Set set2) {
        (set1.intersect(set2)).size()
    }

    /***
     * Calculates precision of test based task interface considering files only.
     *
     * @param iTest task interface based in test code
     * @param iReal task interface computed after task is done
     * @return value between 0 and 1
     */
    static double calculateFilesPrecision(ITest iTest, IReal iReal) {
        double result = 0
        if (!iTest || iTest.empty || !iReal || iReal.empty) return 0
        def testFiles = iTest.findFilteredFiles()
        def truePositives = calculateTruePositives(testFiles, iReal.findFilteredFiles())
        if (truePositives > 0) result = (double) truePositives / testFiles.size()
        result
    }

    static double calculateFilesPrecision(Set iTest, Set iReal) {
        double result = 0
        if (!iTest || iTest.empty || !iReal || iReal.empty) return 0
        def truePositives = calculateTruePositives(iTest, iReal)
        if (truePositives > 0) result = (double) truePositives / iTest.size()
        result
    }

    /***
     * Calculates recall of test based task interface considering files only.
     *
     * @param iTest ITest task interface based in test code
     * @param iReal task interface computed after task is done
     * @return value between 0 and 1
     */
    static double calculateFilesRecall(ITest iTest, IReal iReal) {
        double result = 0
        if (!iTest || iTest.empty || !iReal || iReal.empty) return 0
        def realFiles = iReal.findFilteredFiles()
        def truePositives = calculateTruePositives(iTest.findFilteredFiles(), realFiles)
        if (truePositives > 0) result = (double) truePositives / realFiles.size()
        result
    }

    static double calculateFilesRecall(Set iTest, Set iReal) {
        double result = 0
        if (!iTest || iTest.empty || !iReal || iReal.empty) return 0
        def truePositives = calculateTruePositives(iTest, iReal)
        if (truePositives > 0) result = (double) truePositives / iReal.size()
        result
    }

}