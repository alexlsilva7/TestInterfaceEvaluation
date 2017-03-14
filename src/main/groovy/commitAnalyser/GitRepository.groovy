package commitAnalyser

import gherkin.ast.ScenarioDefinition
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.BlameCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import taskAnalyser.task.GherkinFile
import taskAnalyser.task.StepDefinition
import taskAnalyser.task.StepDefinitionFile
import taskAnalyser.task.UnitFile
import testCodeAnalyser.TestCodeAbstractParser
import util.ConstantData
import util.RegexUtil
import util.Util
import util.exception.CloningRepositoryException

import java.util.regex.Matcher

/***
 * Represents a git repository to be downloaded for analysis purpose.
 */
@Slf4j
class GitRepository {

    static List<GitRepository> repositories = []
    String url
    String name
    String localPath
    String lastCommit //used only to reset the repository for the original state after checkout command
    Set removedSteps

    private GitRepository(String path) throws CloningRepositoryException {
        this.removedSteps = [] as Set
        if (path.startsWith("http")) {
            this.url = path + ConstantData.GIT_EXTENSION
            this.name = Util.configureGitRepositoryName(url)
            this.localPath = Util.REPOSITORY_FOLDER_PATH + name
            if (isCloned()) {
                this.lastCommit = searchAllRevCommits()?.last()?.name
                log.info "Already cloned from " + url + " to " + localPath
            } else cloneRepository()
        } else {
            this.localPath = path
            this.lastCommit = searchAllRevCommits()?.last()?.name
            def git = Git.open(new File(localPath))
            this.url = git.repository.config.getString("remote", "origin", "url")
            git.close()
            this.name = Util.configureGitRepositoryName(url)
        }
    }

    static GitRepository getRepository(String url) throws CloningRepositoryException {
        def repository = repositories.find { ((it.url - ConstantData.GIT_EXTENSION) == url) }
        if (!repository) {
            repository = new GitRepository(url)
            repositories += repository
        }
        return repository
    }

    /***
     * Verifies if a repository is already cloned
     */
    private isCloned() {
        File dir = new File(localPath)
        File[] files = dir.listFiles()
        if (files && files.length > 0) true
        else false
    }

    /***
     * Clones a repository if it was not cloned yet.
     */
    private cloneRepository() throws CloningRepositoryException {
        try {
            def result = Git.cloneRepository().setURI(url).setDirectory(new File(localPath)).call()
            lastCommit = result?.log()?.call()?.sort { it.commitTime }?.last()?.name
            result.close()
            log.info "Cloned from " + url + " to " + localPath
        } catch (Exception ex) {
            Util.deleteFolder(localPath)
            throw new CloningRepositoryException(ex.message)
        }
    }

    /***
     * Computes the difference between two versions of a file or all files from two commits.
     *
     * @param filename file to evaluate. If it is empty, all differences between commits are computed.
     * @param newCommit the commit that contains a new version of the file.
     * @param oldCommit the commit that contains an older version of the file.
     * @return a list of DiffEntry objects that represents the difference between two versions of a file.
     */
    private List<DiffEntry> extractDiff(String filename, RevCommit newCommit, RevCommit oldCommit) {
        def git = Git.open(new File(localPath))
        def oldTree = prepareTreeParser(git, oldCommit.name)
        def newTree = prepareTreeParser(git, newCommit.name)
        def diffCommand = git.diff().setOldTree(oldTree).setNewTree(newTree)
        if (filename != null && !filename.isEmpty()) diffCommand.setPathFilter(PathFilter.create(filename))
        def diffs = diffCommand.call()
        git.close()

        List<DiffEntry> result = []
        diffs.each {
            it.oldPath = it.oldPath.replaceAll(RegexUtil.FILE_SEPARATOR_REGEX, Matcher.quoteReplacement(File.separator))
            it.newPath = it.newPath.replaceAll(RegexUtil.FILE_SEPARATOR_REGEX, Matcher.quoteReplacement(File.separator))
            result += it
        }

        result.findAll { file -> (Util.isValidFile(file.newPath) || Util.isValidFile(file.oldPath)) }
    }

