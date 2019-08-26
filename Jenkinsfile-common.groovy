@Library('common-pipelines@10.12.0') _

import java.util.Arrays

// -----------------------------------------------------------------------------------
// The following params are automatically provided by the callback gateway as inputs
// to the Jenkins pipeline that starts this job.
//
// params["SHA"]                    - Sha used to start the pipeline
// params["BRANCH_NAME"]            - Name of GitHub branch the SHA is associated with
// params["UNIQUE_BUILD_ID"]        - A randomly generated unique ID for this job run
// params["ENQUEUED_AT_TIMESTAMP"]  - Unix timestamp generated by callback gateway
// params["JSON"]                   - Extensible json doc with extra information
// params["GITHUB_REPOSITORY"]      - GitHub ssh url of repository (git://....)
// -----------------------------------------------------------------------------------

docker = new org.doordash.Docker()
doorctl = new org.doordash.Doorctl()
github = new org.doordash.Github()
pulse = new org.doordash.Pulse()
slack = new org.doordash.Slack()
JenkinsDd = org.doordash.JenkinsDd

gitUrl = params["GITHUB_REPOSITORY"]
sha = params["SHA"]


/**
 * Returns the service name which is useful for builds and deployments.
 */
def getServiceName() {
  return "payment-service"
}


/**
 * Returns slack CD channel name.
 */
def getCDSlackChannel() {
  return "#eng-payment-cd"
}



/**
 * Returns the service docker image url which is useful for builds and deployments.
 */
def getDockerImageUrl() {
  def serviceName = getServiceName()
  return "ddartifacts-docker.jfrog.io/doordash/${serviceName}"
}


/**
 * Build, Tag, and Push a Docker image for a Microservice.
 * If there already exists a docker image for the sha, then it will skip 'make build tag push'.
 * <br>
 * <br>
 * Requires:
 * <ul>
 * <li>Makefile with build, tag, and push targets
 * </ul>
 * Provides:
 * <ul>
 * <li>branch = GitHub branch name
 * <li>doorctl = Path in order to execute doorctl from within the Makefile
 * <li>SHA = GitHub SHA
 * <li>CACHE_FROM = url:tag of recent Docker image to speed up subsequent builds that use the --cache-from option
 * <li>env.ARTIFACTORY_USERNAME = Artifactory Username to install Python packages
 * <li>env.ARTIFACTORY_PASSWORD = Artifactory Password to install Python packages
 * <li>env.FURY_TOKEN = Gemfury Token to install Python packages
 * </ul>
 */
def buildTagPushNoRelease(Map optArgs = [:], String gitUrl, String sha, String branch, String serviceName) {
  Map o = [
          dockerDoorctlVersion: 'v0.0.118',
          dockerImageUrl      : getDockerImageUrl()
  ] << optArgs
  String loadedCacheDockerTag
  try {
    sh """|#!/bin/bash
          |set -ex
          |docker pull ${o.dockerImageUrl}:${sha}
          |""".stripMargin()
    println "Docker image was found for ${o.dockerImageUrl}:${sha} - Skipping 'make build tag push'"
    loadedCacheDockerTag = sha
  } catch (oops) {
    println "No docker image was found for ${o.dockerImageUrl}:${sha} - Running 'make build tag push'"
  }
  if (loadedCacheDockerTag == null) {
    loadedCacheDockerTag = docker.findAvailableCacheFrom(gitUrl, sha, o.dockerImageUrl)
    if (loadedCacheDockerTag == null) {
      loadedCacheDockerTag = "noCacheFoundxxxxxxx"
    }
    String doorctlPath
    sshagent(credentials: ['DDGHMACHINEUSER_PRIVATE_KEY']) {
      doorctlPath = doorctl.installIntoWorkspace(o.dockerDoorctlVersion)
    }
    String cacheFromValue = "${o.dockerImageUrl}:${loadedCacheDockerTag}"
    github.doClosureWithStatus({
      withCredentials([
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_NAME', variable: 'ARTIFACTORY_USERNAME'),
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_PASS_URLENCODED', variable: 'ARTIFACTORY_PASSWORD'),
        string(credentialsId: 'FURY_TOKEN', variable: 'FURY_TOKEN')
      ]) {
        sh """|#!/bin/bash
              |set -ex
              |make build tag push \\
              | branch=${branch} \\
              | doorctl=${doorctlPath} \\
              | SHA=${sha} \\
              | CACHE_FROM=${cacheFromValue}
              |""".stripMargin()
      }
    }, gitUrl, sha, "Docker Build Tag Push - [NoRelease]", "${BUILD_URL}testReport")
  }
}

/**
 * Tag and Push a docker image with releaseTag
 */
