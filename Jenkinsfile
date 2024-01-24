pipeline {
  agent any
  stages {
    stage('build') {
      steps {
        sh '''cd /root/code/gluten
bash ./ep/build-clickhouse/src/build_clickhouse.sh'''
      }
    }

  }
}