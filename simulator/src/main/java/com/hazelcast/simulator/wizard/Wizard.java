/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.wizard;

import com.hazelcast.simulator.common.AgentsFile;
import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.protocol.registry.AgentData;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.CommandLineExitException;
import org.apache.log4j.Logger;
import org.jclouds.providers.ProviderMetadata;
import org.jclouds.providers.Providers;

import java.io.File;
import java.util.Properties;
import java.util.TreeSet;

import static com.hazelcast.simulator.common.GitInfo.getBuildTime;
import static com.hazelcast.simulator.common.GitInfo.getCommitIdAbbrev;
import static com.hazelcast.simulator.common.SimulatorProperties.PROPERTY_CLOUD_CREDENTIAL;
import static com.hazelcast.simulator.common.SimulatorProperties.PROPERTY_CLOUD_IDENTITY;
import static com.hazelcast.simulator.common.SimulatorProperties.PROPERTY_CLOUD_PROVIDER;
import static com.hazelcast.simulator.utils.CloudProviderUtils.PROVIDER_LOCAL;
import static com.hazelcast.simulator.utils.CloudProviderUtils.PROVIDER_STATIC;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isEC2;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isGCE;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isLocal;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isStatic;
import static com.hazelcast.simulator.utils.CommonUtils.exitWithError;
import static com.hazelcast.simulator.utils.CommonUtils.getSimulatorVersion;
import static com.hazelcast.simulator.utils.FileUtils.appendText;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingDirectory;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingFile;
import static com.hazelcast.simulator.utils.FileUtils.fileAsText;
import static com.hazelcast.simulator.utils.FileUtils.writeText;
import static com.hazelcast.simulator.utils.FormatUtils.HORIZONTAL_RULER;
import static com.hazelcast.simulator.utils.FormatUtils.NEW_LINE;
import static com.hazelcast.simulator.utils.NativeUtils.execute;
import static com.hazelcast.simulator.utils.SimulatorUtils.loadComponentRegister;
import static com.hazelcast.simulator.wizard.WizardCli.init;
import static com.hazelcast.simulator.wizard.WizardCli.run;
import static com.hazelcast.simulator.wizard.WizardUtils.containsCommentedOutProperty;
import static com.hazelcast.simulator.wizard.WizardUtils.copyResourceFile;
import static com.hazelcast.simulator.wizard.WizardUtils.getCommentedOutProperty;
import static java.lang.String.format;

public class Wizard {

    static final File SSH_COPY_ID_FILE = new File("ssh-copy-id-script").getAbsoluteFile();
    static final File AGENTS_FILE = new File(AgentsFile.NAME).getAbsoluteFile();

    private static final String GCE_DEFAULT_MACHINE_SPEC = "osFamily=CENTOS,os64Bit=true";
    private static final String GCE_DEFAULT_SSH_OPTIONS = "-o CheckHostIP=no";

    private static final Logger LOGGER = Logger.getLogger(Wizard.class);

    Wizard() {
    }

    void install(String simulatorPath, File profileFile) {
        echoImportant("Hazelcast Simulator Installation");

        echo("Found Simulator in the following directory: %s", simulatorPath);

        echo("Found the following profile file: %s", profileFile.getAbsolutePath());

        String profile = fileAsText(profileFile);
        if (profile.contains("SIMULATOR_HOME")) {
            throw new CommandLineExitException("Hazelcast Simulator seems to be already installed on this system!");
        }

        String config = NEW_LINE + "# Hazelcast Simulator configuration" + NEW_LINE
                + "export SIMULATOR_HOME=" + simulatorPath + NEW_LINE
                + "PATH=$SIMULATOR_HOME/bin:$PATH" + NEW_LINE;
        echo("Will append the following configuration to your profile file:%n%s", config);

        appendText(config, profileFile);
        echo("Done!%n%nNOTE: Don't forget to start a new terminal to make changes effective!");
    }

    void createWorkDir(SimulatorProperties simulatorProperties, String pathName, String cloudProvider) {
        File workDir = new File(pathName).getAbsoluteFile();
        if (workDir.exists()) {
            throw new CommandLineExitException(format("Working directory '%s' already exists!", workDir));
        }

        echo("Will create working directory '%s' for cloud provider '%s'", workDir, cloudProvider);
        ensureExistingDirectory(workDir);

        copyResourceFile(workDir, "run", "runScript");
        copyResourceFile(workDir, "test.properties", "testSuite");

        if (isLocal(cloudProvider)) {
            return;
        }

        ensureExistingFile(workDir, AgentsFile.NAME);

        File simulatorPropertiesFile = ensureExistingFile(workDir, SimulatorProperties.PROPERTIES_FILE_NAME);
        writeText(format("%s=%s%n", PROPERTY_CLOUD_PROVIDER, cloudProvider), simulatorPropertiesFile);
        if (isEC2(cloudProvider)) {
            appendText(format(
                    "%n# These files contain your AWS access key ID and secret access key (change if needed)%n#%s=%s%n#%s=%s%n",
                    PROPERTY_CLOUD_IDENTITY, simulatorProperties.get(PROPERTY_CLOUD_IDENTITY),
                    PROPERTY_CLOUD_CREDENTIAL, simulatorProperties.get(PROPERTY_CLOUD_CREDENTIAL)),
                    simulatorPropertiesFile);
            appendText(format(
                    "%n# Machine specification used for AWS (change if needed)%n#MACHINE_SPEC=%s%n",
                    simulatorProperties.get("MACHINE_SPEC")), simulatorPropertiesFile);
        } else if (isGCE(cloudProvider)) {
            String currentUser = execute("whoami").toString().trim();

            appendText(format(
                    "%n# These files contain your GCE credentials (change if needed)%n%s=%s%n%s=%s%n",
                    PROPERTY_CLOUD_IDENTITY, "~/gce.id",
                    PROPERTY_CLOUD_CREDENTIAL, "~/gce.pem"),
                    simulatorPropertiesFile);
            appendText(format(
                    "%nGROUP_NAME=simulator-agent%nUSER=%s%n",
                    currentUser), simulatorPropertiesFile);
            appendText(format(
                    "%n# Machine specification used for GCE (change if needed)%nMACHINE_SPEC=%s%n",
                    GCE_DEFAULT_MACHINE_SPEC), simulatorPropertiesFile);
            appendText(format(
                    "%n# SSH options used for GCE (change if needed)%nSSH_OPTIONS=%s %s%n",
                    simulatorProperties.getSshOptions(), GCE_DEFAULT_SSH_OPTIONS), simulatorPropertiesFile);
        }

        if (isStatic(cloudProvider)) {
            copyResourceFile(workDir, "prepare", "prepareScriptStatic");
        } else {
            copyResourceFile(workDir, "prepare", "prepareScriptCloud");
        }
        copyResourceFile(workDir, "download", "downloadScript");
    }

