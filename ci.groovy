pipeline {
    agent any

    environment {
        RELEASE_BUCKET = "s3://configs/releases"
        RELEASE_COUNTER_FILE = "release_counter.txt"
        SIGN_KEY = credentials('ci-ed25519-private')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Compute Release ID') {
            steps {
                script {
                    // Read counter or initialize
                    COUNTER = readFile(RELEASE_COUNTER_FILE).trim()
                    if (!COUNTER) { COUNTER = "1" }
                    RELEASE_ID = String.format("release-%08d", COUNTER.toInteger())
                    env.RELEASE_ID = RELEASE_ID
                }
            }
        }

        stage('Create Full Snapshot') {
            steps {
                sh """
                mkdir -p /tmp/$RELEASE_ID
                cp -r . /tmp/$RELEASE_ID/
                TREE_HASH=\$(tar cf - /tmp/$RELEASE_ID | sha256sum | cut -d' ' -f1)
                echo \$TREE_HASH > /tmp/$RELEASE_ID/tree_hash.txt
                """
            }
        }

        stage('Sign Manifest') {
            steps {
                sh """
                cat > /tmp/$RELEASE_ID/manifest.json <<EOF
{
    "release_id": "$RELEASE_ID",
    "tree_hash": "\$(cat /tmp/$RELEASE_ID/tree_hash.txt)",
    "git_commit": "$(git rev-parse HEAD)"
}
EOF
                ed25519-sign -k $SIGN_KEY /tmp/$RELEASE_ID/manifest.json > /tmp/$RELEASE_ID/manifest.sig
                """
            }
        }

        stage('Upload to Storage') {
            steps {
                sh """
                aws s3 cp /tmp/$RELEASE_ID/ $RELEASE_BUCKET/$RELEASE_ID/ --recursive
                aws s3 cp /tmp/$RELEASE_ID/manifest.json $RELEASE_BUCKET/latest.json
                aws s3 cp /tmp/$RELEASE_ID/manifest.sig $RELEASE_BUCKET/latest.sig
                """
            }
        }

        stage('Increment Counter') {
            steps {
                script {
                    COUNTER = (COUNTER.toInteger() + 1).toString()
                    writeFile file: RELEASE_COUNTER_FILE, text: COUNTER
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