    /***
     * Prints a file content showing the differences between it and its previous version.
     *
     * @param entry the DiffEntry object that represents the difference between two versions of a file.
     */
    private showDiff(DiffEntry entry) {
        def git = Git.open(new File(localPath))
        ByteArrayOutputStream stream = new ByteArrayOutputStream()
        DiffFormatter formatter = new DiffFormatter(stream)
        formatter.setRepository(git.repository)
        formatter.setDetectRenames(true)
        formatter.setContext(1000) //to show all lines
        formatter.format(entry)
        def lines = stream.toString("UTF-8").readLines()
        def result = lines.getAt(5..lines.size() - 1)
        result.eachWithIndex { val, index ->
            println "($index) $val"
        }
        git.close()
        stream.reset()
        formatter.release()
    }

    private StepDefinitionFile extractStepDefinitionChanges(RevCommit commit, RevCommit parent, DiffEntry entry,
                                                            TestCodeAbstractParser parser) {
        StepDefinitionFile changedStepFile = null
        def newVersion = extractFileContent(commit, entry.newPath)
        def newDefs = StepDefinitionManager.parseStepDefinitionFile(entry.newPath, newVersion, commit.name, parser)
        def oldVersion = extractFileContent(parent, entry.oldPath)
        def oldDefs = StepDefinitionManager.parseStepDefinitionFile(entry.oldPath, oldVersion, parent.name, parser)

        //searches for changed or removed step definitions
        List<StepDefinition> changedStepDefinitions = []
        oldDefs?.each { stepDef ->
            def foundStepDef = newDefs?.find { it.value == stepDef.value }
            if (foundStepDef && foundStepDef.value && foundStepDef.value != "") {
                if (stepDef.size() == foundStepDef.size()) { //step definition might be changed
                    def stepDefEquals = GherkinManager.equals(foundStepDef, stepDef)
                    if (!stepDefEquals) changedStepDefinitions += foundStepDef
                } else {//step definition was changed
                    changedStepDefinitions += foundStepDef
                }
            } //if a step definition was removed, it was not relevant for the task
        }

        //searches for added step definitions
        newDefs?.each { newStepDef ->
            def foundStepDef = oldDefs?.find { it.value == newStepDef.value }
            if (!foundStepDef || !foundStepDef.value || foundStepDef.value == "") {//it was not found because it is new
                changedStepDefinitions += newStepDef
            }
        }

        if (!changedStepDefinitions.isEmpty()) {
            changedStepFile = new StepDefinitionFile(path: entry.newPath, changedStepDefinitions: changedStepDefinitions)
        }

        changedStepFile
    }

    /***
     * Identifies step definitions at added step definition files.
     * It is used only when dealing with done tasks.
     */
    private StepDefinitionFile extractStepDefinitionAdds(RevCommit commit, DiffEntry entry, TestCodeAbstractParser parser) {
        StepDefinitionFile changedStepFile = null
        def newVersion = extractFileContent(commit, entry.newPath)
        def newStepDefinitions = StepDefinitionManager.parseStepDefinitionFile(entry.newPath, newVersion, commit.name, parser)

        if (newStepDefinitions && !newStepDefinitions.isEmpty()) {
            changedStepFile = new StepDefinitionFile(path: entry.newPath, changedStepDefinitions: newStepDefinitions)
        }

        changedStepFile
    }

    private UnitFile extractUnitChanges(RevCommit commit, String path, List<Integer> lines, TestCodeAbstractParser parser) {
        def newVersion = extractFileContent(commit, path)
        UnitTestManager.parseUnitFile(path, newVersion, lines, parser)
    }

