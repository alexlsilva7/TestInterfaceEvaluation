package commitAnalyser

import gherkin.Parser
import gherkin.ParserException
import gherkin.ast.Feature
import gherkin.ast.ScenarioDefinition
import org.eclipse.jgit.revwalk.RevCommit
import taskAnalyser.GherkinFile
import taskAnalyser.StepDefinition


class GherkinManager {

    static Feature parseGherkinFile(String content, String filename, String sha){
        Feature feature = null
        if(!content || content==""){
            GitRepository.log.warn "Problem to parse Gherkin file '$filename'. Reason: The commit deleted it."
        }
        else{
            try{
                Parser<Feature> parser = new Parser<>()
                feature = parser.parse(content)
            } catch(ParserException ex){
                GitRepository.log.warn "Problem to parse Gherkin file '$filename' (commit $sha). ${ex.class}: ${ex.message}."
                GitRepository.log.warn content
            }
        }
        feature
    }

    static boolean equals(ScenarioDefinition sd1, ScenarioDefinition sd2){
        def result = true
        for (int i = 0; i < sd1.steps.size(); i++) {
            def step1 = sd1.steps.get(i)
            def step2 = sd2.steps.get(i)
            if(step1.text != step2.text || step1.keyword != step2.keyword) {
                result = false
                break
            }
        }
        result
    }

    static boolean equals(StepDefinition sd1, StepDefinition sd2){
        def result = true
        for (int i = 0; i < sd1.body.size(); i++) {
            def step1 = sd1.body.get(i)
            def step2 = sd2.body.get(i)
            if(step1 != step2) {
                result = false
                break
            }
        }
        result
    }

    static extractFeatureText(def locations, Feature feature, def lines){
        def featureLocation = feature.location.line
        def featureText = ""
        def featureIndex = locations.indexOf(featureLocation)

        if(featureIndex < locations.size()-1) {
            int max = locations.get(featureIndex+1)-1 as int
            for(int i = featureLocation-1; i<max; i++){
                featureText += lines.get(i).trim()+"\n"
            }
        }
        else{
            for(int i = featureLocation-1; i<lines.size(); i++){
                featureText += lines.get(i).trim()+"\n"
            }
        }

        featureText
    }

    static extractBackgroundText(def locations, Feature feature, def lines){
        def text = ""
        if(!feature.background) return text

        def location = feature.background.location.line
        def index = locations.indexOf(location)

        if(index < locations.size()-1) {
            int max = locations.get(index+1)-1 as int
            for(int i = location-1; i<max; i++){
                text += lines.get(i).trim()+"\n"
            }
        }
        else{
            for(int i = location-1; i<lines.size(); i++){
                text += lines.get(i).trim()+"\n"
            }
        }
        text
    }

    static extractTextFromGherkin(Feature feature, List<ScenarioDefinition> scenDefinitions,
                                          String content, GherkinFile gherkinFile){
        def locations = feature.scenarioDefinitions*.location*.line.flatten().sort()
        def lines = content.readLines()

        gherkinFile.featureText = extractFeatureText(locations, feature, lines).replaceAll("(?m)^\\s", "")
        gherkinFile.backgroundText = extractBackgroundText(locations, feature, lines).replaceAll("(?m)^\\s", "")
        scenDefinitions.each{ change ->
            def text = ""
            def initialLine = change.location.line
            def index = locations.indexOf(initialLine)

            if(index < locations.size()-1) {
                //excludes tag of next scenario definition
                int max = locations.get(index+1)-1 as int
                def scenDef = scenDefinitions.find { it.location.line == max+1 }
                if(!scenDef?.tags?.empty) max--

                //extracts all text until it reaches the next scenario definition
                for(int i = initialLine-1; i<max; i++){
                    text += lines.get(i).trim()+"\n"
                }
            }
            else{ //the scenario definition is the last one
                for(int i = initialLine-1; i<lines.size(); i++){
                    text += lines.get(i).trim()+"\n"
                }
            }
            gherkinFile.changedScenarioDefinitionsText += text.replaceAll("(?m)^\\s", "")
        }
    }

    /***
     * Identifies scenarios definitions at added gherkin files (features) by the first commit of the repository.
     * It is used only when dealing with done tasks.
     */
    static GherkinFile extractGherkinAdds(RevCommit commit, String content, String path){
        GherkinFile changedGherkinFile = null
        def newFeature = parseGherkinFile(content, path, commit.name)
        def newScenarioDefinitions = newFeature?.scenarioDefinitions

        if(newScenarioDefinitions && !newScenarioDefinitions.isEmpty()){
            changedGherkinFile = new GherkinFile(commitHash:commit.name, path:path,
                    feature:newFeature, changedScenarioDefinitions:newScenarioDefinitions)
            extractTextFromGherkin(newFeature, newScenarioDefinitions, content, changedGherkinFile)
        }
        changedGherkinFile
    }

}
