// vim: set filetype=groovy:


def buildName = "${env.JOB_BASE_NAME.replaceAll("%2F", "-").replaceAll("\\.", "-").take(20)}-${env.BUILD_ID}"

pipeline {
    agent {
      kubernetes {
        cloud 'zeebe-ci'
        label "zeebe-ci-build_${buildName}"
        defaultContainer 'jnlp'
        yamlFile '.ci/podSpecs/distribution.yml'
      }
    }

    environment {
      NEXUS = credentials("camunda-nexus")
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', numToKeepStr: '10'))
        skipDefaultCheckout()
        timestamps()
        timeout(time: 120, unit: 'MINUTES')
    }

    stages {
        stage('Prepare') {
            steps {
                checkout scm
                container('maven') {
                    sh '.ci/scripts/distribution/prepare.sh'
                }
            }
        }

        stage('Build (Go)') {
            steps {
                container('golang') {
                    sh '.ci/scripts/distribution/build-go.sh'
                }
            }

            post {
                always {
                    junit testResults: "**/*/TEST-*.xml", keepLongStdio: true
                }
            }
        }

        stage('Build (Java)') {
            steps {
                container('maven') {
                    sh '.ci/scripts/distribution/build-java.sh'
                }
            }

            post {
                always {
                    junit testResults: "**/*/TEST-*.xml", keepLongStdio: true
                }
            }
        }

        stage('Upload') {
            when { branch 'develop' }
            steps {
                container('maven') {
                    sh '.ci/scripts/distribution/upload.sh'
                }
            }
        }

        stage('Post') {
            parallel {
                stage('Docker') {
                    when { branch 'develop' }

                    environment {
                        VERSION = readMavenPom(file: 'parent/pom.xml').getVersion()
                    }

                    steps {
                        build job: 'zeebe-docker', parameters: [
                            string(name: 'BRANCH', value: env.BRANCH_NAME),
                            string(name: 'VERSION', value: env.VERSION),
                            booleanParam(name: 'IS_LATEST', value: env.BRANCH_NAME == 'master')
                        ]
                    }
                }

                stage('Docs') {
                    when { anyOf { branch 'master'; branch 'live' } }
                    steps {
                        build job: 'zeebe-docs', parameters: [
                            string(name: 'BRANCH', value: env.BRANCH_NAME),
                            booleanParam(name: 'LIVE', value: env.BRANCH_NAME == 'master')
                        ]
                    }
                }
            }
        }
    }
}
