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

}
