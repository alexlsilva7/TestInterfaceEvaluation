package commitAnalyser

/***
 * Represents a code change by a commit.
 */
class CodeChange {

    String filename
    def type //add file, remove file, change file (add, change or remove lines), copy file or renaming file
    List<Integer> lines //if null, it was not computed yet

}
