import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.notifications
import jetbrains.buildServer.configs.kotlin.buildSteps.ScriptBuildStep
import jetbrains.buildServer.configs.kotlin.buildSteps.ant
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.failureConditions.BuildFailureOnText
import jetbrains.buildServer.configs.kotlin.failureConditions.failOnText
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.triggers.schedule
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

    vcsRoot(NebulaSourcesWriterside)
    vcsRoot(WritersideSandboxSources)
    vcsRoot(SupernovaSourcesWriterside)
    vcsRoot(WritersideSources)

    buildType(SupernovaWriterside)
    buildType(WritersideSandbox)
    buildType(WritersideSandboxContentPromoter)
    buildType(WritersidePlugin)
    buildType(WritersideAsHelpserverRepository)
}

object SupernovaWriterside : BuildType({
    name = "Writerside Help Builder"

    artifactRules = """
        supernova/build/distributions/*.zip
        supernova/build/*.xml
        supernova/build/whatsnew.html
    """.trimIndent()

    params {
        param("help-builder-branch", "master")
    }

    vcs {
        root(SupernovaSourcesWriterside, "+:. => supernova")
        root(NebulaSourcesWriterside, "+:. => nebula")
        root(AbsoluteId("Documentation_Plugins_HelpEngine"), "+:. => supernova/help-engine")
        root(WritersideSources, "+:resources/schemas => supernova/resources/schemas")
    }

    steps {
        ant {
            name = "Copy schemas to resources"
            mode = antScript {
                content = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project name="copy" default="copySchemas">
                      
                      <target name="copySchemas">
                    
                        <copy todir="${'$'}{teamcity.build.checkoutDir}/supernova/resources/engine/" >  
                          <fileset dir="${'$'}{teamcity.build.checkoutDir}/supernova/help-engine/xsl" includes="**"/>  
                        </copy> 
                       
                        <copy todir="${'$'}{teamcity.build.checkoutDir}/supernova/resources/engine/" >  
                          <fileset dir="${'$'}{teamcity.build.checkoutDir}/help-app" includes="**"/>  
                        </copy> 
                        
                      </target>
                    </project>
                """.trimIndent()
            }
        }
        gradle {
            name = "Build plugin"
            tasks = "clean build"
            workingDir = "supernova"
            enableStacktrace = true
            jdkHome = "%env.JDK_17_0_x64%"
        }
        step {
            name = "Tag build with lang code"
            type = "TagPin_yaegor"
            param("system.username", "%system.pin.builds.user.name%")
            param("system.tags", "%teamcity.build.branch%")
            param("system.password", "credentialsJSON:bd17f9fe-9d84-46c9-9a4d-fc7403b4a626")
        }
        script {
            name = "Upload Writerside to Space"

            conditions {
                equals("teamcity.build.branch", "master")
            }
            workingDir = "%teamcity.build.checkoutDir%/supernova/build/distributions"
            scriptContent = """curl -i -H "Authorization: Bearer %authoring-assets-space-upload-key%" -F author="Egor.Malyshev" -F description="Supernova main zip" -F file=@"%teamcity.build.checkoutDir%/supernova/build/distributions/supernova-2.1.%build.number%.zip" https://packages.jetbrains.team/files/p/stardust/writerside-redistributable/"""
        }
    }

    triggers {
        schedule {
            schedulingPolicy = daily {
                hour = 1
            }
            branchFilter = ""
            triggerBuild = always()
        }
    }

    dependencies {
        artifacts(AbsoluteId("WebTeam_WebHelp_WebHelp2_ApplicationBuild")) {
            buildRule = lastSuccessful()
            artifactRules = "preview.zip!/**=>help-app"
        }
    }

    requirements {
        exists("teamcity.tool.idea")
        doesNotContain("teamcity.agent.jvm.os.name", "Windows")
    }
})

object WritersideAsHelpserverRepository : BuildType({
    templates(AbsoluteId("Documentation_StagingPromoterGenericS3"))
    name = "Writerside promoter to Helpserver Repository for Updates"

    params {
        param("update.index", "false")
        param("images.root", "")
    }

    steps {
        script {
            name = "Update parameter value"
            id = "RUNNER_6510"
            enabled = false
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                help_version="${'$'}(cat 'intellij-platform/topics/current.help.version')"
                echo "help version is: ${'$'}help_version"
                echo "##teamcity[setParameter name='deploy.to.version' value='${'$'}help_version']"
            """.trimIndent()
        }
        script {
            name = "Patch app paths for staging"
            id = "RUNNER_8143"
            enabled = false
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                echo "Replacing URLs..."
                
                find 'intellij-platform/topics' -name '*.html' -print0 | xargs -0 -n1 sed -i.bak \
                 -e 's~https://resources.jetbrains.com/storage/help-app/~/help/app/~g' \
                 -e 's~https://resources.jetbrains.com/help/img/~/help/img/~g'
                
                find 'intellij-platform/topics' -name 'config.json' -print0 | xargs -0 -n1 sed -i.bak \
                 -e 's~https://resources.jetbrains.com/storage/help-app/~/help/app/~g' \
                 -e 's~https://resources.jetbrains.com/help/img/~/help/img/~g'
                
                find 'intellij-platform/topics' -name 'config.json' -print0 | xargs -0 -n1 sed -i.bak \
                 -e 's~https://forms-service.jetbrains.com/feedback~https://forms-stgn.w3jbcom-nonprod.aws.intellij.net/feedback~g'
                
                echo "${'$'}(find 'intellij-platform/topics' -name '*.html.bak' | wc -l) files were modified"
                
                echo "Removing sed backups..."
                find 'intellij-platform/topics' -name '*.html.bak' -delete
                echo "Done"
            """.trimIndent()
        }
        script {
            name = "Update topics on Helpserver"
            id = "RUNNER_2189"
            enabled = false
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                dest="%topics.root%/%deploy.to.version%/"
                dest="${'$'}(echo "${'$'}{dest}" | tr -s /)"
                # sync new data, it will delete previous one
                aws  s3 sync 'intellij-platform/topics/' "s3://%bucket.name%/help/${'$'}dest" --delete --no-progress
                # ensure there's index.html in upper context
                # aws  s3 cp 'intellij-platform/topics/index.html' 's3://%bucket.name%/help/%topics.root%/index.html' --no-progress
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
        script {
            name = "Update topics on Helpserver for indexing"
            id = "RUNNER_7350"
            enabled = false

            conditions {
                equals("update.index", "true")
            }
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                if [ "%update.index%" != "true" ]; then
                    echo "Updating topics for 'help/%topics.root%/' disabled"
                    exit 0
                fi
                
                echo "Updating topics for 'help/%topics.root%/'..."
                aws  s3 sync 'intellij-platform/topics/' 's3://%bucket.name%/help/%topics.root%/' --no-progress
                echo "Done"
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
        script {
            name = "Update images on Helpserver"
            id = "RUNNER_8023"
            enabled = false
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                
                dest="img/%images.root%/%deploy.to.version%/"
                dest="${'$'}(echo "${'$'}{dest}" | tr -s /)"
                # copy new icons (sync without --delete)
                aws  s3 sync 'intellij-platform/images/' "s3://%bucket.name%/help/${'$'}dest" --no-progress
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
        script {
            name = "Publish plugin to Helpserver"
            id = "Publish"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                # sync new data, it will delete previous one
                aws  s3 sync 'intellij-platform/topics/' "s3://%bucket.name%/writerside-updates/" --delete --no-progress
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_1"
            buildType = "${WritersidePlugin.id}"
            successfulOnly = true
        }
    }

    dependencies {
        dependency(WritersidePlugin) {
            snapshot {
                onDependencyFailure = FailureAction.CANCEL
                onDependencyCancel = FailureAction.CANCEL
            }

            artifacts {
                id = "ARTIFACT_DEPENDENCY_1"
                artifactRules = """
                    *.zip=>intellij-platform/topics
                    helpserver.xml=>intellij-platform/topics
                """.trimIndent()
            }
        }
    }
})

object WritersidePlugin : BuildType({
    name = "Writerside IDE Plugin"
    description = "Builds Writerside plugin that is used to author documentation in IntelliJ platform IDEs."

    artifactRules = """
        stardust/build/distributions/*.zip
        stardust/build/updatePlugins.xml
        stardust/build/update.xml
        stardust/build/helpserver.xml
        stardust/build/whatsnew.html
    """.trimIndent()

    params {
        param("help-builder-branch", "master")
    }

    vcs {
        root(WritersideSources, "+:. => stardust")
        root(NebulaSourcesWriterside, "+:. => nebula")
    }

    steps {
        ant {
            name = "Copy schemas to resources"
            mode = antScript {
                content = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project name="copy" default="copySchemas">
                      
                      <target name="copySchemas">
                       
                        <copy todir="${'$'}{teamcity.build.checkoutDir}/stardust/resources/engine/" >  
                          <fileset dir="${'$'}{teamcity.build.checkoutDir}/help-app" includes="**"/>  
                        </copy> 
                        
                        <copy todir="${'$'}{teamcity.build.checkoutDir}/stardust/resources/onboarding/" >  
                          <fileset dir="${'$'}{teamcity.build.checkoutDir}/onboarding-pages" includes="**"/>  
                        </copy> 
                        
                        <copy todir="${'$'}{teamcity.build.checkoutDir}/stardust/resources/onboarding/images/" >  
                          <fileset dir="${'$'}{teamcity.build.checkoutDir}/onboarding-images" includes="**"/>  
                        </copy> 
                        
                      </target>
                    </project>
                """.trimIndent()
            }
        }
        gradle {
            name = "Build plugin"
            tasks = "clean build buildPlugin"
            workingDir = "stardust"
            enableStacktrace = true
            jdkHome = "%env.JDK_17_0%"
        }
        script {
            name = "Upload plugin to Space"

            conditions {
                equals("teamcity.build.branch", "<default>")
            }
            workingDir = "%teamcity.build.checkoutDir%/build"
            scriptContent = """
                curl -H "Authorization: Bearer %authoring-assets-space-upload-key%" -F author="Egor.Malyshev" -F description="Stardust plugin updates.xml" -F file=@%teamcity.build.checkoutDir%/build/update.xml https://packages.jetbrains.team/files/p/wh/stardust-updates/
                curl -H "Authorization: Bearer %authoring-assets-space-upload-key%" -F author="Egor.Malyshev" -F description="Stardust plugin main zip" -F file=@%teamcity.build.checkoutDir%/build/distributions/stardust-1.5.%build.number%.zip https://packages.jetbrains.team/files/p/wh/stardust-updates/
            """.trimIndent()
        }
    }

    triggers {
        schedule {
            schedulingPolicy = daily {
                hour = 5
            }
            branchFilter = "+:master"
            triggerBuild = always()

            enforceCleanCheckout = true
        }
    }

    dependencies {
        artifacts(AbsoluteId("Documentation_InternalDocumentation_WritersideOnboardingPages")) {
            buildRule = lastFinished("master")
            artifactRules = """
                onboarding-pages.zip!**=>onboarding-pages
                webHelpImages.zip!**=>onboarding-images
            """.trimIndent()
        }
        artifacts(AbsoluteId("WebTeam_WebHelp_WebHelp2_BuildPure")) {
            buildRule = lastSuccessful("master")
            artifactRules = "preview.zip!**=>help-app"
        }
    }

    requirements {
        exists("teamcity.tool.idea")
        doesNotContain("teamcity.agent.jvm.os.name", "Windows")
    }
})

object WritersideSandbox : BuildType({
    templates(AbsoluteId("Documentation_SupernovaGenericDependencyBased"))
    name = "Writerside Sandbox"

    params {
        param("product.to.build.locator", "sandbox/s")
        param("additional.parameters", "")
        param("can.edit.tags", "true")
        param("suppress.tests", "")
        param("sources.path.relative.to.checkout", "sources")
        password("loading-status", "credentialsJSON:95acd613-9aa8-4b31-8142-d5503ce6333a", display = ParameterDisplay.HIDDEN)
        param("run.tests", "true")
    }

    vcs {
        root(WritersideSandboxSources, "+:.=>sources")

        cleanCheckout = true
    }

    steps {
        script {
            name = "Rename Arecibo artifact"
            id = "Stardust_Sandbox_Rename_Arecibo"
            scriptContent = """
                #!/bin/bash
                mv arecibo/arecibo-*-all.jar arecibo/arecibo.jar
            """.trimIndent()
        }
        ant {
            name = "Test generated output"
            id = "Stardust_Sandbox_Testing_step"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE

            conditions {
                equals("run.tests", "true")
            }
            mode = antScript {
                content = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    
                    <project basedir="." default="runTests" name="runTests">
                        <target name="runTests">
                            <unzip src="${'$'}{teamcity.build.checkoutDir}/artifacts/webHelpS2.zip"
                                   dest="${'$'}{teamcity.build.checkoutDir}/artifacts/webHelpS2"/>
                    
                            <java fork="true" jar="${'$'}{teamcity.build.checkoutDir}/arecibo/arecibo.jar" failonerror="true">
                                <arg value="--tests"/>
                                <arg value="${'$'}{teamcity.build.checkoutDir}/sources/tests"/>
                                <arg value="--data"/>
                                <arg value="${'$'}{teamcity.build.checkoutDir}/artifacts"/>
                                <arg value="--depth"/>
                                <arg value="5"/>
                                <arg value="--teamcity"/>
                                <arg value="--exclude-scope"/>
                                <arg value="do-not-run"/>
                            </java>
                            
                            <echo>##teamcity[setParameter name='env.ARECIBO_TESTS_OK' value='true']</echo>
                        </target>
                    </project>
                """.trimIndent()
            }
            jdkHome = "%env.JDK_11_0_x64%"
        }
        script {
            name = "Check build status"
            id = "Check_build_status"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE

            conditions {
                equals("env.ARECIBO_TESTS_OK", "true")
                equals("run.tests", "true")
            }
            scriptContent = """echo "##teamcity[setParameter name='env.TESTS_PASSED' value='true']""""
        }
        script {
            name = "Fail Supernova build when tests did not pass"
            id = "Fail_Supernova"
            enabled = false

            conditions {
                doesNotEqual("teamcity.build.branch", "master-222")
                doesNotEqual("teamcity.build.branch", "master-223")
                doesNotExist("env.TESTS_PASSED")
                equals("can.edit.tags", "true")
                equals("run.tests", "true")
            }
            scriptContent = """curl -v --request POST "https://buildserver.labs.intellij.net/ajax.html" -H "Authorization: Bearer %loading-status%" --data "comment=Failed because Sandbox tests did not pass" --data "status=FAILURE" --data "changeBuildStatus=${SupernovaWriterside.depParamRefs["teamcity.build.id"]}""""
        }
        step {
            name = "Tag suspicious Supernova build"
            id = "Tag_suspicious_build"
            type = "TagPin_yaegor"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE

            conditions {
                doesNotExist("env.TESTS_PASSED")
                equals("can.edit.tags", "true")
                equals("run.tests", "true")
            }
            param("system.username", "%system.pin.builds.user.name%")
            param("system.tags", "do-not-pin")
            param("system.buildId", "${SupernovaWriterside.depParamRefs["teamcity.build.id"]}")
            param("system.password", "credentialsJSON:bd17f9fe-9d84-46c9-9a4d-fc7403b4a626")
        }
        step {
            name = "Tag QC passing Supernova build"
            id = "Tag_OK_build"
            type = "TagPin_yaegor"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE

            conditions {
                exists("env.TESTS_PASSED")
                equals("can.edit.tags", "true")
                equals("run.tests", "true")
            }
            param("system.username", "%system.pin.builds.user.name%")
            param("system.tags", "qc-passed")
            param("system.buildId", "${WritersideSandbox.depParamRefs["teamcity.build.id"]}")
            param("system.password", "credentialsJSON:bd17f9fe-9d84-46c9-9a4d-fc7403b4a626")
        }
        script {
            name = "Check if build must fail"
            id = "Is_must_fail"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE

            conditions {
                doesNotExist("env.TESTS_PASSED")
                equals("run.tests", "true")
            }
            scriptContent = """echo "{{{Failure condition is met}}}""""
        }
        stepsOrder = arrayListOf("Remove_old_dir", "Deploy_plugin", "Patch_memory", "Run_build", "Stardust_Sandbox_Rename_Arecibo", "Stardust_Sandbox_Testing_step", "Check_build_status", "Fail_Supernova", "Tag_suspicious_build", "Tag_OK_build", "Is_must_fail")
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_1"
            buildType = "${SupernovaWriterside.id}"
            successfulOnly = true
            branchFilter = "+:*"
        }
    }

    failureConditions {
        testFailure = false
        nonZeroExitCode = false
        failOnText {
            id = "BUILD_EXT_2"
            conditionType = BuildFailureOnText.ConditionType.CONTAINS
            pattern = "{{{Failure condition is met}}}"
        }
    }

    features {
        notifications {
            id = "BUILD_EXT_1"
            notifierSettings = slackNotifier {
                connection = "PROJECT_EXT_486"
                sendTo = "#master-internal-notifications"
                messageFormat = verboseMessageFormat {
                    addBranch = true
                    addStatusText = true
                    maximumNumberOfChanges = 10
                }
            }
            branchFilter = """
                +:<default>
                +:master-internal
            """.trimIndent()
            buildStarted = true
            buildFailedToStart = true
            buildFailed = true
            buildFinishedSuccessfully = true
            firstBuildErrorOccurs = true
            buildProbablyHanging = true
        }
    }

    dependencies {
        artifacts(AbsoluteId("Documentation_Supplementals_AreciboTestingTool")) {
            id = "Stardust_Sandbox_Arecibo_Dependency"
            buildRule = lastSuccessful()
            artifactRules = "arecibo*.jar=>arecibo"
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Linux", "RQ_1")
    }
})

object WritersideSandboxContentPromoter : BuildType({
    templates(AbsoluteId("Documentation_StagingPromoterGenericS3"))
    name = "Writerside Sandbox Content Promoter"

    params {
        param("topics.root", "writerside-sandbox")
        param("update.index", "true")
        param("images.root", "writerside-sandbox")
    }

    dependencies {
        dependency(WritersideSandbox) {
            snapshot {
                onDependencyFailure = FailureAction.IGNORE
                onDependencyCancel = FailureAction.CANCEL
            }

            artifacts {
                id = "ARTIFACT_DEPENDENCY_1"
                artifactRules = """
                    webHelpS2.zip!/*.*=>intellij-platform/topics
                    webHelpImages.zip!/**.*=>intellij-platform/images
                """.trimIndent()
            }
        }
    }
})

object NebulaSourcesWriterside : GitVcsRoot({
    name = "Nebula Sources for Writerside"
    url = "ssh://git@git.jetbrains.team/stardust/nebula.git"
    branch = "refs/heads/master-internal"
    branchSpec = "+:refs/heads/*"
    checkoutPolicy = GitVcsRoot.AgentCheckoutPolicy.USE_MIRRORS
    authMethod = uploadedKey {
        userName = "megor"
        uploadedKey = "supernova_sources"
    }
})

object SupernovaSourcesWriterside : GitVcsRoot({
    name = "Supernova Sources for Writerside"
    url = "ssh://git@git.jetbrains.team/stardust/supernova.git"
    branch = "refs/heads/master"
    branchSpec = "+:refs/heads/*"
    checkoutPolicy = GitVcsRoot.AgentCheckoutPolicy.USE_MIRRORS
    authMethod = uploadedKey {
        userName = "megor"
        uploadedKey = "supernova_sources"
    }
})

object WritersideSandboxSources : GitVcsRoot({
    name = "Stardust Sandbox Sources"
    url = "ssh://git@git.jetbrains.team/stardust/stardust-sandbox.git"
    branch = "refs/heads/master"
    branchSpec = "+:refs/heads/*"
    checkoutPolicy = GitVcsRoot.AgentCheckoutPolicy.USE_MIRRORS
    authMethod = uploadedKey {
        userName = "megor"
        uploadedKey = "supernova_sources"
    }
})

object WritersideSources : GitVcsRoot({
    name = "WritersideSources"
    url = "ssh://git@git.jetbrains.team/stardust/stardust.git"
    branch = "refs/heads/master"
    branchSpec = """
        +:refs/heads/*
        -:refs/heads/help-builder
    """.trimIndent()
    checkoutPolicy = GitVcsRoot.AgentCheckoutPolicy.USE_MIRRORS
    authMethod = uploadedKey {
        userName = "megor"
        uploadedKey = "supernova_sources"
    }
})
