package testCodeAnalyser.ruby

import groovy.util.logging.Slf4j
import org.jrubyparser.CompatVersion
import org.jrubyparser.Parser
import org.jrubyparser.ast.Node
import org.jrubyparser.lexer.SyntaxException
import org.jrubyparser.parser.ParserConfiguration
import taskAnalyser.task.StepDefinition
import taskAnalyser.task.UnitFile
import testCodeAnalyser.FileToAnalyse
import testCodeAnalyser.StepRegex
import testCodeAnalyser.TestCodeAbstractParser
import testCodeAnalyser.TestCodeVisitor
import testCodeAnalyser.ruby.routes.RubyConfigRoutesVisitor
import testCodeAnalyser.ruby.unitTest.RSpecFileVisitor
import testCodeAnalyser.ruby.unitTest.RSpecTestDefinitionVisitor
import util.RegexUtil
import util.Util
import util.ruby.RubyUtil

@Slf4j
class RubyTestCodeParser extends TestCodeAbstractParser {

    String routesFile
    def routes //name, file, value
    List<String> modelFiles
    List<String> controllerFiles

    RubyTestCodeParser(String repositoryPath){
        super(repositoryPath)
        routesFile = repositoryPath+RubyUtil.ROUTES_FILE
        modelFiles = []
        controllerFiles = []
    }

    private updateFiles(){
        def modelsDir = "${repositoryPath}${File.separator}${Util.PRODUCTION_FILES_RELATIVE_PATH}${File.separator}models"
        modelFiles = Util.findFilesFromDirectoryByLanguage(modelsDir)
        def controllersDir = "${repositoryPath}${File.separator}${Util.PRODUCTION_FILES_RELATIVE_PATH}${File.separator}controllers"
        controllerFiles = Util.findFilesFromDirectoryByLanguage(controllersDir)
    }

    /***
     * Generates AST for Ruby file.
     * @param path path of interest file
     * @return the root node of the AST
     */
     Node generateAst(String path){
         FileReader reader = new FileReader(path)
         Parser rubyParser = new Parser()
         CompatVersion version = CompatVersion.RUBY2_0
         ParserConfiguration config = new ParserConfiguration(0, version)
         def result = null

         try{
             result = rubyParser.parse("<code>", reader, config)
         } catch(SyntaxException ex){
             log.error "Problem to visit file $path: ${ex.message}"
             def index = ex.message.indexOf(",")
             def msg = index>=0 ? ex.message.substring(index+1).trim() : ex.message.trim()
             compilationErrors += [path:path, msg:msg]
         }
         finally {
             reader?.close()
         }
         result
     }

     Node generateAst(String path, String content){
         StringReader reader = new StringReader(content)
         Parser rubyParser = new Parser()
         CompatVersion version = CompatVersion.RUBY2_0
         ParserConfiguration config = new ParserConfiguration(0, version)

         def result = null

         try{
             result = rubyParser.parse("<code>", reader, config)
         } catch(SyntaxException ex){
             log.error "Problem to visit file $path: ${ex.message}"
             def index = ex.message.indexOf(",")
             def msg = index>=0 ? ex.message.substring(index+1).trim() : ex.message.trim()
             compilationErrors += [path:path, msg:msg]
         }
         finally {
             reader?.close()
         }
         result
     }

    /***
     * Finds all regex expression in a source code file.
     *
     * @param path ruby file
     * @return map identifying the file and its regexs
     */
    @Override
    List<StepRegex> doExtractStepsRegex(String path){
        def node = generateAst(path)
        def visitor = new RubyStepRegexVisitor(path)
        node?.accept(visitor)
        visitor.regexs
    }

    @Override
    List<StepDefinition> doExtractStepDefinitions(String path, String content) {
        def node = generateAst(path, content)
        def visitor = new RubyStepDefinitionVisitor(path, content)
        node?.accept(visitor)
        visitor.stepDefinitions
    }

