import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.projectFeatures.buildReportTab
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2022.10"

project {
    description = "Contains all other projects"

    features {
        buildReportTab {
            id = "PROJECT_EXT_1"
            title = "Code Coverage"
            startPage = "coverage.zip!index.html"
        }
    }

    cleanup {
        baseRule {
            all(days = 365)
            history(days = 90)
            preventDependencyCleanup = false
        }
    }

    subProject(PluginHelp)
}


object PluginHelp : Project({
    name = "Plugin Help"

    vcsRoot(PluginHelp_HttpsGithubComLananovikova10pluginHelpRefsHeadsMain)

    buildType(PluginHelp_PublishAlgoliaIndex)
    buildType(PluginHelp_BuildDocs)

    template(PluginHelp_PublishIndexes)
})

object PluginHelp_BuildDocs : BuildType({
    name = "Build docs"

    artifactRules = """
        helproot => webhelp.zip
        artifacts/algolia-indexes-*.zip
        algolia-update-index-log.txt
    """.trimIndent()

    params {
        param("env.ALGOLIA_KEY", "85724485b4a5899262ac9c8856168fc2")
        param("teamcity.internal.artifacts.useZip64", "false")
        param("env.ALGOLIA_INDEX_NAME", "lana_teamcity_pd")
        param("env.ARTIFACT1", "webHelpPD2.zip")
        param("env.ARTIFACT2", "webHelpImages.zip")
        param("env.CONFIG_JSON_VERSION", "main")
        param("env.CONFIG_JSON_PRODUCT", "plugin-developers")
        param("env.ALGOLIA_APP_NAME", "0J2F033YWG")
        param("env.PRODUCT", "stardust-help/pd")
        param("env.ARTIFACT_ALL", "webHelpPD2-all.zip")
    }

    vcs {
        root(PluginHelp_HttpsGithubComLananovikova10pluginHelpRefsHeadsMain)
    }

    steps {
        script {
            scriptContent = """
                set -e
                # mkdir -p intellij-platform/images/
                # wget https://packages.jetbrains.team/files/p/stardust/web-help-authoring-assets/keymaps.zip -O keymaps.zip
                # wget https://packages.jetbrains.team/files/p/stardust/web-help-authoring-assets/icons.zip -O icons.zip
                # wget https://packages.jetbrains.team/files/p/stardust/web-help-authoring-assets/props.zip -O props.zip
                #
                # NOTE: Do not replace with unzip! This debian distribution contains buggy unzip, which is not able to extract java-packed zips
                # python3 -m zipfile -e keymaps.zip intellij-platform/
                # python3 -m zipfile -e icons.zip intellij-platform/images/
                # python3 -m zipfile -e props.zip intellij-platform/
                /opt/builder/bin/idea.sh helpbuilderinspect -source-dir . -product ${'$'}PRODUCT -output-dir artifacts/ ${'$'}SUPPRESS_TESTS
                
                echo "Test existing of ${'$'}ARTIFACT_ALL artifact"
                test -e artifacts/${'$'}ARTIFACT_ALL
                echo "${'$'}ARTIFACT_ALL exists"
                
                mkdir -p helproot/img/
                echo "Unzip artifact to shared folder"
                python3 -m zipfile -e artifacts/${'$'}ARTIFACT_ALL helproot/
                
                # see WRS-938 for some details on upload tool
                #set -e 
                
                ls -la algolia-index/
                
                echo "DEBUG: secret key for algolia is : ${'$'}ALGOLIA_KEY."
                
                env "algolia-key=${'$'}ALGOLIA_KEY" java -jar /opt/builder/help-publication-agent.jar \
                      update-index \
                      --application-name ${'$'}ALGOLIA_APP_NAME \
                      --index-name ${'$'}ALGOLIA_INDEX_NAME \
                      --product ${'$'}CONFIG_JSON_PRODUCT \
                      --version ${'$'}CONFIG_JSON_VERSION \
                      --index-directory algolia-index/ \
                      2>&1 | tee algolia-update-index-log.txt
            """.trimIndent()
            dockerImage = "registry.jetbrains.team/p/writerside/builder/writerside-builder:2.1.971"
        }
        script {
            name = "Info"
            scriptContent = "ls -lR"
        }
    }

    triggers {
        vcs {
        }
    }

    failureConditions {
        executionTimeoutMin = 30
    }

    features {
        perfmon {
        }
        dockerSupport {
        }
    }

    dependencies {
        artifacts(RelativeId("PluginHelp_BuildDocs")) {
            buildRule = lastSuccessful()
            artifactRules = """
                webhelp.zip
                algolia-indexes-*.zip!** => algolia-index
            """.trimIndent()
        }
    }
})

object PluginHelp_PublishAlgoliaIndex : BuildType({
    templates(PluginHelp_PublishIndexes)
    name = "Publish Algolia index"
})

object PluginHelp_PublishIndexes : Template({
    name = "Publish indexes"

    artifactRules = "algolia-update-index-log.txt"

    params {
        param("env.ALGOLIA_KEY", "85724485b4a5899262ac9c8856168fc2")
        param("env.CONFIG_JSON_VERSION", "main")
        param("env.ALGOLIA_INDEX_NAME", "lana_teamcity_pd")
        param("env.CONFIG_JSON_PRODUCT", "plugin-developers")
        param("env.ALGOLIA_APP_NAME", "0J2F033YWG")
    }

    steps {
        script {
            id = "RUNNER_3"
            scriptContent = """
                # see WRS-938 for some details on upload tool
                set -e 
                
                ls -la algolia-index/
                
                echo "DEBUG: secret key for algolia is : ${'$'}ALGOLIA_KEY."
                
                env "algolia-key=${'$'}ALGOLIA_KEY" java -jar /opt/builder/help-publication-agent.jar \
                      update-index \
                      --application-name ${'$'}ALGOLIA_APP_NAME \
                      --index-name ${'$'}ALGOLIA_INDEX_NAME \
                      --product ${'$'}CONFIG_JSON_PRODUCT \
                      --version ${'$'}CONFIG_JSON_VERSION \
                      --index-directory algolia-index/ \
                      2>&1 | tee algolia-update-index-log.txt
            """.trimIndent()
            dockerImage = "registry.jetbrains.team/p/writerside/builder/algolia-publisher:2.0.32-1"
        }
    }

    dependencies {
        artifacts(PluginHelp_BuildDocs) {
            id = "ARTIFACT_DEPENDENCY_1"
            buildRule = lastSuccessful()
            artifactRules = """
                webhelp.zip
                algolia-indexes-*.zip!** => algolia-index
            """.trimIndent()
        }
    }
})

object PluginHelp_HttpsGithubComLananovikova10pluginHelpRefsHeadsMain : GitVcsRoot({
    name = "https://github.com/lananovikova10/plugin-help#refs/heads/main"
    url = "https://github.com/lananovikova10/plugin-help"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    authMethod = password {
        userName = "lananovikova10"
        password = "cksee69db0efbc0bf06ae9088504ee9fc7fHZFLSx/i4owDZVV6xjaTZQ=="
    }
})
