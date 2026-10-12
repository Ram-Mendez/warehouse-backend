pipeline {
    // Configure a trusted Linux agent with JDK 21 and access to its Docker daemon.
    agent { label 'linux-docker-java21' }
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timeout(time: 20, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Verify Java and Docker') {
            steps {
                sh '''
                    set -eu
                    java -version
                    test "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')" = "21"
                    docker info > /dev/null
                    chmod +x mvnw
                    ./mvnw -B -ntp --version
                '''
            }
        }
        stage('Maven clean verify: tests and JAR') {
            // verify runs test and package in order; a failed test stops the pipeline.
            // PostgreSQL is isolated by Testcontainers, not the local Compose database.
            steps { sh './mvnw -B -ntp clean verify' }
            post {
                always { junit testResults: 'target/surefire-reports/TEST-*.xml' }
                success { archiveArtifacts artifacts: 'target/warehouse-backend-*.jar', fingerprint: true }
            }
        }
        stage('Build local Docker image') {
            steps { sh 'docker build -t warehouse-backend:${BUILD_NUMBER} .' }
        }
    }
}
