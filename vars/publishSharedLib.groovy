/**
 * Shared pipeline for the shared-libs repo (url-shortener-shared-libs): build and test every module, then publish any
 * module version that is not yet in GitHub Packages.
 *
 * The consuming repo's Jenkinsfile is expected to be just:
 *
 *     publishSharedLib()
 *
 * Every commit is built and tested, but a published release version is immutable in GitHub Packages (publishing it
 * again fails with a conflict), so the Publish stage first asks the registry which of the modules already have the
 * current version and publishes only the rest. Releasing is therefore a deliberate version bump in the repo's build
 * file, which is what ADR-0004 asks for ("bumped explicitly when it wants a contract change"), and a build of an
 * already-released version is a harmless no-op rather than a failure. Checking each module separately also lets a
 * publish that stopped partway be completed by the next build.
 *
 * Needs a Jenkins "username with password" credential with the id below, holding a GitHub user and a token with
 * write:packages. The repo's own build file reads them from GITHUB_ACTOR / GITHUB_TOKEN.
 *
 * @param config.repository  Optional. The GitHub repository whose package registry is published to, as
 *                            "owner/name". Defaults to "codewithjams-url-shortener/url-shortener-shared-libs".
 * @param config.modules     Optional. The Gradle modules that are published. Defaults to links-contract,
 *                            event-contracts and service-common.
 */
def call(final Map config = [:]) {

	final String repository = config.repository ?: 'codewithjams-url-shortener/url-shortener-shared-libs'
	final List<String> modules = config.modules ?: ['links-contract', 'event-contracts', 'service-common']
	final String groupPath = 'io/url-shortener'
	final String registryUrl = "https://maven.pkg.github.com/${repository}"
	final String credentialsId = 'github-packages-publish'

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

			stage('Publish') {
				steps {
					container('gradle') {
						withCredentials([usernamePassword(
								credentialsId: credentialsId,
								usernameVariable: 'GITHUB_ACTOR',
								passwordVariable: 'GITHUB_TOKEN'
						)]) {
							script {
								publishUnpublishedModules(registryUrl, groupPath, modules)
							}
						}
					}
				}
			}

		}

	}

}

/**
 * Publishes the modules whose current version is not yet in the registry, and says so when there is nothing to do.
 */
private void publishUnpublishedModules(final String registryUrl, final String groupPath, final List<String> modules) {
	final String version = sh(
			script: "./gradlew -q properties --property version | sed -n 's/^version: //p'",
			returnStdout: true
	).trim()
	if (!version) {
		error "publishSharedLib: could not determine the version to publish from the Gradle build"
	}
	// Plain loops rather than collect/findAll: closures passed to those are unreliable when they run pipeline steps.
	final List<String> unpublished = []
	for (final String module : modules) {
		if (!isPublished(registryUrl, groupPath, module, version)) {
			unpublished << module
		}
	}
	if (unpublished.isEmpty()) {
		echo "publishSharedLib: version ${version} of ${modules.join(', ')} is already published, nothing to do. " +
				"Bump the version in the build file to release a new one."
		return
	}
	echo "publishSharedLib: publishing version ${version} of ${unpublished.join(', ')}"
	final List<String> tasks = []
	for (final String module : unpublished) {
		tasks << ":${module}:publish"
	}
	sh "./gradlew ${tasks.join(' ')}"
}

/**
 * Asks the registry whether a module's POM for this version exists.
 *
 * GitHub Packages answers an existing module with a 302 redirect to a signed download URL, so the check follows
 * redirects (-L) and sees a 200; it does not send the credentials on to the other host. A missing module is a 404.
 *
 * Anything other than a clear 200 or 404 (bad credentials, the registry down, no network) is an error, never
 * "not published": treating it as a miss would try to publish and either fail confusingly or hide the real problem.
 * The credentials are expanded by the shell from the environment, never interpolated into the script text.
 */
private boolean isPublished(final String registryUrl, final String groupPath, final String module,
							final String version) {
	final String pomUrl = "${registryUrl}/${groupPath}/${module}/${version}/${module}-${version}.pom"
	final String status = sh(
			script: "curl -sL -o /dev/null -w '%{http_code}' -u \"\$GITHUB_ACTOR:\$GITHUB_TOKEN\" ${pomUrl}",
			returnStdout: true
	).trim()
	if (status == '200') {
		return true
	}
	if (status == '404') {
		return false
	}
	error "publishSharedLib: unexpected HTTP ${status} checking ${pomUrl} - check the 'github-packages-publish' " +
			"credential (a token with read:packages and write:packages) and the registry's availability"
}

/**
 * Pod template for the ephemeral Kubernetes build agent (see ADR-0008): just Gradle, since this pipeline neither
 * builds images nor touches the GitOps repo.
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
"""
}
