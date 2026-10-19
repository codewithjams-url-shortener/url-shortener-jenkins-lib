/**
 * Shared pipeline for a service's config repo (url-service-config, redirect-service-config,
 * analytics-service-config): validate the config against its JSON Schema, publish it as a new AWS AppConfig hosted
 * configuration version, and start a canary deployment of it. See ADR-0011 and ADR-0008.
 *
 * A consuming repo's Jenkinsfile is expected to be just:
 *
 *     publishAppConfig(serviceName: 'url-service')
 *
 * The repo is expected to hold environments/<environment>/config.json and schema/config-schema.json. The AppConfig
 * resources are found by name, following ADR-0011: an Application named after the service, an Environment named
 * after the target environment, and a "runtime-settings" Configuration Profile, deployed with the predefined
 * AppConfig.Canary10Percent20Minutes strategy. They are created by the appconfig Terraform module, not by this step.
 *
 * The step starts the deployment and finishes. It does not wait out the canary: rolling back on an error spike is
 * AppConfig's own job, via the CloudWatch alarm tied to the deployment, so the pipeline has nothing to do during the
 * bake and does not hold an agent pod for it.
 *
 * Every commit is polled, including ones that do not touch the config, so the step avoids needless rollouts. It
 * does not republish a config identical to the latest hosted version, and it does not redeploy a version that is
 * already the latest deployment - which also means a version AppConfig rolled back is not retried until the config
 * changes. Publishing and deploying are decided separately, so a version published but never deployed (for example
 * because a deployment was still baking, which AppConfig rejects) is deployed by the next build instead of being
 * stranded.
 *
 * Needs a Jenkins "username with password" credential (an AWS access key id and secret) with the id below.
 *
 * @param config.serviceName       Required. The service, e.g. "url-service": the AppConfig Application's name.
 * @param config.environment       Optional. The AppConfig Environment and the environments/ directory to publish.
 *                                  Defaults to "local", the only environment the local Jenkins can reach.
 * @param config.awsEndpointUrl    Optional. The AWS API endpoint. Defaults to floci as the local cluster's pods
 *                                  reach it. Pass "" for the real AWS endpoints.
 * @param config.awsRegion         Optional. Defaults to us-west-2.
 * @param config.awsCredentialsId  Optional. The Jenkins credential holding the AWS keys. Defaults to
 *                                  "appconfig-publish-aws". Pass "" to use the pod's own AWS identity.
 */
def call(Map config) {

	if (!config.serviceName) {
		error "publishAppConfig: 'serviceName' is required"
	}
	if (!(config.serviceName ==~ /[a-z0-9][a-z0-9-]*/)) {
		error "publishAppConfig: 'serviceName' must be lowercase letters, digits and hyphens, was '${config.serviceName}'"
	}

	final String serviceName = config.serviceName
	final String environment = config.environment ?: 'local'
	final String awsEndpointUrl = config.containsKey('awsEndpointUrl') ? config.awsEndpointUrl : 'http://host.docker.internal:4566'
	final String awsRegion = config.awsRegion ?: 'us-west-2'
	final String awsCredentialsId = config.containsKey('awsCredentialsId') ? config.awsCredentialsId : 'appconfig-publish-aws'
	final String profileName = 'runtime-settings'
	final String deploymentStrategyId = 'AppConfig.Canary10Percent20Minutes'
	final String configPath = "environments/${environment}/config.json"
	final String schemaPath = 'schema/config-schema.json'

	// What the publish stage found, for the deployment stage. A plain map in this scope rather than env.* variables, so it
	// does not depend on how environment assignments propagate between stages.
	final Map<String, String> state = [:]

	final List<String> awsEnv = ["AWS_DEFAULT_REGION=${awsRegion}".toString()]
	if (awsEndpointUrl) {
		awsEnv << "AWS_ENDPOINT_URL=${awsEndpointUrl}".toString()
	}

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

			stage('Validate Schema') {
				steps {
					container('validator') {
						sh 'pip install --quiet --disable-pip-version-check check-jsonschema==0.38.2'
						// The schema itself must be a valid schema before it can be trusted to validate anything.
						sh "check-jsonschema --check-metaschema ${schemaPath}"
						sh "check-jsonschema --schemafile ${schemaPath} ${configPath}"
					}
				}
			}

			stage('Publish Hosted Version') {
				steps {
					container('aws') {
						withEnv(awsEnv) {
							withAwsCredentials(awsCredentialsId) {
								script {
									publishHostedVersion(state, serviceName, environment, profileName, configPath)
								}
							}
						}
					}
				}
			}

			stage('Start Canary Deployment') {
				steps {
					container('aws') {
						withEnv(awsEnv) {
							withAwsCredentials(awsCredentialsId) {
								script {
									startCanaryDeployment(state, deploymentStrategyId)
								}
							}
						}
					}
				}
			}

		}

	}

}

/**
 * Finds the AppConfig resources by name, then publishes the config as a new hosted version unless the latest one is
 * already identical. Leaves the ids and the version to ship in the state for the deployment stage.
 */
