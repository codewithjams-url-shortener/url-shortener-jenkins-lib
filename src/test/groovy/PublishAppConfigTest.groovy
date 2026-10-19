import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import org.junit.Before
import org.junit.Test

import java.util.regex.Pattern

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Exercises vars/publishAppConfig.groovy against JenkinsPipelineUnit's mocked pipeline steps, like its siblings' tests.
 * The shell steps are mocked, so what is verified is the pipeline's decisions: what it validates first, which AppConfig
 * resources it looks up, and when it publishes and deploys versus skips. The AWS and validation commands themselves
 * were run for real, against floci and in the same container images, while writing the step; whether the whole thing
 * works in a live Jenkins is not covered here.
 *
 * Two quirks of JenkinsPipelineUnit shape this file. addShMock treats a String as the literal command and only matches
 * a Pattern as a regex, so the mocks are Patterns (a String regex silently matches nothing and every shell step
 * returns an empty string). And its default `error` only records the call instead of aborting, so it is replaced with
 * one that throws, as Jenkins' does. For a mocked sh the exit value is what returnStatus gives back.
 */
class PublishAppConfigTest extends DeclarativePipelineTest {

	@Before
	void setUp() throws Exception {
		super.setUp()
		helper.registerAllowedMethod('error', [String]) { String message -> throw new RuntimeException(message) }
		givenResourcesExist()
		givenLatestHostedVersion('None')
		givenLastDeployment('None')
		helper.addShMock(Pattern.compile('.*create-hosted-configuration-version.*'), '1', 0)
		helper.addShMock(Pattern.compile('.*start-deployment.*'), '7\tDEPLOYING', 0)
	}