    void listCloudProviders() {
        echo("Supported cloud providers:");
        echo(" • %s: Local Setup", PROVIDER_LOCAL);
        echo(" • %s: Static Setup", PROVIDER_STATIC);
        for (ProviderMetadata providerMetadata : Providers.all()) {
            echo(" • %s: %s", providerMetadata.getId(), providerMetadata.getName());
        }
    }

    void createSshCopyIdScript(SimulatorProperties simulatorProperties) {
        ComponentRegistry componentRegistry = loadComponentRegister(AGENTS_FILE, true);
        String userName = simulatorProperties.getUser();

        ensureExistingFile(SSH_COPY_ID_FILE);
        writeText("#!/bin/bash" + NEW_LINE + NEW_LINE, SSH_COPY_ID_FILE);
        for (AgentData agentData : componentRegistry.getAgents()) {
            String publicAddress = agentData.getPublicAddress();
            appendText(format("ssh-copy-id -i ~/.ssh/id_rsa.pub %s@%s%n", userName, publicAddress), SSH_COPY_ID_FILE);
        }
        execute(format("chmod u+x %s", SSH_COPY_ID_FILE.getAbsoluteFile()));

        echo("Please execute './%s' to copy your public RSA key to all remote machines.", SSH_COPY_ID_FILE.getName());
    }

    void sshConnectionCheck(SimulatorProperties simulatorProperties, Bash bash) {
        if (isLocal(simulatorProperties)) {
            throw new CommandLineExitException("SSH is not supported for local setups.");
        }

        ComponentRegistry componentRegistry = loadComponentRegister(AGENTS_FILE, true);
        String userName = simulatorProperties.getUser();

        for (AgentData agentData : componentRegistry.getAgents()) {
            String publicAddress = agentData.getPublicAddress();
            echo("Connecting to %s@%s...", userName, publicAddress);
            bash.ssh(publicAddress, "echo ok 2>&1");
        }

        echo("Connected successfully to all remote machines!");
    }

    void compareSimulatorProperties() {
        SimulatorProperties defaultProperties = new SimulatorProperties();
        String defaultPropertiesString = defaultProperties.getAsString();
        Properties userProperties = WizardUtils.getUserProperties();

        int size = userProperties.size();
        if (size == 0) {
            echo(format("Found no %s file or file was empty!", SimulatorProperties.PROPERTIES_FILE_NAME));
            return;
        }

        echo("Defined user properties:");
        int unknownProperties = 0;
        int changedProperties = 0;
        for (String property : new TreeSet<String>(userProperties.stringPropertyNames())) {
            boolean commentedOutProperty = containsCommentedOutProperty(defaultPropertiesString, property);
            String userValue = userProperties.getProperty(property);
            String defaultValue = (commentedOutProperty ? getCommentedOutProperty(defaultPropertiesString, property)
                    : defaultProperties.get(property));

            if (!defaultProperties.containsKey(property) && !commentedOutProperty) {
                echo("%s = %s [unknown property]", property, userValue);
                unknownProperties++;
            } else if (!userValue.equals(defaultValue)) {
                echo("%s = %s [default: %s]", property, userValue, defaultValue);
                changedProperties++;
            } else {
                echo("%s = %s", property, userValue);
            }
        }

        logResults(size, unknownProperties, changedProperties);
    }

    private void logResults(int size, int unknownProperties, int changedProperties) {
        StringBuilder sb = new StringBuilder();
        sb.append(size).append((size > 1 ? " properties" : " property")).append(" defined");
        if (changedProperties > 0) {
            sb.append(" (").append(changedProperties).append(" changed)");
        }
        if (unknownProperties > 0) {
            sb.append(" (").append(unknownProperties).append(" unknown)");
        }
        echo(sb.toString());
        if (unknownProperties > 0) {
            LOGGER.warn("Unknown properties will be ignored!");
        }
    }

    public static void main(String[] args) {
        try {
            run(args, init());
        } catch (Exception e) {
            exitWithError(LOGGER, "Could not execute command", e);
        }
    }

    static void logHeader() {
        echo("Hazelcast Simulator Wizard");
        echo("Version: %s, Commit: %s, Build Time: %s", getSimulatorVersion(), getCommitIdAbbrev(), getBuildTime());
    }

    private static void echo(String message, Object... args) {
        LOGGER.info(message == null ? "null" : format(message, args));
    }

    private static void echoImportant(String message, Object... args) {
        echo(HORIZONTAL_RULER);
        echo(message, args);
        echo(HORIZONTAL_RULER);
    }
}
