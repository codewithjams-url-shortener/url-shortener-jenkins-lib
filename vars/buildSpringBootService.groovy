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
	// Where the build pod pushes the image. The cluster pulls the same image as localhost:5100/<name> (the repository in
	// each values-local.yaml), which its nodes' containerd maps to host.docker.internal:5100, but inside the build pod
	// localhost is the pod itself, so the push goes to the address the registry is actually reachable on.
	final String registry = 'host.docker.internal:5100'
	final String gitOpsRepoHost = 'github.com/codewithjams-url-shortener/url-shortener-gitops.git'
	final String gitOpsCredentialsId = 'gitops-repo-push'
	// Read-only (read:packages): the services only resolve the shared libs from GitHub Packages. Their build files read
	// these two variable names, the same ones url-shortener-shared-libs publishes with.
	final String githubPackagesCredentialsId = 'github-packages-read'

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
						withCredentials([usernamePassword(
								credentialsId: githubPackagesCredentialsId,
								usernameVariable: 'GITHUB_ACTOR',
								passwordVariable: 'GITHUB_TOKEN'
						)]) {
							sh './gradlew clean build'
						}
					}
				}
			}

			stage('Build & Push Image') {
				steps {
					container('docker') {
						script {
							// The commit the SCM checkout built, not the output of `git rev-parse` here: this container runs
							// as root and the workspace belongs to the jenkins user, so git stops with "dubious ownership".
							if (!env.GIT_COMMIT) {
								error 'buildSpringBootService: GIT_COMMIT is not set, so there is no commit to tag the image with'
							}
							env.IMAGE_TAG = env.GIT_COMMIT.take(7)
						}
						// The daemon is the dind sidecar's and takes a few seconds to start after the pod does.
						sh '''
							timeout 120 sh -c 'until docker info >/dev/null 2>&1; do sleep 1; done' || {
								echo 'The Docker daemon in the dind sidecar did not start within 120s' >&2
								exit 1
							}
						'''
						// The Dockerfile's Gradle build reads the shared libs from GitHub Packages with these as BuildKit
						// secrets. They are named by variable (env=), not passed by value, so the token never appears on
						// the command line or in an image layer.
						withCredentials([usernamePassword(
								credentialsId: githubPackagesCredentialsId,
								usernameVariable: 'GITHUB_ACTOR',
								passwordVariable: 'GITHUB_TOKEN'
						)]) {
							sh """
								docker buildx build \
									--secret id=github_actor,env=GITHUB_ACTOR \
									--secret id=github_token,env=GITHUB_TOKEN \
									-t ${registry}/${dockerImage}:${env.IMAGE_TAG} \
									--push .
							"""
						}
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
 *
 * <p>The image build does not use the node's Docker socket: the cluster's nodes run containerd and have no Docker daemon,
 * so a hostPath mount of /var/run/docker.sock is just an empty directory. Instead the pod runs its own daemon, the
 * privileged {@code dind} sidecar, which the {@code docker} container reaches over localhost:2375 (no TLS, and nothing
 * outside the pod can reach it). It trusts the local registry, which speaks plain HTTP, and its layers live in the pod's
 * emptyDir, so they go away with the pod instead of piling up as build cache on the node.
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
      env:
        - name: DOCKER_HOST
          value: tcp://localhost:2375
    - name: dind
      image: docker:24-dind
      securityContext:
        privileged: true
      env:
        - name: DOCKER_TLS_CERTDIR
          value: ''
      args: ['--insecure-registry=host.docker.internal:5100']
    - name: git
      image: alpine/git:2.45.2
      command: ['sleep']
      args: ['infinity']
"""
}
