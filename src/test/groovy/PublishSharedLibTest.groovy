import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import org.junit.Before
import org.junit.Test

import java.util.regex.Pattern

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Exercises vars/publishSharedLib.groovy against JenkinsPipelineUnit's mocked pipeline steps, like
 * BuildSpringBootServiceTest does for its sibling. The shell steps are mocked, so what is verified is the pipeline's
 * decisions: which modules it publishes given what the registry says, and that it never treats a registry error as
 * "not published". Whether the real commands work against a live Jenkins and GitHub Packages is not covered here.
 *
 * Two quirks of JenkinsPipelineUnit shape this file. addShMock treats a String as the literal command and only matches
 * a Pattern as a regex, so the mocks below are Patterns (a String regex silently matches nothing and every shell step
 * returns an empty string). And its default `error` only records the call rather than aborting, so it is replaced with
 * one that throws, as Jenkins' does.
 */
class PublishSharedLibTest extends DeclarativePipelineTest {

	private static final String VERSION = '0.1.0'

	private static final String REGISTRY =
			'https://maven.pkg.github.com/codewithjams-url-shortener/url-shortener-shared-libs'

	@Before
	void setUp() throws Exception {
		super.setUp()
		helper.registerAllowedMethod('error', [String]) { String message -> throw new RuntimeException(message) }
		givenVersion(VERSION)
	}

	@Test
	void call_shouldFollowTheRegistrysRedirect_whenCheckingWhetherEachModuleIsPublished() throws Exception {

		// Arrange
		givenRegistryAnswers('200', '200', '200')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		// Regression: GitHub Packages answers an existing module with a 302 redirect to a signed download URL and only a
		// followed redirect ends in the 200 this step accepts, so a check that does not follow it fails every build of an
		// already published version with "unexpected HTTP 302".
		def checks = shCommands().findAll { command -> command.contains('curl') }
		assertThat(checks).hasSize(3)
		assertThat(checks.every { command -> command =~ /curl\s+-\w*L/ })
				.as('every registry check follows redirects')
				.isTrue()

	}

	@Test
	void call_shouldBuildTestThenPublish_whenNoModuleIsPublishedYet() throws Exception {

		// Arrange
		givenRegistryAnswers('404', '404', '404')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		def stageNames = helper.callStack.findAll {
			methodCall -> methodCall.methodName == 'stage'
		}.collect { methodCall ->
			methodCall.args[0]
		}
		assertThat(stageNames).containsExactly('Build & Test', 'Publish')
		assertThat(shCommands()).contains('./gradlew clean build')
		assertThat(shCommands())
				.as('every module is published, in one Gradle invocation')
				.contains('./gradlew :links-contract:publish :event-contracts:publish :service-common:publish')

	}

	@Test
	void call_shouldSkipThePublish_whenEveryModuleIsAlreadyPublished() throws Exception {

		// Arrange
		givenRegistryAnswers('200', '200', '200')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		// A build of an already-released version must be a harmless no-op: a republish would fail with a conflict.
		assertThat(
				shCommands().findAll { command ->
					command.contains(':publish')
				}
		).isEmpty()
		assertThat(
				echoes().any { echo ->
					echo.contains('already published') && echo.contains(VERSION)
				}
		).as('says why nothing was published')
				.isTrue()

	}

	@Test
	void call_shouldPublishOnlyTheMissingModules_whenSomeAreAlreadyPublished() throws Exception {

		// Arrange
		// A previous publish stopped partway: the next build completes it rather than failing on the existing ones.
		givenRegistryAnswers('200', '200', '404')
		def  script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		assertThat(shCommands()).contains('./gradlew :service-common:publish')
		assertThat(
				shCommands().findAll { command ->
					command.contains(':links-contract:publish') || command.contains(':event-contracts:publish')
				}
		).isEmpty()

	}

	@Test
	void call_shouldFailWithoutPublishing_whenTheRegistryAnswersUnexpectedly() throws Exception {

		// Arrange
		givenRegistryAnswers('401', '404', '404')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act & Assert
		// Bad credentials are not "not published": publishing on that basis would hide the real problem.
		assertThatThrownBy {
			script.call()
		}.hasMessageContaining('unexpected HTTP 401')
				.hasMessageContaining('github-packages-publish')
		assertThat(
				shCommands().findAll { command ->
					command.contains(':publish')
				}
		).isEmpty()

	}

