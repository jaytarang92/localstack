package com.atlassian.localstack;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import com.amazonaws.util.IOUtils;

/**
 * Simple JUnit test runner that automatically downloads, installs, starts,
 * and stops the LocalStack local cloud infrastructure components.
 *
 * Should work cross-OS, however has been only tested under Unix (Linux/MacOS).
 *
 * @author Waldemar Hummer
 */
public class LocalstackTestRunner extends BlockJUnit4ClassRunner {

	private static final AtomicReference<Process> INFRA_STARTED = new AtomicReference<Process>();
	private static String CONFIG_FILE_CONTENT = "";

	private static final String INFRA_READY_MARKER = "Ready.";
	private static final String TMP_INSTALL_DIR = System.getProperty("java.io.tmpdir") +
			File.separator + "localstack_install_dir";
	private static final String ADDITIONAL_PATH = "/usr/local/bin/";
	private static final String LOCALHOST = "localhost";
	private static final String LOCALSTACK_REPO_URL = "https://github.com/atlassian/localstack";

	private static final Logger LOG = Logger.getLogger(LocalstackTestRunner.class.getName());

	public LocalstackTestRunner(Class<?> klass) throws InitializationError {
		super(klass);
	}

	/* SERVICE ENDPOINTS */

	public static String getEndpointS3() {
		return ensureInstallationAndGetEndpoint("s3");
	}

	public static String getEndpointKinesis() {
		return ensureInstallationAndGetEndpoint("kinesis");
	}

	public static String getEndpointLambda() {
		return ensureInstallationAndGetEndpoint("lambda");
	}

	public static String getEndpointDynamoDB() {
		return ensureInstallationAndGetEndpoint("dynamodb");
	}

	public static String getEndpointDynamoDBStreams() {
		return ensureInstallationAndGetEndpoint("dynamodbstreams");
	}

	public static String getEndpointAPIGateway() {
		return ensureInstallationAndGetEndpoint("apigateway");
	}

	public static String getEndpointElasticsearch() {
		return ensureInstallationAndGetEndpoint("elasticsearch");
	}

	public static String getEndpointFirehose() {
		return ensureInstallationAndGetEndpoint("firehose");
	}

	public static String getEndpointSNS() {
		return ensureInstallationAndGetEndpoint("sns");
	}

	public static String getEndpointSQS() {
		return ensureInstallationAndGetEndpoint("sqs");
	}

	public static String getEndpointRedshift() {
		return ensureInstallationAndGetEndpoint("redshift");
	}

	/* UTILITY METHODS */

	@Override
	public void run(RunNotifier notifier) {
		setupInfrastructure();
		super.run(notifier);
	}

	private static void ensureInstallation() {
		File dir = new File(TMP_INSTALL_DIR);
		if(!dir.exists()) {
			LOG.info("Installing LocalStack to temporary directory (this might take a while): " + TMP_INSTALL_DIR);
			exec("git clone " + LOCALSTACK_REPO_URL + " " + TMP_INSTALL_DIR);
			exec("cd " + TMP_INSTALL_DIR + "; make install");
		}
	}

	private static void killProcess(Process p) {
		p.destroy();
		p.destroyForcibly();
	}

	private static String ensureInstallationAndGetEndpoint(String service) {
		ensureInstallation();
		return getEndpoint(service);
	}

	private static String getEndpoint(String service) {
		String regex = ".*DEFAULT_PORT_" + service.toUpperCase() + "\\s*=\\s*([0-9]+).*";
		String port = Pattern.compile(regex, Pattern.DOTALL | Pattern.MULTILINE).matcher(CONFIG_FILE_CONTENT).replaceAll("$1");
		return "http://" + LOCALHOST + ":" + port + "/";
	}

	private static Process exec(String cmd) {
		return exec(cmd, true);
	}

	private static Process exec(String cmd, boolean wait) {
		try {
			Map<String, String> env = System.getenv();
			final Process p = Runtime.getRuntime().exec(new String[]{"/bin/bash", "-c", cmd},
					new String[]{"PATH=" + ADDITIONAL_PATH + ":" + env.get("PATH")});
			if (wait) {
				int code = p.waitFor();
				if(code != 0) {
					String stderr = IOUtils.toString(p.getErrorStream());
					String stdout = IOUtils.toString(p.getInputStream());
					throw new IllegalStateException("Failed to run command '" + cmd + "', return code " + code +
							".\nSTDOUT: " + stdout + "\nSTDERR: " + stderr);
				}
			} else {
				/* make sure we destroy the process on JVM shutdown */
				Runtime.getRuntime().addShutdownHook(new Thread() {
					public void run() {
						killProcess(p);
					}
				});
			}
			return p;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void setupInfrastructure() {
		synchronized (INFRA_STARTED) {
			ensureInstallation();
			if(INFRA_STARTED.get() != null) return;
			String cmd = "cd " + TMP_INSTALL_DIR + "; exec make infra";
			Process proc;
			try {
				proc = exec(cmd, false);
				BufferedReader r1 = new BufferedReader(new InputStreamReader(proc.getInputStream()));
				String line;
				LOG.info("Waiting for infrastructure to be spun up");
				while((line = r1.readLine()) != null) {
					if(INFRA_READY_MARKER.equals(line)) {
						break;
					}
				}
				/* read contents of LocalStack config file */
				String configFile = TMP_INSTALL_DIR + File.separator + "localstack" +  File.separator + "constants.py";
				CONFIG_FILE_CONTENT = IOUtils.toString(new FileInputStream(configFile));
				INFRA_STARTED.set(proc);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static void teardownInfrastructure() {
		Process proc = INFRA_STARTED.get();
		if(proc == null) {
			return;
		}
		killProcess(proc);
	}
}
