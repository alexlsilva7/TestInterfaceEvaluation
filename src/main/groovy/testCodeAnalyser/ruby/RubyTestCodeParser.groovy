package testCodeAnalyser.ruby

import org.jrubyparser.CompatVersion
import org.jrubyparser.Parser
import org.jrubyparser.ast.Node
import org.jrubyparser.parser.ParserConfiguration
import testCodeAnalyser.StepRegex
import testCodeAnalyser.TestCodeAbstractParser
import testCodeAnalyser.TestCodeVisitor


class RubyTestCodeParser extends TestCodeAbstractParser {

    RubyTestCodeParser(String repositoryPath){
        super(repositoryPath)
    }

    /***
     * Generates AST for Ruby file.
     * @param path path of interest file
     * @return the root node of the AST
     */
    static Node generateAst(String path){
        FileReader reader = new FileReader(path)
        Parser rubyParser = new Parser()
        CompatVersion version = CompatVersion.RUBY2_0
        ParserConfiguration config = new ParserConfiguration(0, version)
        def result = rubyParser.parse("<code>", reader, config)
        reader.close()
        return result
    }

    @Override
    /***
     * Finds all regex expression in a source code file.
     *
     * @param path ruby file
     * @return map identifying the file and its regexs
     */
    List<StepRegex> doExtractStepsRegex(String path){
        def node = generateAst(path)
        def visitor = new RubyStepRegexVisitor(path)
        node.accept(visitor)
        visitor.regexs
    }

    @Override
    /***
     * Visits a step body and method calls inside it. The result is stored as a field of the returned visitor.
     *
     * @param file List of map objects that identifies files by 'path' and 'lines'.
     * @return visitor to visit method bodies
     */
    TestCodeVisitor parseStepBody(def file) {
        def node = generateAst(file.path)
        def visitor = new RubyTestCodeVisitor(repositoryPath, file.path)
        visitor.lastVisitedFile = file.path
        def testCodeVisitor = new RubyStepsFileVisitor(file.lines, visitor)
        node.accept(testCodeVisitor)
        visitor
    }

    @Override
    /***
     * Visits selected method bodies from a source code file searching for other method calls. The result is stored as a
     * field of the input visitor.
     *
     * @param file a map object that identifies a file by 'path' and 'methods'. A method is identified by its name.
     * @param visitor visitor to visit method bodies
     */
    def visitFile(def file, TestCodeVisitor visitor) {
        def node = generateAst(file.path)
        visitor.lastVisitedFile = file.path
        def auxVisitor = new RubyMethodVisitor(file.methods, (RubyTestCodeVisitor) visitor)
        node.accept(auxVisitor)
    }

}