	@Test
	void call_shouldFail_whenTheVersionCannotBeDetermined() throws Exception {

		// Arrange
		givenVersion('')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act & Assert
		assertThatThrownBy {
			script.call()
		}.hasMessageContaining('could not determine the version')
		assertThat(
				shCommands().findAll { command ->
					command.contains('curl') || command.contains(':publish')
				}
		).isEmpty()

	}

	@Test
	void call_shouldCheckTheExactPomOfEachModuleWithoutInterpolatingSecrets_whenCheckingTheRegistry() throws Exception {

		// Arrange
		givenRegistryAnswers('200', '200', '200')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		def checks = shCommands().findAll { command ->
			command.contains('curl')
		}
		assertThat(checks).hasSize(3)
		assertThat(
				checks.any {
					it.contains(
							"${REGISTRY}/io/url-shortener/links-contract/${VERSION}/links-contract-${VERSION}.pom"
									.toString()
					)
				}
		).isTrue()
		assertThat(
				checks.any {
					it.contains(
							"${REGISTRY}/io/url-shortener/event-contracts/${VERSION}/event-contracts-${VERSION}.pom"
									.toString()
					)
				}
		).isTrue()
		assertThat(
				checks.any {
					it.contains(
							"${REGISTRY}/io/url-shortener/service-common/${VERSION}/service-common-${VERSION}.pom"
									.toString()
					)
				}
		).isTrue()
		// The credentials are left for the shell to expand from the environment, not written into the command text.
		assertThat(checks.every { it.contains('$GITHUB_ACTOR:$GITHUB_TOKEN') })
				.as('credentials stay as shell variables in every registry check')
				.isTrue()

	}

	@Test
	void call_shouldUseTheGitHubPackagesCredential_whenPublishing() throws Exception {

		// Arrange
		givenRegistryAnswers('404', '404', '404')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		// The repo's own build file reads these two names from the environment.
		def credential = helper.callStack.find { it.methodName == 'usernamePassword' }.args[0]
		assertThat(credential.credentialsId).isEqualTo('github-packages-publish')
		assertThat(credential.usernameVariable).isEqualTo('GITHUB_ACTOR')
		assertThat(credential.passwordVariable).isEqualTo('GITHUB_TOKEN')

	}

	@Test
	void call_shouldPollScm_whenDeclaringTriggers() throws Exception {

		// Arrange
		givenRegistryAnswers('200', '200', '200')
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call()

		// Assert
		// Poll SCM, not a webhook - see ADR-0008.
		def trigger = helper.callStack.find { it.methodName == 'pollSCM' }
		assertThat(trigger).isNotNull()
		assertThat(trigger.args[0]).isEqualTo('H/5 * * * *')

	}

	@Test
	void call_shouldUseTheGivenRepositoryAndModules_whenConfigured() throws Exception {

		// Arrange
		helper.addShMock(Pattern.compile('.*curl.*/only-module/.*'), '404', 0)
		def script = loadScript('vars/publishSharedLib.groovy')

		// Act
		script.call(repository: 'some-org/some-repo', modules: ['only-module'])

		// Assert
		def checks = shCommands().findAll { it.contains('curl') }
		assertThat(checks).hasSize(1)
		assertThat(checks[0].toString())
				.contains('https://maven.pkg.github.com/some-org/some-repo/io/url-shortener/only-module/')
		assertThat(shCommands()).contains('./gradlew :only-module:publish')

	}

	private void givenVersion(String version) {
		helper.addShMock(Pattern.compile('.*properties --property version.*'), version, 0)
	}

	private void givenRegistryAnswers(String linksContract, String eventContracts, String serviceCommon) {
		helper.addShMock(Pattern.compile('.*curl.*/links-contract/.*'), linksContract, 0)
		helper.addShMock(Pattern.compile('.*curl.*/event-contracts/.*'), eventContracts, 0)
		helper.addShMock(Pattern.compile('.*curl.*/service-common/.*'), serviceCommon, 0)
	}

	private List<String> shCommands() {
		return helper.callStack.findAll { methodCall ->
			methodCall.methodName == 'sh'
		}.collect { methodCall ->
			methodCall.args[0] instanceof Map ? methodCall.args[0].script : methodCall.args[0]
		}
	}

	private List<String> echoes() {
		return helper.callStack.findAll { methodCall ->
			methodCall.methodName == 'echo'
		}.collect { methodCall ->
			methodCall.args[0].toString()
		}
	}

}