	@Test
	void call_shouldValidateThenPublishThenDeploy_whenNothingHasBeenPublishedYet() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def stageNames = helper.callStack.findAll { it.methodName == 'stage' }.collect { it.args[0] }
		assertThat(stageNames).containsExactly('Validate Schema', 'Publish Hosted Version', 'Start Canary Deployment')
		assertThat(shCommands().any { it.contains('create-hosted-configuration-version') })
				.as('publishes the config as a hosted version').isTrue()
		assertThat(shCommands().any { it.contains('start-deployment') })
				.as('starts a deployment of it').isTrue()

	}

	@Test
	void call_shouldValidateTheSchemaAndTheConfigBeforeTouchingAws_whenRunning() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def commands = shCommands()
		assertThat(commands.any { it.contains('check-jsonschema --check-metaschema schema/config-schema.json') })
				.as('the schema must itself be a valid schema').isTrue()
		assertThat(commands.any {
			it.contains('check-jsonschema --schemafile schema/config-schema.json environments/local/config.json')
		}).as('the config is validated against the schema').isTrue()
		// On floci the profile's own validator is not enforced, so this is the only check the config gets.
		def firstAws = commands.findIndexOf { it.contains('aws appconfig') }
		def lastValidation = commands.findLastIndexOf { it.contains('check-jsonschema') }
		assertThat(lastValidation).isLessThan(firstAws)

	}

	@Test
	void call_shouldPublishAndDeployTheVersionItCreated_whenNothingIsPublishedYet() throws Exception {

		// Arrange
		helper.addShMock(Pattern.compile('.*create-hosted-configuration-version.*'), '4', 0)
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def create = shCommands().find { it.contains('create-hosted-configuration-version') }
		assertThat(create).contains('--application-id app-1').contains('--configuration-profile-id prof-1')
				.contains('--content fileb://environments/local/config.json')
		def start = shCommands().find { it.contains('start-deployment') }
		assertThat(start).contains('--application-id app-1').contains('--environment-id env-1')
				.contains('--configuration-profile-id prof-1').contains('--configuration-version 4')
				.contains('--deployment-strategy-id AppConfig.Canary10Percent20Minutes')

	}

	@Test
	void call_shouldNotRepublishNorRedeploy_whenTheConfigIsIdenticalAndAlreadyDeployed() throws Exception {

		// Arrange
		// Poll SCM builds every commit, including ones that do not touch the config.
		givenLatestHostedVersion('2')
		givenConfigIdenticalToLatest(true)
		givenLastDeployment('2')
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		assertThat(shCommands().findAll { it.contains('create-hosted-configuration-version') || it.contains('start-deployment') })
				.isEmpty()
		assertThat(echoes().any { it.contains('identical to hosted version 2') }).isTrue()
		assertThat(echoes().any { it.contains('already the latest deployment') }).isTrue()

	}

	@Test
	void call_shouldDeployTheExistingVersionWithoutRepublishing_whenItWasPublishedButNeverDeployed() throws Exception {

		// Arrange
		// A deployment that could not start (AppConfig rejects one while another is baking) must not strand the version.
		givenLatestHostedVersion('2')
		givenConfigIdenticalToLatest(true)
		givenLastDeployment('1')
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		assertThat(shCommands().findAll { it.contains('create-hosted-configuration-version') }).isEmpty()
		assertThat(shCommands().find { it.contains('start-deployment') }).contains('--configuration-version 2')

	}

	@Test
	void call_shouldPublishAndDeployANewVersion_whenTheConfigDiffersFromTheLatest() throws Exception {

		// Arrange
		givenLatestHostedVersion('2')
		givenConfigIdenticalToLatest(false)
		givenLastDeployment('2')
		helper.addShMock(Pattern.compile('.*create-hosted-configuration-version.*'), '3', 0)
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		assertThat(shCommands().any { it.contains('create-hosted-configuration-version') }).isTrue()
		assertThat(shCommands().find { it.contains('start-deployment') }).contains('--configuration-version 3')

	}

	@Test
	void call_shouldDeployAnyway_whenTheLastDeploymentCannotBeDetermined() throws Exception {

		// Arrange
		// floci cannot list deployments; the shell reports UNKNOWN for exactly that error and fails on any other.
		givenLatestHostedVersion('2')
		givenConfigIdenticalToLatest(true)
		givenLastDeployment('UNKNOWN')
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		assertThat(shCommands().find { it.contains('start-deployment') }).contains('--configuration-version 2')

	}

	@Test
	void call_shouldListDeploymentsFailingOnEveryErrorExceptFlocisKnownOne_whenAskingWhatWasDeployed() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def listing = shCommands().find { it.contains('list-deployments') }
		assertThat(listing).contains('max_by(Items,&DeploymentNumber).ConfigurationVersion')
				.contains('*NoSuchBucket*) echo UNKNOWN;;')
				.contains('exit 1')

	}

	@Test
	void call_shouldFailWithoutPublishing_whenTheApplicationIsNotFound() throws Exception {

		// Arrange
		helper.addShMock(Pattern.compile('.*list-applications.*'), 'None', 0)
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act & Assert
		assertThatThrownBy { script.call(serviceName: 'url-service') }
				.hasMessageContaining("AppConfig application 'url-service' was not found")
				.hasMessageContaining('Terraform module')
		assertThat(shCommands().findAll { it.contains('create-hosted-configuration-version') || it.contains('start-deployment') })
				.isEmpty()

	}

	@Test
	void call_shouldFail_whenTheEnvironmentIsNotFound() throws Exception {

		// Arrange
		helper.addShMock(Pattern.compile('.*list-environments.*'), 'None', 0)
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act & Assert
		assertThatThrownBy { script.call(serviceName: 'url-service') }
				.hasMessageContaining("AppConfig environment 'local' of 'url-service' was not found")

	}

	@Test
	void call_shouldFail_whenTheConfigurationProfileIsNotFound() throws Exception {

		// Arrange
		helper.addShMock(Pattern.compile('.*list-configuration-profiles.*'), 'None', 0)
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act & Assert
		assertThatThrownBy { script.call(serviceName: 'url-service') }
				.hasMessageContaining("AppConfig configuration profile 'runtime-settings' of 'url-service' was not found")

	}

	@Test
	void call_shouldLookUpByTheNamesAdr0011Specifies_whenResolvingTheResources() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'redirect-service')

		// Assert
		assertThat(shCommands().find { it.contains('list-applications') }).contains("Name=='redirect-service'")
		assertThat(shCommands().find { it.contains('list-environments') }).contains("Name=='local'")
		assertThat(shCommands().find { it.contains('list-configuration-profiles') }).contains("Name=='runtime-settings'")

	}

	@Test
	void call_shouldUseTheNamedEnvironmentsConfig_whenAnotherEnvironmentIsGiven() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service', environment: 'aws')

		// Assert
		assertThat(shCommands().any { it.contains('environments/aws/config.json') }).isTrue()
		assertThat(shCommands().find { it.contains('list-environments') }).contains("Name=='aws'")

	}

	@Test
	void call_shouldBindTheAwsKeysFromTheNamedCredential_whenRunning() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		def credential = helper.callStack.find { it.methodName == 'usernamePassword' }.args[0]
		assertThat(credential.credentialsId).isEqualTo('appconfig-publish-aws')
		assertThat(credential.usernameVariable).isEqualTo('AWS_ACCESS_KEY_ID')
		assertThat(credential.passwordVariable).isEqualTo('AWS_SECRET_ACCESS_KEY')

	}

	@Test
	void call_shouldRunAsThePodsOwnIdentity_whenNoCredentialIsNamed() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service', awsCredentialsId: '')

		// Assert
		assertThat(helper.callStack.findAll { it.methodName == 'withCredentials' }).isEmpty()
		assertThat(shCommands().any { it.contains('start-deployment') }).isTrue()

	}

	@Test
	void call_shouldPointTheAwsCliAtFlociAndTheRegion_whenUsingTheDefaults() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// How the local cluster's pods reach floci - the same endpoint the External Secrets Operator uses.
		assertThat(awsEnvironments()).isNotEmpty()
		assertThat(awsEnvironments().every {
			it.contains('AWS_ENDPOINT_URL=http://host.docker.internal:4566') && it.contains('AWS_DEFAULT_REGION=us-west-2')
		}).isTrue()

	}

	@Test
	void call_shouldLeaveTheEndpointUnset_whenItIsBlank() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service', awsEndpointUrl: '', awsRegion: 'eu-west-1')

		// Assert
		// For the real AWS endpoints: an empty AWS_ENDPOINT_URL would break the CLI rather than mean "default".
		assertThat(awsEnvironments()).isNotEmpty()
		assertThat(awsEnvironments().every { !it.any { entry -> entry.startsWith('AWS_ENDPOINT_URL') } }).isTrue()
		assertThat(awsEnvironments().every { it.contains('AWS_DEFAULT_REGION=eu-west-1') }).isTrue()

	}

	@Test
	void call_shouldRecordTheCommitOnTheVersionAndDeployment_whenTheBuildHasOne() throws Exception {

		// Arrange
		binding.setVariable('env', [GIT_COMMIT: 'abcdef0123456789'])
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		assertThat(shCommands().find { it.contains('create-hosted-configuration-version') }).contains("--description 'commit abcdef0'")
		assertThat(shCommands().find { it.contains('start-deployment') }).contains("--description 'commit abcdef0'")

	}

	@Test
	void call_shouldPollScm_whenDeclaringTriggers() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act
		script.call(serviceName: 'url-service')

		// Assert
		// Poll SCM, not a webhook - see ADR-0008.
		def trigger = helper.callStack.find { it.methodName == 'pollSCM' }
		assertThat(trigger).isNotNull()
		assertThat(trigger.args[0]).isEqualTo('H/5 * * * *')

	}

	@Test
	void call_shouldFail_whenServiceNameIsMissing() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act & Assert
		assertThatThrownBy { script.call([:]) }.hasMessageContaining("'serviceName' is required")

	}

	@Test
	void call_shouldFail_whenServiceNameCouldNotBeAnAppConfigApplicationName() throws Exception {

		// Arrange
		def script = loadScript('vars/publishAppConfig.groovy')

		// Act & Assert
		// It is interpolated into shell and JMESPath text, so anything but a plain service name is refused.
		assertThatThrownBy { script.call(serviceName: "url-service'; rm -rf /") }
				.hasMessageContaining('lowercase letters, digits and hyphens')

	}

	private void givenResourcesExist() {
		helper.addShMock(Pattern.compile('.*list-applications.*'), 'app-1', 0)
		helper.addShMock(Pattern.compile('.*list-environments.*'), 'env-1', 0)
		helper.addShMock(Pattern.compile('.*list-configuration-profiles.*'), 'prof-1', 0)
	}

	private void givenLatestHostedVersion(String version) {
		helper.addShMock(Pattern.compile('.*list-hosted-configuration-versions.*'), version, 0)
	}

	private void givenConfigIdenticalToLatest(boolean identical) {
		helper.addShMock(Pattern.compile('.*get-hosted-configuration-version.*'), '', identical ? 0 : 1)
	}

	private void givenLastDeployment(String result) {
		helper.addShMock(Pattern.compile('.*list-deployments.*'), result, 0)
	}

	private List<String> shCommands() {
		return helper.callStack.findAll { it.methodName == 'sh' }.collect {
			(it.args[0] instanceof Map ? it.args[0].script : it.args[0]).toString()
		}
	}

	private List<String> echoes() {
		return helper.callStack.findAll { it.methodName == 'echo' }.collect { it.args[0].toString() }
	}

	private List<List<String>> awsEnvironments() {
		return helper.callStack.findAll { it.methodName == 'withEnv' }.collect { it.args[0].collect { entry -> entry.toString() } }
	}

}