    @Override
    Set doExtractMethodDefinitions(String file) {
        RubyMethodDefinitionVisitor visitor = new RubyMethodDefinitionVisitor()
        visitor.path = file
        def node = generateAst(file)
        node?.accept(visitor)
        visitor.methods
    }

    /***
     * Visits a step body and method calls inside it. The result is stored as a field of the returned visitor.
     *
     * @param file List of map objects that identifies files by 'path' and 'lines'.
     * @return visitor to visit method bodies
     */
    @Override
    TestCodeVisitor parseStepBody(FileToAnalyse file) {
        def node = generateAst(file.path)
        def visitor = new RubyTestCodeVisitor(projectFiles, file.path, methods)
        def testCodeVisitor = new RubyStepsFileVisitor(file.methods, visitor)
        node?.accept(testCodeVisitor)
        visitor
    }

    /***
     * Visits selected method bodies from a source code file searching for other method calls. The result is stored as a
     * field of the input visitor.
     *
     * @param file a map object that identifies a file by 'path' and 'methods'. A method is identified by its name.
     * @param visitor visitor to visit method bodies
     */
    @Override
    def visitFile(def file, TestCodeVisitor visitor) {
        def node = generateAst(file.path)
        visitor.lastVisitedFile = file.path
        def auxVisitor = new RubyMethodVisitor(file.methods, (RubyTestCodeVisitor) visitor)
        node?.accept(auxVisitor)
    }

    private findAllRoutes(){
        def node = generateAst(routesFile)
        updateFiles()
        RubyConfigRoutesVisitor visitor = new RubyConfigRoutesVisitor(node, modelFiles, controllerFiles)
        def routes = [] //name, file, value
        visitor?.routingMethods?.each{ route ->
            def name = route.name.replaceAll("/:(\\w|\\.|:|#|&|=|\\+|\\?)*/", "/.*/").replaceAll("/:(\\w|\\.|:|#|&|=|\\+|\\?)*[^/()]", "/.*")
            routes += [name:name, file:route.file, value:route.value, arg:route.arg]
        }
        routes
    }

    private String extractValueFromRouteMethod(String name){
        def route = routes?.find{ it.name ==~ name || name ==~/${it.value}/ }
        def result = name
        if(route) {
            if(route.arg && route.arg!="") result = route.arg
            else if(route.value && route.value!="") result = route.value //com isso, o valor retornado por esse metodo pode ser uma regex
        }
        return result //if the route was not found, searches for path directly
    }

    private identifyRoutes(def f){ //f keywords: file, name, args
        def result = []
        if(f.file == RubyUtil.ROUTES_ID) { //search for route
            log.info "searching route by routes configuration: ${f.name}"
            result += extractValueFromRouteMethod(f.name)
        }
        else { //it was used an auxiliary method; the view path must be extracted
            def failed = false
            if(!f.args.empty){ //tries to extract a specific method return according to the argument; if it is not possible, failed is true
                def valueExtracted = false
                def pageVisitor = new RubyConditionalVisitor(f.name, f.args)
                generateAst(f.file)?.accept(pageVisitor)

                if(pageVisitor.pages.empty && pageVisitor.auxiliaryMethods.empty) failed = true
                else if(!pageVisitor.pages.empty) {
                    log.info "Pages: ${pageVisitor.pages}"
                    pageVisitor.pages.each{ page ->
                        result += extractValueFromRouteMethod(page)
                        valueExtracted = true
                    }
                }
                else if(!pageVisitor.auxiliaryMethods.empty){
                    log.info "Auxiliary methods: ${pageVisitor.auxiliaryMethods}"
                    pageVisitor.auxiliaryMethods.each {
                        if(RubyUtil.isRouteMethod(it)){
                            result += extractValueFromRouteMethod(it-RubyUtil.ROUTE_SUFIX)
                            valueExtracted = true
                        }
                    }
                }
                if(!valueExtracted) failed = true
            }
            if(f.args.empty || failed){ //tries to extract all possible return values
                log.info "extracting all possible returns..."
                def pageVisitor = new RubyPageVisitor(f.name, f.args)
                generateAst(f.file)?.accept(pageVisitor) //extracts path from method
                if(!pageVisitor.pages.empty) {
                    pageVisitor.pages.each { page ->
                        def route = routes?.find{ page == it.name|| page ==~ /${it.value}/ }
                        if(route) {
                            result += route.arg
                        } else {
                            result += page //if the route was not found, searches for path directly
                        }
                    }
                }
            }
        }

        def finalResult = []
        result.each{ r ->
            if(r ==~ RegexUtil.FILE_SEPARATOR_REGEX) {
                def newRoute = extractValueFromRouteMethod("root")
                if(newRoute && newRoute!="root") r = newRoute
            }
            else if(r.startsWith(File.separator)){ //dealing with redirects (to validate)
                def newRoute = extractValueFromRouteMethod(r.substring(1))
                if(newRoute && newRoute!=r.substring(1)) r = newRoute
            }
            finalResult += r
        }

        finalResult
    }

