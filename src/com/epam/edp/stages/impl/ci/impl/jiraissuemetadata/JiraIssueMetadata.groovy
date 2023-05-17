/* Copyright 2021 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

package com.epam.edp.stages.impl.ci.impl.jiraissuemetadata

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.github.jenkins.lastchanges.pipeline.LastChangesPipelineGlobal
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import hudson.FilePath
import org.apache.commons.lang.RandomStringUtils
import java.nio.charset.StandardCharsets

@Stage(name = "create-jira-issue-metadata", buildTool = ["maven", "npm", "dotnet", "gradle", "go", "python", "terraform", "codenarc", "any"], type = [ProjectType.APPLICATION, ProjectType.AUTOTESTS, ProjectType.LIBRARY])
class JiraIssueMetadata {
    Script script

    def getChanges(workDir) {
        try {
            script.dir("${workDir}") {
                def publisher = new LastChangesPipelineGlobal(script).getLastChangesPublisher "LAST_SUCCESSFUL_BUILD", "SIDE", "LINE", true, true, "", "", "", "", ""
                publisher.publishLastChanges()
                return publisher.getLastChanges()
            }
        }
        catch (Exception ex) {
            script.println("[JENKINS][ERROR] TEST: ${ex}")
        }
    }

    def getJiraIssueMetadataCrTemplate(platform) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadata CR template")
        def temp = platform.getJsonPathValue("cm", "jim-template", ".data.jim\\.json")
        script.println("[JENKINS][DEBUG] JiraIssueMetadata template has been fetched ${temp}")
        return new JsonSlurperClassic().parseText(temp)
    }

    def getJiraIssueMetadataPayload(namespace, name) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadataPayload of ${name} Codebase CR")
        def payloadBase64 = script.sh(
            script: "kubectl get codebases.v2.edp.epam.com ${name} -n ${namespace} --output=jsonpath={.spec.jiraIssueMetadataPayload}| base64",
            returnStdout: true).trim()
        if (payloadBase64 == "") {
            return null
        }
        def payloadByteArray = Base64.getMimeDecoder().decode(payloadBase64)
        String payloadData = new String(payloadByteArray, StandardCharsets.UTF_8)
        script.println("[JENKINS][DEBUG] JiraIssueMetadataPayload of ${name} Codebase CR has been fetched - ${payloadData}")
        return new JsonSlurperClassic().parseText(payloadData)
    }

    def addCommitId(template, id) {
        if (template.spec.commits == "replace") {
            template.spec.commits = []
        }
        template.spec.commits.add(id)
    }

    def addTicketNumber(template, tickets) {
        if (template.spec.tickets == "replace") {
            template.spec.tickets = []
        }
        template.spec.tickets.addAll(tickets)
    }

    def parseJiraIssueMetadataTemplate(context, template, commits, ticketNamePattern, commitMsgPattern) {
        script.println("[JENKINS][DEBUG] Parsing JiraIssueMetadata template")
        def randomSeed = RandomStringUtils.randomAlphabetic(8)
        template.metadata.name = "${context.codebase.config.name}-${randomSeed}".toLowerCase()
        template.spec.codebaseName = context.codebase.config.name
        def jenkinsUrl = context.platform.getJsonPathValue("edpcomponent", "jenkins", ".spec.url")
        def links = []
        for (commit in commits) {
            def info = commit.getCommitInfo()
            script.println("[JENKINS][DEBUG] Commit message ${info.getCommitMessage()}")
            def tickets = info.getCommitMessage().findAll(ticketNamePattern)
            def id = info.getCommitId()
            if (!tickets) {
                script.println("[JENKINS][DEBUG] No tickets found in ${id} commit")
                continue
            }
            addCommitId(template, id)
            addTicketNumber(template, tickets)

            def url = ""
            if (context.job.type == "codereview") {
                url = "${jenkinsUrl}/job/${context.codebase.config.name}/job/${context.job.getParameterValue("BRANCH").toUpperCase()}-Code-review-${context.codebase.config.name}/${script.BUILD_NUMBER}/console"
            } else {
                url = "${jenkinsUrl}/job/${context.codebase.config.name}/job/${context.job.getParameterValue("BRANCH").toUpperCase()}-Build-${context.codebase.config.name}/${script.BUILD_NUMBER}/console"
            }
            info.getCommitMessage().trim().split("\n").each { it ->
                def matcher = (it =~ /${commitMsgPattern}/)
                if (matcher.matches()) {
                    def linkInfo = [
                            'ticket': matcher.group().find(/${ticketNamePattern}/),
                            'title' : "${matcher.group()} [${context.codebase.config.name}][${context.codebase.vcsTag}]",
                            'url'   : url,
                    ]
                    links.add(linkInfo)
                }
            }
        }

        return buildSpecPayloadTemplate(context, template, links)
    }

    def buildSpecPayloadTemplate(context, template, links) {
        if (links.size() == 0) {
            script.println("Skip creating JiraIssueMetadata CR because of commit message wasn't written by correct pattern.")
            return null
        }
        def payload = getPayloadField(context.job.ciProject, context.codebase.config.name, getVersion(context), context.codebase.vcsTag)
        if (payload == null) {
            template.spec.payload = new JsonBuilder(['issuesLinks': links]).toPrettyString()
        } else {
            payload.put('issuesLinks', links)
            template.spec.payload = new JsonBuilder(payload).toPrettyString()
        }
        return JsonOutput.toJson(template)
    }

    def getVersion(context) {
        return context.codebase.config.versioningType == "default" ?
                context.codebase.version :
                getCodebaseBranch(context.codebase.config.codebase_branch, context.git.branch).version
    }

    def getPayloadField(namespace, component, version, gitTag) {
        def payload = getJiraIssueMetadataPayload(namespace, component)
        if (payload == null) {
            return null
        }

        def values = [
                EDP_COMPONENT   : component,
                EDP_VERSION     : version,
                EDP_SEM_VERSION : version.minus(~/(-RC|-SNAPSHOT)/),
                EDP_GITTAG      : gitTag]
        payload.each { x ->
            values.each { k, v ->
                payload."${x.key}" = payload."${x.key}".replaceAll(k, v)
            }
        }
        return payload
    }

    def createJiraIssueMetadataCR(platform, path) {
        script.println("[JENKINS][DEBUG] Trying to create JiraIssueMetadata CR")
        script.sh(
                script: "cat ${path.getRemote()}",
                returnStdout: true).trim()
        platform.apply(path.getRemote())
        script.println("[JENKINS][INFO] JiraIssueMetadata CR has been created")
    }

    def saveTemplateToFile(outputFilePath, template) {
        def jiraIssueMetadataTemplateFile = new FilePath(Jenkins.getInstance().
                getComputer(script.env['NODE_NAME']).
                getChannel(), outputFilePath)
        jiraIssueMetadataTemplateFile.write(template, null)
        return jiraIssueMetadataTemplateFile
    }

    def tryToCreateJiraIssueMetadataCR(workDir, platform, template) {
        def filePath = saveTemplateToFile("${workDir}/jim-template.json", template)
        createJiraIssueMetadataCR(platform, filePath)
    }

    void run(context) {
        try {
            def ticketNamePattern = context.codebase.config.ticketNamePattern
            script.println("[JENKINS][DEBUG] Ticket name pattern has been fetched ${ticketNamePattern}")
            def changes = getChanges(context.workDir)
            def commits = changes.getCommits()
            if (commits == null || commits.size() == 0) {
                script.println("[JENKINS][INFO] No changes since last successful build. Skip creating JiraIssueMetadata CR")
            } else {
                def template = getJiraIssueMetadataCrTemplate(context.platform)
                script.println("[JENKINS][DEBUG] jim template ${template}")
                def parsedTemplate = parseJiraIssueMetadataTemplate(context, template, commits, ticketNamePattern, context.codebase.config.commitMessagePattern)
                if (parsedTemplate == null) {
                    return
                }
                tryToCreateJiraIssueMetadataCR(context.workDir, context.platform, parsedTemplate)
            }
        } catch (Exception ex) {
            script.error "[JENKINS][ERROR] Couldn't correctly finish 'create-jira-issue-metadata' stage due to exception: ${ex}"
        }
    }

    @NonCPS
    def private getCodebaseBranch(codebaseBranch, gitBranchName) {
        return codebaseBranch.stream().filter({
            it.branchName == gitBranchName
        }).findFirst().get()
    }
}