    /***
     * Identifies changed scenarios definitions at gherkin files (features).
     * It is used only when dealing with done tasks.
     */
    private GherkinFile extractGherkinChanges(RevCommit commit, RevCommit parent, DiffEntry entry) {
        GherkinFile changedGherkinFile = null

        def newVersion = extractFileContent(commit, entry.newPath)
        def newFeature = GherkinManager.parseGherkinFile(newVersion, entry.newPath, commit.name)
        def oldVersion = extractFileContent(parent, entry.oldPath)
        def oldFeature = GherkinManager.parseGherkinFile(oldVersion, entry.oldPath, parent.name)

        if (!newFeature || !oldFeature) return changedGherkinFile

        def newScenarioDefinitions = newFeature?.scenarioDefinitions
        def oldScenarioDefinitions = oldFeature?.scenarioDefinitions

        //searches for changed or removed scenario definitions
        List<ScenarioDefinition> changedScenarioDefinitions = []
        oldScenarioDefinitions?.each { oldScenDef ->
            def foundScenDef = newScenarioDefinitions?.find { it.name == oldScenDef.name }
            if (foundScenDef) {
                if (oldScenDef.steps.size() == foundScenDef.steps.size()) { //scenario definition might be changed
                    def scenDefEquals = GherkinManager.equals(foundScenDef, oldScenDef)
                    if (!scenDefEquals) changedScenarioDefinitions += foundScenDef
                } else {//scenario definition was changed
                    changedScenarioDefinitions += foundScenDef
                }
            } else { //if a scenario definition was removed, it was not relevant for the task
                log.info "commit ${commit.name} removed scenario from ${entry.newPath}:\n ${oldScenDef.name}"
                oldScenDef.steps.each{
                    log.info "${it.text}; ${entry.newPath} (${it.location.line})"
                    removedSteps += [path: entry.newPath, text: it.text]
                }
            }
        }

        //searches for added scenario definitions
        newScenarioDefinitions?.each { newScenDef ->
            def foundScenDef = oldScenarioDefinitions?.find { it.name == newScenDef.name }
            if (!foundScenDef) {//it was not found because it is new
                changedScenarioDefinitions += newScenDef
            }
        }

        if (!changedScenarioDefinitions.empty) {
            changedGherkinFile = new GherkinFile(path: entry.newPath, feature: newFeature, changedScenarioDefinitions: changedScenarioDefinitions)
        }

        return changedGherkinFile
    }

    /***
     * Identifies scenarios definitions at added gherkin files (features).
     * It is used only when dealing with done tasks.
     */
    private GherkinFile extractGherkinAdds(RevCommit commit, DiffEntry entry) {
        def newVersion = extractFileContent(commit, entry.newPath)
        GherkinManager.extractGherkinAdds(commit, newVersion, entry.newPath)
    }

    private CodeChange configureAddChange(RevCommit commit, DiffEntry entry, TestCodeAbstractParser parser) {
        CodeChange change
        if (Util.isGherkinFile(entry.newPath))
            change = extractGherkinAdds(commit, entry)
        else if (Util.isStepDefinitionFile(entry.newPath))
            change = extractStepDefinitionAdds(commit, entry, parser)
        else {
            def result = extractFileContent(commit, entry.newPath)
            def lines = 0..<result.readLines().size()
            if (Util.isUnitTestFile(entry.newPath)) {
                //change = extractUnitChanges(commit, entry.newPath, lines, parser)
            } else change = new CoreChange(path: entry.newPath, type: entry.changeType, lines: lines)
        }
        change
    }

    private CodeChange configureModifyChange(RevCommit commit, RevCommit parent, DiffEntry entry, TestCodeAbstractParser parser) {
        CodeChange change = null
        if (Util.isGherkinFile(entry.newPath))
            change = extractGherkinChanges(commit, parent, entry)
        else if (Util.isStepDefinitionFile(entry.newPath))
            change = extractStepDefinitionChanges(commit, parent, entry, parser)
        else {
            def lines = computeChanges(commit, entry.newPath)
            if (Util.isUnitTestFile(entry.newPath)) {
                //change = extractUnitChanges(commit, entry.newPath, lines, parser)
            } else {
                change = new CoreChange(path: entry.newPath, type: entry.changeType, lines: lines)
            }
        }
        change
    }

    /***
     * Converts a list of DiffEntry objects into CodeChange objects.
     * Important: DiffEntry.ChangeType.RENAME and DiffEntry.ChangeType.COPY are ignored. As consequence, if a renamed
     * file also has code changes, such changes are also ignored.
     */
    private List<CodeChange> extractAllCodeChangeFromDiffs(RevCommit commit, RevCommit parent, List<DiffEntry> diffs,
                                                           TestCodeAbstractParser parser) {
        List<CodeChange> codeChanges = []
        diffs?.each { entry ->
            switch (entry.changeType) {
                case DiffEntry.ChangeType.ADD: //it is necessary to know file size because all lines were changed
                    def change = configureAddChange(commit, entry, parser)
                    if (change != null) {
                        codeChanges += change
                    }
                    break
                case DiffEntry.ChangeType.MODIFY:
                    def change = configureModifyChange(commit, parent, entry, parser)
                    if (change != null) {
                        codeChanges += change
                    }
                    break
                case DiffEntry.ChangeType.DELETE: //the file size is already known
                    if (Util.isProductionFile(entry.oldPath)) {
                        def result = extractFileContent(parent, entry.oldPath)
                        codeChanges += new CoreChange(path: entry.oldPath, type: entry.changeType, lines: 0..<result.readLines().size())
                    }
                    break
                case DiffEntry.ChangeType.RENAME:
                    codeChanges += new RenamingChange(path: entry.newPath, oldPath: entry.oldPath)
                    break
            }
        }

        codeChanges
    }