    private matchRouteAndView(TestCodeVisitor visitor, Set calledViews){
        log.info "All pages to identify: $calledViews"
        def founded = []
        calledViews?.each{ page ->
            def foundPages = RubyUtil.findViewPathForRailsProjects(page, viewFiles)
            if(foundPages && !foundPages.empty) {
                visitor?.taskInterface?.referencedPages += foundPages
                founded += page
            }
        }

        log.info "All founded views: ${visitor?.taskInterface?.referencedPages}"
        def problematicViews = calledViews - founded
        log.info "Not identified pages: $problematicViews"
        notFoundViews += problematicViews
    }

    @Override
    void findAllPages(TestCodeVisitor visitor) {
        def filesToVisit = visitor?.taskInterface?.calledPageMethods
        if(!routes) {
            routes = findAllRoutes()
            log.info "All routes:"
            routes.each{ log.info it.toString() }
        }

        def result = [] as Set
        filesToVisit = filesToVisit.findAll{ it!= null } //it could be null if the test code references a class or file that does not exist
        filesToVisit?.each{ f ->
            log.info "FIND_ALL_PAGES; visiting file '${f.file}' and method '${f.name} (${f.args})'"
            result += identifyRoutes(f)
        }

        result = result.unique()
        matchRouteAndView(visitor, result)
    }

    @Override
    TestCodeVisitor parseUnitBody(UnitFile file) {
        def node = generateAst(file.path)
        def visitor = new RubyTestCodeVisitor(projectFiles, file.path, methods)
        visitor.lastVisitedFile = file.path
        visitor.productionClass = file.productionClass //keywords: name, path
        def testCodeVisitor = new RSpecFileVisitor(file.tests*.lines, visitor)
        node?.accept(testCodeVisitor)
        visitor
    }

    @Override
    UnitFile doExtractUnitTest(String path, String content, List<Integer> changedLines){
        UnitFile unitFile = null
        try {
            def visitor = new RSpecTestDefinitionVisitor(path, content, repositoryPath)
            def node = generateAst(path, content)
            node?.accept(visitor)
            if (visitor.tests.empty) {
                log.info "The unit file does not contain any test definition!"
            } else {
                def changedTests = visitor.tests.findAll { it.lines.intersect(changedLines) }
                if (changedTests) {
                    unitFile = new UnitFile(path: path, tests: changedTests, productionClass: visitor.productionClass)
                } else {
                    log.info "No unit test was changed or the changed one was not found!"
                }
            }
        } catch (FileNotFoundException ex) {
            log.warn "Problem to parse unit test file: ${ex.message}. Reason: The commit deleted it."
        }
        unitFile
    }

    @Override
    String getClassForFile(String path) {
        RubyUtil.getClassName(path)
    }
}