def tagPushRelease(String gitUrl, String sha, String releaseTag) {

  github.doClosureWithStatus({
      withCredentials([
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_NAME', variable: 'ARTIFACTORY_USERNAME'),
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_PASS_URLENCODED', variable: 'ARTIFACTORY_PASSWORD'),
        string(credentialsId: 'FURY_TOKEN', variable: 'FURY_TOKEN')
      ]) {
        sh """|#!/bin/bash
              |set -ex
              |
              |make build release-tag release-push \\
              | RELEASE_TAG=${releaseTag}
              | CACHE_FROM=${sha}
              |""".stripMargin()
      }
    }, gitUrl, sha, "Docker Tag Push - [Release]", "${BUILD_URL}testReport")
}


/**
 * Runs a local container useful to run CI tests on
 */
def runCIcontainer(String serviceName, String tag) {
  def dockerImageUrl = getDockerImageUrl()
  github.doClosureWithStatus({
    sh """|#!/bin/bash
          |set -eox
          |docker rm ${serviceName}-ci || true
          |make run-ci-container \\
          |    CI_BASE_IMAGE="${dockerImageUrl}:${tag}" \\
          |    CI_CONTAINER_NAME="${serviceName}-ci"
          |""".stripMargin()
  }, gitUrl, tag, "Unit Tests", "${BUILD_URL}testReport")
}


def loadJunit(fileName) {
  def fe = fileExists "${fileName}"
  if(fe) {
    junit "${fileName}"
    archiveArtifacts artifacts: "${fileName}"
  } else {
    currentBuild.result = 'UNSTABLE'
  }
}


/**
 * Run Unit Tests on the CI container and archive the report
 */
def runUnitTests(String serviceName) {
  def outputFile = "pytest-unit.xml"
  github.doClosureWithStatus({
    try {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci make test-unit PYTEST_ADDOPTS="--junitxml ${outputFile}"
            |""".stripMargin()
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker cp ${serviceName}-ci:/home/${outputFile} ${outputFile}
            |""".stripMargin()
      loadJunit(outputFile)
    }
  }, gitUrl, sha, "Unit Tests", "${BUILD_URL}testReport")
}



/**
 * Run Integration Tests on the CI container and archive the report
 */
def runIntegrationTests(String serviceName) {
  def outputFile = "pytest-integration.xml"
  github.doClosureWithStatus({
    try {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci make test-integration PYTEST_ADDOPTS="--junitxml ${outputFile}"
            |""".stripMargin()
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker cp ${serviceName}-ci:/home/${outputFile} ${outputFile}
            |""".stripMargin()
      loadJunit(outputFile)
    }
  }, gitUrl, sha, "Integration Tests", "${BUILD_URL}testReport")
}


/**
 * Run Pulse Tests on the CI container and archive the report
 */
def runPulseTests(String serviceName) {
  def outputFile = "pulse-test.xml"
  github.doClosureWithStatus({
    withCredentials([
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_NAME', variable: 'ARTIFACTORY_USERNAME'),
        string(credentialsId: 'ARTIFACTORY_MACHINE_USER_PASS_URLENCODED', variable: 'ARTIFACTORY_PASSWORD'),
        string(credentialsId: 'FURY_TOKEN', variable: 'FURY_TOKEN')
      ]){
    try {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci make test-pulse PYTEST_ADDOPTS="--junitxml ${outputFile}"
            |""".stripMargin()
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker cp ${serviceName}-ci:/home/pulse/${outputFile} ${outputFile}
            |""".stripMargin()
      loadJunit(outputFile)
    }}
  }, gitUrl, sha, "Pulse Tests", "${BUILD_URL}testReport")
}


/**
 * Run the linter on the CI container and archive the report
 */
def runLinter(String serviceName) {
  def outputFile = "flake8.xml"
  github.doClosureWithStatus({
    try {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci make test-lint FLAKE8_ADDOPTS="--format junit-xml --output-file=${outputFile}"
            |""".stripMargin()
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker cp ${serviceName}-ci:/home/${outputFile} ${outputFile}
            |""".stripMargin()
      loadJunit(outputFile)
    }
  }, gitUrl, sha, "Linting", "${BUILD_URL}testReport")
}


/**
 * Run the static type checker on the CI container and archive the report
 */
def runTyping(String serviceName) {
  def outputFile = "mypy.xml"
  github.doClosureWithStatus({
    try {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci make test-typing MYPY_ADDOPTS="--junit-xml ${outputFile}"
            |""".stripMargin()
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker cp ${serviceName}-ci:/home/${outputFile} ${outputFile}
            |""".stripMargin()
      loadJunit(outputFile)
    }
  }, gitUrl, sha, "Typing", "${BUILD_URL}testReport")
}

