package util.ruby

import util.ConstantData
import util.Util

import java.util.regex.Matcher

class RubyUtil extends Util {

    static List<String> searchViewFor(String controller, String action, List viewFiles) {
        def result = []
        def match = viewFiles?.findAll { it.contains("views${File.separator}$controller${File.separator}$action") }
        if (match && !match.empty) result += match
        else {
            def matches = viewFiles?.findAll { it.contains("views${File.separator}$controller") }
            if (matches && !matches.empty) result += matches
        }
        result
    }

    static String getClassName(String path) {
        if (!path || path.empty || path.isAllWhitespace()) return ""
        def firstIndex = path.lastIndexOf(File.separator)
        def lastIndex = path.lastIndexOf(".")
        def underscore = ""
        if (firstIndex >= 0 && lastIndex >= 0) underscore = path.substring(firstIndex + 1, lastIndex)
        underscoreToCamelCase(underscore)
    }

    static boolean isRouteMethod(String name) {
        if (!(name in RubyConstantData.EXCLUDED_PATH_METHODS) && name.endsWith(RubyConstantData.ROUTE_SUFIX)) true
        else false
    }

    static List<String> getClassPathForRubyClass(String original, Collection<String> projectFiles) {
        def underscore = camelCaseToUnderscore(original)
        getClassPathForRubyInstanceVariable(underscore, projectFiles)
    }

    static List<String> getClassPathForRubyInstanceVariable(String original, Collection<String> projectFiles) {
        def name = original + ConstantData.RUBY_EXTENSION
        def exp = ".*$File.separator$name".replace(File.separator, Matcher.quoteReplacement(File.separator))
        projectFiles?.findAll { it ==~ /$exp/ }
    }

    static checkRailsVersionAndGems(String path){
        def railsRegex = /\s*gem\s+"?'?rails"?'?.*/
        def simplecovRegex = /\s*gem\s+"?'?simplecov"?'?.*/
        def factory_girlRegex = /\s*gem\s+"?'?factory_girl"?'?.*/
        def rails = ""
        def simplecov = false
        def factorygirl = false
        File file = new File(path+File.separator+RubyConstantData.GEM_FILE)
        if(file.exists()){
            def lines = file.readLines()
            def railsGem = lines.find{ !(it.trim().startsWith("#")) && it==~railsRegex }
            if(railsGem){
                def index = railsGem.lastIndexOf(",")
                if(index>-1) rails = railsGem?.substring(index+1)?.trim()
            }
            def simplecovGem = lines.find{ !(it.trim().startsWith("#")) && it==~simplecovRegex }
            if(simplecovGem) simplecov = true
            def factorygirlGem = lines.find{ !(it.trim().startsWith("#")) && it==~factory_girlRegex }
            if(factorygirlGem) factorygirl = true
        }
        return [rails:rails, simplecov:simplecov, factorygirl:factorygirl]
    }

}