private void publishHostedVersion(Map<String, String> state, String serviceName, String environment, String profileName,
								  String configPath) {
	final String appId = lookup(
			"aws appconfig list-applications --query \"Items[?Name=='${serviceName}'].Id | [0]\" --output text",
			"AppConfig application '${serviceName}'"
	)
	final String envId = lookup(
			"aws appconfig list-environments --application-id ${appId} " +
					"--query \"Items[?Name=='${environment}'].Id | [0]\" --output text",
			"AppConfig environment '${environment}' of '${serviceName}'"
	)
	final String profileId = lookup(
			"aws appconfig list-configuration-profiles --application-id ${appId} " +
					"--query \"Items[?Name=='${profileName}'].Id | [0]\" --output text",
			"AppConfig configuration profile '${profileName}' of '${serviceName}'"
	)
	state.appId = appId
	state.envId = envId
	state.profileId = profileId

	final String latest = sh(
			script: "aws appconfig list-hosted-configuration-versions --application-id ${appId} " +
					"--configuration-profile-id ${profileId} --query 'max_by(Items,&VersionNumber).VersionNumber' --output text",
			returnStdout: true
	).trim()

	if (latest != 'None' && isIdenticalToVersion(appId, profileId, latest, configPath)) {
		echo "publishAppConfig: ${configPath} is identical to hosted version ${latest}, not republishing"
		state.version = latest
		return
	}

	final String commit = env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'unknown'
	// The response body (the content echoed back) is of no use, hence /dev/null for the CLI's required output file.
	state.version = sh(
			script: "aws appconfig create-hosted-configuration-version --application-id ${appId} " +
					"--configuration-profile-id ${profileId} --content-type application/json " +
					"--description 'commit ${commit}' --content fileb://${configPath} /dev/null " +
					"--query VersionNumber --output text",
			returnStdout: true
	).trim()
	echo "publishAppConfig: published ${configPath} as hosted version ${state.version}"
}

/**
 * Starts the canary deployment of the version the previous stage settled on, unless that version is already the
 * latest deployment to the environment.
 */
private void startCanaryDeployment(Map<String, String> state, String deploymentStrategyId) {
	final String lastDeployed = lastDeployedVersion(state.appId, state.envId)
	if (lastDeployed == state.version) {
		echo "publishAppConfig: hosted version ${state.version} is already the latest deployment, nothing to do. " +
				"Change the config to roll out a new version."
		return
	}
	final String commit = env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'unknown'
	final String started = sh(
			script: "aws appconfig start-deployment --application-id ${state.appId} " +
					"--environment-id ${state.envId} --configuration-profile-id ${state.profileId} " +
					"--configuration-version ${state.version} --deployment-strategy-id ${deploymentStrategyId} " +
					"--description 'commit ${commit}' --query '[DeploymentNumber,State]' --output text",
			returnStdout: true
	).trim()
	echo "publishAppConfig: started deployment (number, state) ${started} of hosted version ${state.version}"
}

/**
 * Runs an AWS CLI lookup that prints a single id, or "None" when nothing has that name, and fails clearly in that case.
 */
private String lookup(String command, String what) {
	final String id = sh(script: command, returnStdout: true).trim()
	if (!id || id == 'None') {
		error "publishAppConfig: ${what} was not found. Has the appconfig Terraform module been applied to this " +
				"environment, and does it use the names ADR-0011 specifies?"
	}
	return id
}

/**
 * Whether the config file is byte-for-byte what the given hosted version holds (ignoring trailing newlines). Compared
 * in the shell, since the AWS CLI image carries neither cmp nor diff.
 */
private boolean isIdenticalToVersion(String appId, String profileId, String version, String configPath) {
	final int status = sh(
			script: "aws appconfig get-hosted-configuration-version --application-id ${appId} " +
					"--configuration-profile-id ${profileId} --version-number ${version} /tmp/latest.json > /dev/null " +
					"&& [ \"\$(cat /tmp/latest.json)\" = \"\$(cat ${configPath})\" ]",
			returnStatus: true
	)
	return status == 0
}

/**
 * The configuration version of the newest deployment to the environment, or "None" if there has been none.
 *
 * floci answers ListDeployments with a bogus S3 "NoSuchBucket" 404, so that one error means "cannot tell" and is
 * reported as unknown, which makes the caller deploy. Any other error is a real failure and fails the build, rather
 * than being mistaken for "nothing deployed".
 */
private String lastDeployedVersion(String appId, String envId) {
	return sh(
			script: "OUT=\$(aws appconfig list-deployments --application-id ${appId} --environment-id ${envId} " +
					"--query 'max_by(Items,&DeploymentNumber).ConfigurationVersion' --output text 2>&1) " +
					"&& echo \"\$OUT\" || { case \"\$OUT\" in *NoSuchBucket*) echo UNKNOWN;; " +
					"*) echo \"\$OUT\" >&2; exit 1;; esac; }",
			returnStdout: true
	).trim()
}

/**
 * Binds the AWS access key and secret from the named Jenkins credential, or runs the body as the pod's own identity
 * when no credential is named.
 */
private void withAwsCredentials(String credentialsId, body) {
	if (!credentialsId) {
		body()
		return
	}
	withCredentials([usernamePassword(
			credentialsId: credentialsId,
			usernameVariable: 'AWS_ACCESS_KEY_ID',
			passwordVariable: 'AWS_SECRET_ACCESS_KEY'
	)]) {
		body()
	}
}

/**
 * Pod template for the ephemeral Kubernetes build agent (see ADR-0008): one container per toolchain, so each
 * {@code container(...)} step runs in the right one. Only the AWS CLI and a JSON Schema validator are needed.
 */
private String podTemplateYaml() {
	return """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: validator
      image: python:3.12-slim
      command: ['sleep']
      args: ['infinity']
    - name: aws
      image: amazon/aws-cli:2.38.1
      command: ['sleep']
      args: ['infinity']
"""
}