def commentHooksFailed(serviceName) {
  def maxFiles = 9
  try {
    def filesList = Arrays.asList(sh(
      script: """|#!/bin/bash
                 |set +ex
                 |docker exec ${serviceName}-ci git diff --name-only
                 |""".stripMargin(),
      returnStdout: true
    ).split(/\n/))
    def changeSummary = sh(
      script: """|#!/bin/bash
                 |set +ex
                 |docker exec ${serviceName}-ci git diff --shortstat
                 |""".stripMargin(),
      returnStdout: true
    ).trim()
    if (filesList.size() > maxFiles) {
      filesList = filesList.take(maxFiles) + ["..."]
    }
    def placeholder = """|```
                         |${filesList.join("\n")}
                         |${changeSummary}
                         |```""".stripMargin()

    github.commentOnPrBySha(
      gitUrl, sha,
      """|:x: Looks like you missed running some pre-commit hooks on your PR.
         |
         |Please ensure pre-commit hooks are set up properly
         |(see [README.md](../blob/${sha}/README.md#setup-local-environment)),
         |and run pre-commit hooks on all files to fix them in place,
         |`pre-commit run --all-files`:
         |
         |SUMMARY-PLACEHOLDER
         |
         |Then, create and push a commit with those changes so pre-commit hooks pass in CI.
         |""" \
          .stripMargin() \
          .split(/\n{2,}/) \
          .collect { it.replace("\n", " ") } \
          .join("\n\n") \
          .replace("SUMMARY-PLACEHOLDER", placeholder)
    )
  } catch (e) {
    println "Failed commenting on ${gitUrl}:${sha} that pre-commit hooks failed"
  }
}


/**
 * Run the pre-commit checks (excluding mypy/flake8)
 */
def runHooks(String serviceName) {
  github.doClosureWithStatus({
    try {
      sh """|#!/bin/bash
            |set -eoxo pipefail
            |docker exec ${serviceName}-ci git reset --hard
            |docker exec ${serviceName}-ci make test-install-hooks PRE_COMMIT_HOME=/home
            |docker exec ${serviceName}-ci make test-hooks PRE_COMMIT_HOME=/home SKIP=flake8,mypy HOOKS_ADDOPTS="--show-diff-on-failure"
            |""".stripMargin()
    } catch (e) {
      commentHooksFailed(serviceName)
      throw e
    } finally {
      sh """|#!/bin/bash
            |set -eox
            |docker exec ${serviceName}-ci touch pre-commit.log
            |docker cp ${serviceName}-ci:/home/pre-commit.log pre-commit-error.log
            |""".stripMargin()
      archiveArtifacts artifacts: "pre-commit-error.log"
    }
  }, gitUrl, sha, "Hooks", "${BUILD_URL}console")
}


/**
 * Deploy a Microservice using Helm.
 */
def deployHelm(Map optArgs = [:], String tag, String serviceName, String env) {
  Map o = [
          helmCommand: 'upgrade',
          helmFlags: '--install',
          helmChartPath: "_infra/charts/${serviceName}",
          helmValuesFile: "values-${env}.yaml",
          helmRelease: serviceName,
          k8sCredFileCredentialId: "K8S_CONFIG_${env.toUpperCase()}_NEW",
          k8sNamespace: env,
          tillerNamespace: env,
          timeoutSeconds: 600
  ] << serviceNameEnvToOptArgs(serviceName, env) << optArgs
  withCredentials([file(credentialsId: o.k8sCredFileCredentialId, variable: 'k8sCredsFile')]) {
    sh """|#!/bin/bash
      |set -ex
      |
      |# use --wait flag for helm to wait until all pod and services are in "ready" state.
      |# working together with k8s readiness probe to prevent uninitialized pod serving traffic
      |helm="docker run --rm -v ${k8sCredsFile}:/root/.kube/config -v ${WORKSPACE}:/apps alpine/helm:2.10.0"
      |HELM_OPTIONS="${o.helmCommand} ${o.helmRelease} ${o.helmChartPath} \\
      | --values ${o.helmChartPath}/${o.helmValuesFile} --set web.tag=${tag} --set cron.tag=${tag} ${o.helmFlags} \\
      | --tiller-namespace ${o.tillerNamespace} --namespace ${o.k8sNamespace} \\
      | --wait --timeout ${o.timeoutSeconds}"
      |
      |# log manifest to CI/CD
      |\${helm} \${HELM_OPTIONS} --debug --dry-run
      |
      |\${helm} \${HELM_OPTIONS}
      |""".stripMargin()
  }
}


/**
 * Deploy Pulse for a Microservice.
 */
