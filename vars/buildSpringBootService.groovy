/**
 * Shared pipeline for a Spring Boot service repo (url-service, redirect-service,
 * analytics-service): build/test, build and push the Docker image to the local registry, then
 * bump its image tag in url-shortener-gitops so ArgoCD picks up the new version. See ADR-0008.
 *
 * A consuming repo's Jenkinsfile is expected to be just:
 *
 *     buildSpringBootService(serviceName: 'url-service')
 *
 * @param config.serviceName  Required. The service's name, e.g. "url-service" - used as the
 *                             GitOps values directory (services/<serviceName>/values-local.yaml)
 *                             and, unless dockerImage overrides it, the image repository name.
 * @param config.dockerImage  Optional. The image repository name to build and push, e.g.
 *                             "url-service". Defaults to config.serviceName.
 */
def call(Map config) {

	if (!config.serviceName) {
		error "buildSpringBootService: 'serviceName' is required"
	}

	final String serviceName = config.serviceName
	final String dockerImage = config.dockerImage ?: serviceName
	final String registry = 'localhost:5100'
	final String gitOpsRepoHost = 'github.com/codewithjams-url-shortener/url-shortener-gitops.git'
	final String gitOpsCredentialsId = 'gitops-repo-push'

	pipeline {

		agent {
			kubernetes {
				yaml podTemplateYaml()
			}
		}

		options {
			timestamps()
			buildDiscarder(logRotator(numToKeepStr: '20'))
		}

		triggers {
			// Poll SCM, not a webhook - see ADR-0008 for why (no publicly reachable Jenkins).
			pollSCM('H/5 * * * *')
		}

		stages {

			stage('Build & Test') {
				steps {
					container('gradle') {
						sh './gradlew clean build'
					}
				}
			}

			stage('Build & Push Image') {
				steps {
					container('docker') {
						script {
							env.IMAGE_TAG = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
						}
						sh """
							docker buildx build \
								--build-context m2=/root/.m2 \
								-t ${registry}/${dockerImage}:${env.IMAGE_TAG} \
								--push .
						"""
					}
				}
			}

			stage('Bump GitOps Tag') {
				steps {
					container('git') {
						withCredentials([usernamePassword(
								credentialsId: gitOpsCredentialsId,
								usernameVariable: 'GIT_USER',
								passwordVariable: 'GIT_TOKEN'
						)]) {
							sh """
								apk add --no-cache yq
								rm -rf gitops-checkout
								git clone https://\${GIT_USER}:\${GIT_TOKEN}@${gitOpsRepoHost} gitops-checkout
								cd gitops-checkout
								yq -i ".image.tag = \\"\${IMAGE_TAG}\\"" services/${serviceName}/values-local.yaml
								git config user.email 'jenkins@url-shortener.local'
								git config user.name 'Jenkins'
								git commit -am "chore(${serviceName}): bump image tag to \${IMAGE_TAG}"
								git push
							"""
						}
					}
				}
			}

		}

	}

}

/**
 * Pod template for the ephemeral Kubernetes build agent (see ADR-0008): one container per
 * toolchain the stages above need, so each {@code container(...)} step runs in the right one.
 */
private String podTemplateYaml() {
	return """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: gradle
      image: gradle:8-jdk21
      command: ['sleep']
      args: ['infinity']
    - name: docker
      image: docker:24-cli
      command: ['sleep']
      args: ['infinity']
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock
    - name: git
      image: alpine/git:2.45.2
      command: ['sleep']
      args: ['infinity']
  volumes:
    - name: docker-sock
      hostPath:
        path: /var/run/docker.sock
"""
}
