package taskAnalyser

import au.com.bytecode.opencsv.CSVWriter
import evaluation.TaskInterfaceEvaluator
import groovy.util.logging.Slf4j
import util.Util

@Slf4j
class Main {

    static analyseTasks(){
        List<DoneTask> tasks = TaskSearchManager.extractProductionAndTestTasksFromCSV()
        log.info "Number of tasks: ${tasks.size()}"

        /* RUBY: TEST INTERFACE BASED ON ACCEPTANCE TEST CODE */
        def gherkinCounter = 0
        def nonEmptyInterfaces = []
        tasks.each{ task ->
            def taskInterface = task.computeTestBasedInterface()
            if(!task.changedGherkinFiles.isEmpty()){
                gherkinCounter++
                if(taskInterface.toString() != ""){
                    nonEmptyInterfaces += [task:task, itest:taskInterface, ireal:task.computeRealInterface()]
                }
            }
        }

        log.info "Number of tasks that changed Gherkin files: $gherkinCounter"
        log.info "Number of non empty task interfaces: ${nonEmptyInterfaces.size()}"
        exportResult(Util.DEFAULT_EVALUATION_FILE, tasks.size(), gherkinCounter, nonEmptyInterfaces)
    }

    static analyseTasks(String filename){
        List<DoneTask> tasks = TaskSearchManager.extractProductionAndTestTasksFromCSV(filename)
        log.info "Number of tasks: ${tasks.size()}"

        /* RUBY: TEST INTERFACE BASED ON ACCEPTANCE TEST CODE */
        def gherkinCounter = 0
        def nonEmptyInterfaces = []
        tasks.each{ task ->
            def taskInterface = task.computeTestBasedInterface()
            if(!task.changedGherkinFiles.isEmpty()){
                gherkinCounter++
                if(taskInterface.toString() != ""){
                    nonEmptyInterfaces += [task:task, itest:taskInterface, ireal:task.computeRealInterface()]
                }
            }
        }

        log.info "Number of tasks that changed Gherkin files: $gherkinCounter"
        log.info "Number of non empty task interfaces: ${nonEmptyInterfaces.size()}"

        File file = new File(filename)
        def outputFile = Util.DEFAULT_EVALUATION_FOLDER+File.separator+file.name
        exportResult(outputFile, tasks.size(), gherkinCounter, nonEmptyInterfaces)
    }

    static exportResult(def allTasksCounter, def tasksCounter,  def taskInterfaces){
        exportResult(Util.DEFAULT_EVALUATION_FILE, allTasksCounter, tasksCounter, taskInterfaces)
    }

    static exportResult(def filename, def allTasksCounter, def tasksCounter, def taskInterfaces){
        CSVWriter writer = new CSVWriter(new FileWriter(filename))
        writer.writeNext("Number of tasks: $allTasksCounter")
        writer.writeNext("Number of tasks that changed Gherkin files: $tasksCounter")
        writer.writeNext("Number of non empty task interfaces: ${taskInterfaces?.size()}")
        String[] header = ["Task","Date","Commit_Message","ITest","IReal","Precision","Recall"]
        writer.writeNext(header)

        taskInterfaces?.each{ entry ->
            def precision = TaskInterfaceEvaluator.calculateFilesPrecision(entry.itest, entry.ireal)
            def recall = TaskInterfaceEvaluator.calculateFilesRecall(entry.itest, entry.ireal)
            def dates =  entry?.task?.commits*.date?.flatten()?.sort()
            if(dates) dates = dates.collect{ new Date(it*1000).format('dd-MM-yyyy') }.unique()
            else dates = []
            def msgs = entry?.task?.commits*.message?.flatten()
            String[] line = [entry.task.id, dates, msgs, entry.itest, entry.ireal, precision, recall]
            writer.writeNext(line)

            /*println "changed test files: "
            println entry.task.commits.collect{ commit -> commit.testChanges*.filename }?.flatten()?.unique()
            println "changed production files: "
            println entry.task.commits.collect{ commit -> commit.productionChanges*.filename }?.flatten()?.unique()*/
        }

        writer.close()
        log.info "The results were saved!"
    }

    public static void main(String[] args){
        /*def cvsFiles = Util.findFilesFromDirectory("tasks")
        cvsFiles?.each{
            log.info "<  Analysing tasks from '$it'  >"
            analyseTasks(it)
        }*/

        analyseTasks()
    }

}