def deployPulse(Map optArgs = [:], String gitUrl, String sha, String branch, String serviceName, String env) {
  Map o = [
          k8sNamespace: env,
          pulseVersion: '2.1',
          pulseDoorctlVersion: 'v0.0.118',
          pulseRootDir: 'pulse'
  ] << serviceNameEnvToOptArgs(serviceName, env) << optArgs

  String PULSE_VERSION = o.pulseVersion
  String SERVICE_NAME = serviceName
  String KUBERNETES_CLUSTER = o.k8sNamespace
  String DOORCTL_VERSION = o.pulseDoorctlVersion
  String PULSE_DIR = o.pulseRootDir

  sshagent(credentials: ['DDGHMACHINEUSER_PRIVATE_KEY']) {
    // install doorctl and grab its executable path
    String doorctlPath = doorctl.installIntoWorkspace(DOORCTL_VERSION)
    // deploy Pulse
    pulse.deploy(PULSE_VERSION, SERVICE_NAME, KUBERNETES_CLUSTER, doorctlPath, PULSE_DIR)
  }
}


/**
 * Deploy Blocking Pulse for a Microservice.
 */
def deployBlockingPulse(Map optArgs = [:], String gitUrl, String sha, String branch, String serviceName, String env) {
  Map o = [
          k8sNamespace: env,
          pulseVersion: '2.1',
          pulseDoorctlVersion: 'v0.0.118',
          pulseRootDir: 'pulse'
  ] << serviceNameEnvToOptArgs(serviceName, env) << optArgs

  String PULSE_VERSION = o.pulseVersion
  String SERVICE_NAME = serviceName
  String SERVICE_SHA = sha
  String KUBERNETES_CLUSTER = o.k8sNamespace
  String DOORCTL_VERSION = o.pulseDoorctlVersion
  String PULSE_DIR = o.pulseRootDir
  Integer TIMEOUT_S = 360
  Integer SLEEP_S = 5

  sshagent(credentials: ['DDGHMACHINEUSER_PRIVATE_KEY']) {
    // install doorctl and grab its executable path
    String doorctlPath = doorctl.installIntoWorkspace(DOORCTL_VERSION)
    // deploy Pulse
    pulse.blockingDeploy(PULSE_VERSION, SERVICE_NAME, SERVICE_SHA, KUBERNETES_CLUSTER, doorctlPath, PULSE_DIR, TIMEOUT_S, SLEEP_S)
  }
}


/**
 * Given a service name and environment name like 'sandbox1', 'staging', and 'production',
 * resolve the optional arguments that vary per environment.
 */
def serviceNameEnvToOptArgs(String serviceName, String env) {
  if (env ==~ /^sandbox([0-9]|1[0-5])/) { // sandbox0 - sandbox15
    return [
            helmFlags: '--install --force',
            helmValuesFile: "values-${env}.yaml",
            helmRelease: "${serviceName}-${env}",
            k8sCredFileCredentialId: 'K8S_CONFIG_STAGING_NEW',
            k8sNamespace: 'staging',
            tillerNamespace: 'staging'
    ]
  } else if (env == 'staging') {
    return [
            helmFlags: '--install --force',
            helmValuesFile: 'values-staging.yaml',
            helmRelease: serviceName,
            k8sCredFileCredentialId: 'K8S_CONFIG_STAGING_NEW',
            k8sNamespace: 'staging',
            tillerNamespace: 'staging'
    ]
  } else if (env == 'prod' || env == 'production') {
    return [
            helmFlags: '--install',
            helmValuesFile: 'values-prod.yaml',
            helmRelease: serviceName,
            k8sCredFileCredentialId: 'K8S_CONFIG_PROD_NEW',
            k8sNamespace: 'prod',
            tillerNamespace: 'prod'
    ]
  } else {
    error("Unknown env value of '${env}' passed.")
  }
}


def removeAllContainers() {
  sh """|#!/bin/bash
        |set -x
        |docker ps -a -q | xargs --no-run-if-empty docker rm -f || true
        |""".stripMargin()
}


/**
 * Prompt the user to decide if we can deploy to production.
 * The user has 10 minutes to choose between Proceed or Abort.
 * If Proceed, then we should proceed. If Abort or Timed-out,
 * then we should cleanly skip the rest of the steps in the
 * pipeline without failing the pipeline.
 *
 * @return True if we can deploy to prod. False, otherwise.
 */
def inputCanDeployToProd() {
  boolean canDeployToProd = false
  try {
    timeout(time: 10, unit: 'MINUTES') {
      input(id: 'userInput', message: 'Deploy to production?')
      canDeployToProd = true
    }
  } catch (err) {
    println "Timed out or Aborted! Will not deploy to production."
    println err
  }
  return canDeployToProd
}


/**
 * Notification utilities
 */

def notifySlackChannelDeploymentStatus(stage, sha, buildNumber, status, mentionChannel = false) {
    def slackChannel = getCDSlackChannel()
    def serviceName = getServiceName()
    mention = mentionChannel ? "@here" : ""
    slack.notifySlackChannel("${mention}[${stage}][${serviceName}] deployment status [${status}]: <${JenkinsDd.instance.getBlueOceanJobUrl()}|[${buildNumber}]>", slackChannel)
}

return this