    private TreeWalk generateTreeWalk(RevTree tree, String filename) {
        def git = Git.open(new File(localPath))
        TreeWalk treeWalk = new TreeWalk(git.repository)
        treeWalk.addTree(tree)
        treeWalk.setRecursive(true)
        if (filename) treeWalk.setFilter(PathFilter.create(filename))
        treeWalk.next()
        git.close()
        return treeWalk
    }

    String extractFileContent(RevCommit commit, String filename) {
        def result = ""
        def git = Git.open(new File(localPath))
        filename = filename.replaceAll(RegexUtil.FILE_SEPARATOR_REGEX, Matcher.quoteReplacement("/"))
        RevWalk revWalk = new RevWalk(git.repository)
        TreeWalk treeWalk = generateTreeWalk(commit?.tree, filename)
        ObjectId objectId = treeWalk.getObjectId(0)
        try {
            ObjectLoader loader = git.repository.open(objectId)
            ByteArrayOutputStream stream = new ByteArrayOutputStream()
            loader.copyTo(stream)
            revWalk.dispose()
            result = stream.toString("UTF-8")
            stream.reset()
        }
        catch (ignored) {
            if (objectId.equals(ObjectId.zeroId()))
                log.error "There is no ObjectID for the commit tree. Verify the file separator used in the filename '$filename'."
        }

        git.close()

        return result
    }

    private static AbstractTreeIterator prepareTreeParser(Git git, String objectId) {
        RevWalk walk = null
        RevCommit commit
        RevTree tree
        CanonicalTreeParser oldTreeParser = null

        // from the commit we can build the tree which allows us to construct the TreeParser
        try {
            walk = new RevWalk(git.repository)
            commit = walk.parseCommit(ObjectId.fromString(objectId))
            tree = walk.parseTree(commit.getTree().getId())
            oldTreeParser = new CanonicalTreeParser()
            ObjectReader oldReader = git.repository.newObjectReader()
            oldTreeParser.reset(oldReader, tree.getId())
        } catch (Exception ex) {
            log.error ex.message
        }
        finally {
            walk?.dispose()
        }

        return oldTreeParser
    }

    private List<CodeChange> extractAllCodeChangesFromCommit(RevCommit commit, TestCodeAbstractParser parser) {
        List<CodeChange> codeChanges = []

        switch (commit.parentCount) {
            case 0: //first commit
                codeChanges = extractCodeChangesByFirstCommit(commit, parser)
                break
            case 1: //commit with one parent
                codeChanges = extractCodeChanges(commit, commit.parents.first(), parser)
                break
            default: //merge commit (commit with more than one parent)
                commit.parents.each { parent ->
                    codeChanges += extractCodeChanges(commit, parent, parser)
                }
        }

        return codeChanges
    }

    private List<Commit> extractCommitsFromLogs(Iterable<RevCommit> logs, TestCodeAbstractParser parser) {
        def commits = []
        logs?.each { c ->
            List<CodeChange> codeChanges = extractAllCodeChangesFromCommit(c, parser)
            List<CoreChange> prodFiles = codeChanges.findAll { it instanceof CoreChange } as List<CoreChange>

            // identifies changed gherkin files and scenario definitions
            List<GherkinFile> gherkinChanges = codeChanges?.findAll { it instanceof GherkinFile } as List<GherkinFile>

            //identifies changed step files
            List<StepDefinitionFile> stepChanges = codeChanges?.findAll {
                it instanceof StepDefinitionFile
            } as List<StepDefinitionFile>

            // identifies changed rspec files
            //List<UnitFile> unitChanges = codeChanges?.findAll{ it instanceof UnitFile } as List<UnitFile>
            List<UnitFile> unitChanges = []

            List<RenamingChange> renameChanges = codeChanges?.findAll {
                it instanceof RenamingChange
            } as List<RenamingChange>

            commits += new Commit(hash: c.name, message: c.fullMessage.replaceAll(RegexUtil.NEW_LINE_REGEX, " "),
                    author: c.authorIdent.name, date: c.commitTime, coreChanges: prodFiles, gherkinChanges: gherkinChanges,
                    unitChanges: unitChanges, stepChanges: stepChanges, renameChanges: renameChanges)
        }
        commits
    }

