import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import org.junit.Before
import org.junit.Test

import static org.assertj.core.api.Assertions.assertThat

/**
 * Exercises vars/buildSpringBootService.groovy against JenkinsPipelineUnit's mocked pipeline
 * steps, since pipeline/stage/sh/container/etc. only resolve for real inside a live Jenkins CPS
 * interpreter - this is the closest thing to "compile and run it" without one. Extends
 * DeclarativePipelineTest rather than the plain BasePipelineTest since buildSpringBootService uses
 * the declarative `pipeline { ... }` syntax, not scripted `node { ... }`.
 */
class BuildSpringBootServiceTest extends DeclarativePipelineTest {

	@Before
	void setUp() throws Exception {
		super.setUp()
		// The SCM checkout sets GIT_COMMIT, which is where the image tag comes from. `error` is replaced by one that
		// throws, as Jenkins' does, since JenkinsPipelineUnit's own only records the call.
		binding.setVariable('env', [GIT_COMMIT: 'abcdef0123456789'])
		helper.registerAllowedMethod('error', [String]) { String message -> throw new RuntimeException(message) }
	}

	@Test
	void call_shouldTagTheImageWithTheFirstSevenCharactersOfTheCommit_whenBuildingTheImage() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def imageBuild = shCommands().find { it.contains('docker buildx build') }
		assertThat(imageBuild).contains('-t localhost:5100/url-service:abcdef0')
		assertThat(imageBuild).doesNotContain('abcdef01')

	}

	@Test
	void call_shouldNotRunGitInTheDockerContainer_whenTaggingTheImage() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// Regression: `git rev-parse` in this container, which runs as root in a workspace owned by the jenkins user,
		// stops with "detected dubious ownership" (exit 128) and fails every service build at this stage.
		assertThat(shCommands().findAll { it.contains('git rev-parse') }).isEmpty()

	}

	@Test
	void call_shouldFailBeforeBuildingTheImage_whenNoCommitIsKnown() throws Exception {

		// Arrange
		binding.setVariable('env', [:])
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act & Assert
		org.assertj.core.api.Assertions.assertThatThrownBy { script.call(serviceName: 'url-service') }
				.hasMessageContaining('GIT_COMMIT is not set')
		assertThat(shCommands().findAll { it.contains('docker buildx build') }).isEmpty()

	}

	@Test
	void call_shouldRunAllStages_whenServiceNameIsProvided() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def stageNames = helper.callStack.findAll { it.methodName == 'stage' }.collect { it.args[0] }
		assertThat(stageNames).containsExactly('Build & Test', 'Build & Push Image', 'Bump GitOps Tag')

		def shCommands = helper.callStack.findAll { it.methodName == 'sh' }.collect {
			it.args[0] instanceof Map ? it.args[0].script : it.args[0]
		}
		assertThat(shCommands.any { it.contains('./gradlew clean build') })
				.as('Build & Test stage should run ./gradlew clean build')
				.isTrue()
		assertThat(shCommands.any { it.contains('docker buildx build') && it.contains('localhost:5100/url-service') })
				.as('Build & Push Image stage should push to the local registry')
				.isTrue()
		assertThat(shCommands.any { it.contains('services/url-service/values-local.yaml') && it.contains('git push') })
				.as('Bump GitOps Tag stage should update and push url-service\'s values-local.yaml')
				.isTrue()

	}

	@Test
	void call_shouldRunTheGradleBuildWithTheGithubPackagesReadCredential_whenBuildingAndTesting() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// The services resolve the shared libs from GitHub Packages, so the build needs a token to read them.
		assertThat(credentialBoundTo('./gradlew clean build')).isEqualTo('github-packages-read')

	}

	@Test
	void call_shouldExposeTheReadCredentialAsGithubActorAndGithubToken_whenBindingIt() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// The services' build files read these two names, the same ones url-shortener-shared-libs publishes with.
		def bindings = helper.callStack.findAll {
			it.methodName == 'usernamePassword' && it.args[0].credentialsId == 'github-packages-read'
		}.collect { it.args[0] }
		assertThat(bindings).hasSize(2)
		assertThat(bindings.every { it.usernameVariable == 'GITHUB_ACTOR' && it.passwordVariable == 'GITHUB_TOKEN' })
				.isTrue()

	}

	@Test
	void call_shouldPassTheCredentialToTheImageBuildAsBuildKitSecretsByVariableName_whenBuildingTheImage() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def imageBuild = shCommands().find { it.contains('docker buildx build') }
		assertThat(imageBuild).contains('--secret id=github_actor,env=GITHUB_ACTOR')
		assertThat(imageBuild).contains('--secret id=github_token,env=GITHUB_TOKEN')
		assertThat(credentialBoundTo(imageBuild)).isEqualTo('github-packages-read')
		// By name, not by value: a value would put the token on the command line and in the process list.
		assertThat(imageBuild).doesNotContain('$')

	}

	@Test
	void call_shouldNotMountTheAgentsLocalMavenRepository_whenBuildingTheImage() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// The image build used to read the shared libs from the agent's /root/.m2, which the services no longer need.
		def imageBuild = shCommands().find { it.contains('docker buildx build') }
		assertThat(imageBuild).doesNotContain('--build-context')
		assertThat(imageBuild).doesNotContain('.m2')

	}

	@Test
	void call_shouldKeepTheReadCredentialOutOfTheGitOpsStage_whenBumpingTheImageTag() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def gitOpsBump = shCommands().find { it.contains('git push') }
		assertThat(credentialBoundTo(gitOpsBump)).isEqualTo('gitops-repo-push')

	}

	@Test
	void call_shouldNeverUseThePublishCredential_whenBuildingAService() throws Exception {

		// Arrange
		def script = loadScript('vars/buildSpringBootService.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// Building a service only reads the shared libs; the credential that can overwrite them stays with the publisher.
		def usedCredentials = helper.callStack.findAll { it.methodName == 'usernamePassword' }.collect {
			it.args[0].credentialsId
		}
		assertThat(usedCredentials).doesNotContain('github-packages-publish')

	}

	/**
	 * The shell commands that were run, in order.
	 */
	private List<String> shCommands() {
		return helper.callStack.findAll { methodCall ->
			methodCall.methodName == 'sh'
		}.collect { methodCall ->
			methodCall.args[0] instanceof Map ? methodCall.args[0].script : methodCall.args[0]
		}
	}

	/**
	 * The ID of the credential binding the given shell command runs directly inside of, or null when it runs outside
	 * any. Works from the call stack's depths: a command's parent is the nearest earlier call that is shallower than
	 * it, and the binding's credential is the usernamePassword call recorded just before its withCredentials.
	 */
	private String credentialBoundTo(String command) {
		def calls = helper.callStack
		int shell = calls.findIndexOf { call ->
			call.methodName == 'sh' && (call.args[0] instanceof Map ? call.args[0].script : call.args[0]) == command
		}
		assertThat(shell).as("the pipeline ran '${command}'").isGreaterThanOrEqualTo(0)

		int parent = (shell - 1..0).find { calls[it].stackDepth < calls[shell].stackDepth }
		if (calls[parent].methodName != 'withCredentials') {
			return null
		}
		return calls[parent - 1].args[0].credentialsId
	}

}
