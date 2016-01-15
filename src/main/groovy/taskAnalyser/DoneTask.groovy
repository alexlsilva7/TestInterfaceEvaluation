package taskAnalyser

import commitAnalyser.Commit
import groovy.util.logging.Slf4j
import util.Util

/***
 * Represents a done task, that is, a task that contains production and test code. The code is published in a public
 * Git repository in GitHub. The task is used to evaluation study, to validate test-based task interfaces by comparing
 * them with real interfaces.
 */
@Slf4j
class DoneTask extends Task {

    String repositoryIndex
    List<Commit> commits
    List<GherkinFile> changedGherkinFiles
    List<UnitFile> changedUnitFiles

    DoneTask(String repositoryUrl, String id, List<String> shas){
        super(repositoryUrl, true, id)
        this.changedGherkinFiles = []
        this.changedUnitFiles = []
        /* retrieves commits code */
        this.commits = gitRepository.searchBySha(*shas)
    }

    DoneTask(String repositoryIndex, String repositoryUrl, String id, List<Commit> commits) {
        super(repositoryUrl, true, id)
        this.repositoryIndex = repositoryIndex
        this.changedGherkinFiles = []
        this.changedUnitFiles = []
        /* retrieves commits code */
        this.commits = gitRepository.searchBySha(*(commits*.hash))
    }

    /***
     * Computes task interface based in unit test code.
     * It can be seen as a future refinement for task interface.
     * @return task interface
     */
    @Deprecated
    TaskInterface computeUnitTestBasedInterface(){
        log.info "TASK ID: $id"
        def interfaces = []

        /* identifies changed unit test files */
        List<Commit> commitsChangedRspecFile = commits.findAll{ !it.unitChanges.isEmpty() }

        commitsChangedRspecFile?.each{ commit ->
            /* resets repository to the state of the commit to extract changes */
            gitRepository.reset(commit.hash)

            /* translates changed lines in unit test files to changed unit tests */
            List<UnitFile> changes = gitRepository.identifyChangedUnitTestContent(commit)

            if(!changes.isEmpty()){
                changedUnitFiles += changes

                /* computes task interface based on the production code exercised by tests */
                interfaces += testCodeParser.computeInterfaceForDoneTaskByUnitTest(changes)
            }
            else{
                log.info "No changes in unit test!\n"
            }
        }

        /* resets repository to last version */
        gitRepository.reset()

        /* collapses step code interfaces to define the interface for the whole task */
        TaskInterface.colapseInterfaces(interfaces)
    }

    /***
     * (TO VALIDATE)
     * Computes task interface based in unit test code.
     * It can be seen as a future refinement for task interface.
     * Changes interpretation are based in the checkout of the last commit of the task. It could introduce error and
     * after validation it should be removed.
     * @return task interface
     */
    @Deprecated
    TaskInterface computeUnitTestBasedInterfaceVersion2(){
        log.info "TASK ID: $id; LAST COMMIT: ${commits?.last()?.hash}"
        TaskInterface taskInterface = new TaskInterface()

        /* identifies changed unit test files */
        List<Commit> commitsChangedRspecFile = commits.findAll{ !it.unitChanges.isEmpty() }

        if(!commitsChangedRspecFile.isEmpty()){
            /* resets repository to the state of the last commit to extract changes */
            gitRepository.reset(commits?.last()?.hash)
            changedUnitFiles = gitRepository.identifyChangedUnitTestContent(commitsChangedRspecFile)

            /* computes task interface based on the production code exercised by tests */
            taskInterface = testCodeParser.computeInterfaceForDoneTaskByUnitTest(changedUnitFiles)

            /* resets repository to last version */
            gitRepository.reset()
        }

        taskInterface
    }

    private static identifyChangedGherkinContent(List<Commit> commitsChangedGherkinFile){
        List<GherkinFile> gherkinFiles = []
        commitsChangedGherkinFile?.each{ commit -> //commits sorted by date
            commit.gherkinChanges?.each{ file ->
                if(!file.changedScenarioDefinitions.isEmpty()){
                    def index = gherkinFiles.findIndexOf{ it.path == file.path }
                    GherkinFile foundFile
                    if(index >= 0) foundFile = gherkinFiles.get(index)

                    //another previous commit changed the same gherkin file
                    if(foundFile){
                        def previousCommit = commitsChangedGherkinFile.find{ it.hash == foundFile.commitHash }
                        def equalDefs = foundFile.changedScenarioDefinitions.findAll{ it.name in file.changedScenarioDefinitions*.name }
                        def oldDefs = foundFile.changedScenarioDefinitions - equalDefs

                        //previous commit changed the same scenario definitions
                        if(!equalDefs.isEmpty() && previousCommit && commit.date>previousCommit.date){
                            //previous commit changed other(s) scenario definition(s)
                            if(!oldDefs.isEmpty()) {
                                file.changedScenarioDefinitions += oldDefs
                            }
                            gherkinFiles.set(index, file)
                        }
                    } else{
                        gherkinFiles += file
                    }
                }
            }
        }
        gherkinFiles
    }

    /***
     * Computes task interface based in acceptance test code.
     * @return task interface
     */
    @Override
    TaskInterface computeTestBasedInterface(){
        if(!commits || commits.empty) {
            log.warn "TASK ID: $id; NO COMMITS!"
            return new TaskInterface()
        }
        log.info "TASK ID: $id; LAST COMMIT: ${commits?.last()?.hash}"
        def interfaces = []

        List<Commit> commitsChangedGherkinFile = commits?.findAll{ !it.gherkinChanges.isEmpty() }

        // identifies changed gherkin files and scenario definitions
        List<GherkinFile> gherkinFiles = identifyChangedGherkinContent(commitsChangedGherkinFile)

        if(!gherkinFiles.isEmpty()) {
            // resets repository to the state of the last commit to extract changes
            gitRepository.reset(commits?.last()?.hash)

            changedGherkinFiles += gherkinFiles
            // computes task interface based on the production code exercised by tests
            interfaces += testCodeParser.computeInterfaceForDoneTask(gherkinFiles)

        }

        List<Commit> commitsNoGhangedGherkinFile = commits - commitsChangedGherkinFile
        commitsNoGhangedGherkinFile?.each{
            log.info "Commit '${it.hash}' did not change acceptance tests!"
        }

        // resets repository to last version
        gitRepository.reset()

        // collapses step code interfaces to define the interface for the whole task
        TaskInterface.colapseInterfaces(interfaces)
    }

    TaskInterface computeRealInterface(){
        def taskInterface = new TaskInterface()
        if(commits && !commits.empty){
            def files = commits.collect{ commit -> commit.codeChanges*.filename }?.flatten()?.unique()
            def productionFiles = Util.findAllProductionFiles(files)
            productionFiles.each{ file ->
                taskInterface.classes += [name:"", file:gitRepository.name+File.separator+file]
            }
        }
        return taskInterface
    }

}