    /* PROBLEM: Deal with removed lines. */

    private List<Integer> computeChanges(RevCommit commit, String filename) {
        def changedLines = []
        def git = Git.open(new File(localPath))
        BlameCommand blamer = new BlameCommand(git.repository)
        blamer.setStartCommit(ObjectId.fromString(commit.name))
        blamer.setFilePath(filename.replaceAll(RegexUtil.FILE_SEPARATOR_REGEX, Matcher.quoteReplacement("/")))
        BlameResult blameResult = blamer.call()

        List<String> fileContent = extractFileContent(commit, filename)?.readLines()
        fileContent?.eachWithIndex { line, i ->
            RevCommit c = blameResult?.getSourceCommit(i)
            if (c?.name?.equals(commit.name)) changedLines += i
        }

        git.close()

        /* if the result is empty, it means changes were removed lines only; the blame command can not deal with
        * this type of change; a new strategy should be defined!!!! */
        return changedLines
    }

    List<RevCommit> identifyCommitsInFile(String filename) {
        def git = Git.open(new File(localPath))
        List<RevCommit> logs = git?.log()?.addPath(filename)?.call()?.sort { it.commitTime }
        git.close()
        return logs
    }

    List<CodeChange> extractCodeChanges(RevCommit commit, RevCommit parent, TestCodeAbstractParser parser) {
        def diffs = extractDiff(null, commit, parent)
        extractAllCodeChangeFromDiffs(commit, parent, diffs, parser)
    }

    List<CodeChange> extractCodeChangesByFirstCommit(RevCommit commit, TestCodeAbstractParser parser) {
        List<CodeChange> codeChanges = []
        def git = Git.open(new File(localPath))
        TreeWalk tw = new TreeWalk(git.repository)
        tw.reset()
        tw.setRecursive(true)
        tw.addTree(commit.tree)
        while (tw.next()) {
            if (!Util.isValidFile(tw.pathString)) continue

            def result = extractFileContent(commit, tw.pathString)

            if (Util.isGherkinFile(tw.pathString)) {
                def change = GherkinManager.extractGherkinAdds(commit, result, tw.pathString)
                if (change != null) codeChanges += change
            } else if (Util.isStepDefinitionFile(tw.pathString)) {
                def change = StepDefinitionManager.extractStepDefinitionAdds(commit, result, tw.pathString, parser)
                if (change != null) codeChanges += change
            } else {
                def lines = 0..<result.readLines().size()
                if (Util.isUnitTestFile(tw.pathString)) {
                    //codeChanges += extractUnitChanges(commit, tw.pathString, lines, parser)
                } else {
                    codeChanges += new CoreChange(path: tw.pathString, type: DiffEntry.ChangeType.ADD, lines: 0..<lines)
                }
            }
        }
        tw.release()
        git.close()
        codeChanges
    }

    Iterable<RevCommit> searchAllRevCommits() {
        def git = Git.open(new File(localPath))
        Iterable<RevCommit> logs = git?.log()?.call()?.sort { it.commitTime }
        git.close()
        logs
    }

    Iterable<RevCommit> searchAllRevCommitsBySha(String... hash) {
        def git = Git.open(new File(localPath))
        def logs = git?.log()?.call()?.findAll { it.name in hash }?.sort { it.commitTime }
        git.close()
        logs
    }

    /***
     * Searches commits from a Git repository by hash value.
     *
     * @param hash a set of hash value
     * @return a list of commits that satisfy the search criteria.
     */
    List<Commit> searchCommitsBySha(TestCodeAbstractParser parser, String... hash) {
        def logs = searchAllRevCommitsBySha(hash)
        extractCommitsFromLogs(logs, parser)
    }

    /***
     * Checkouts a specific version of git repository.
     * @param sha the commit's identification.
     */
    def reset(String sha) {
        def git = Git.open(new File(localPath))
        git.checkout().setName(sha).setStartPoint(sha).call()
        git.close()
    }

    /***
     * Checkouts the last version of git repository.
     */
    def reset() {
        def git = Git.open(new File(localPath))
        git.checkout().setName(lastCommit).setStartPoint(lastCommit).call()
        git.close()
    }

}
