/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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
package com.hazelcast.stabilizer.trainee;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Cluster;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.Member;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.coach.Coach;
import com.hazelcast.stabilizer.JavaInstallation;
import com.hazelcast.stabilizer.Utils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.stabilizer.Utils.getHostAddress;
import static com.hazelcast.stabilizer.Utils.getStablizerHome;
import static java.lang.String.format;

public class TraineeVmManager {

    private final static ILogger log = Logger.getLogger(TraineeVmManager.class);
    private final static File USER_DIR = new File(System.getProperty("user.dir"));
    private final static String CLASSPATH = System.getProperty("java.class.path");
    private final static File STABILIZER_HOME = getStablizerHome();
    private final static String CLASSPATH_SEPARATOR = System.getProperty("path.separator");
    private final static AtomicLong TRAINEE_ID_GENERATOR = new AtomicLong();

    private final List<TraineeVm> traineeJvms = new CopyOnWriteArrayList<TraineeVm>();
    private final Coach coach;
    private volatile HazelcastInstance traineeClient;
    private volatile IExecutorService traineeExecutor;
    private final AtomicBoolean javaHomePrinted = new AtomicBoolean();

    public TraineeVmManager(Coach coach) {
        this.coach = coach;

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                for (TraineeVm jvm : traineeJvms) {
                    log.info("Destroying trainee : " + jvm.getId());
                    jvm.getProcess().destroy();
                }
            }
        });
    }

    public IExecutorService getTraineeExecutor() {
        return traineeExecutor;
    }

    public HazelcastInstance getTraineeClient() {
        return traineeClient;
    }

    public List<TraineeVm> getTraineeJvms() {
        return traineeJvms;
    }

    public void spawn(TraineeVmSettings settings) throws Exception {
        log.info(format("Starting %s trainee Java Virtual Machines using settings\n %s", settings.getTraineeCount(), settings));

        File traineeHzFile = createHazelcastConfigFile(settings);

        List<TraineeVm> trainees = new LinkedList<TraineeVm>();

        for (int k = 0; k < settings.getTraineeCount(); k++) {
            TraineeVm trainee = startTraineeJvm(settings, traineeHzFile);
            Process process = trainee.getProcess();
            String traineeId = trainee.getId();

            trainees.add(trainee);

            new TraineeVmLogger(traineeId, process.getInputStream(), settings.isTrackLogging()).start();
        }
        Config config = new XmlConfigBuilder(traineeHzFile.getAbsolutePath()).build();
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.getGroupConfig()
                .setName(config.getGroupConfig().getName())
                .setPassword(config.getGroupConfig().getPassword());
        clientConfig.getNetworkConfig().addAddress("localhost:" + config.getNetworkConfig().getPort());

        traineeClient = HazelcastClient.newHazelcastClient(clientConfig);
        traineeExecutor = traineeClient.getExecutorService(Trainee.TRAINEE_EXECUTOR);

        waitForTraineesStartup(trainees, settings.getTraineeStartupTimeout());

        log.info(format("Finished starting %s trainee Java Virtual Machines", settings.getTraineeCount()));
    }

    private File createHazelcastConfigFile(TraineeVmSettings settings) throws IOException {
        File traineeHzFile = File.createTempFile("trainee-hazelcast", "xml");
        traineeHzFile.deleteOnExit();
        final String hzConfig = settings.getHzConfig();

        StringBuffer members = new StringBuffer();
        HazelcastInstance coachHazelcastInstance = coach.getCoachHazelcastInstance();
        Cluster cluster = coachHazelcastInstance.getCluster();
        for (Member member : cluster.getMembers()) {
            String hostAddress = member.getSocketAddress().getAddress().getHostAddress();
            members.append("<member>").append(hostAddress).append(":5701").append("</member>\n");
        }

        String enhancedHzConfig = hzConfig.replace("<!--MEMBERS-->", members);
        Utils.writeText(enhancedHzConfig, traineeHzFile);
        return traineeHzFile;
    }

    private String getJavaHome(String javaVendor, String javaVersion) {
        JavaInstallation installation = coach.getJavaInstallationRepository().get(javaVendor, javaVersion);
        if (installation != null) {
            //todo: we should send a signal
            return installation.getJavaHome();
        }

        //nothing is found so we are going to make use of a default.
        String javaHome = System.getProperty("java.home");
        if (javaHomePrinted.compareAndSet(false, true)) {
            log.info("java.home=" + javaHome);
        }

        return javaHome;
    }

    private TraineeVm startTraineeJvm(TraineeVmSettings settings, File traineeHzFile) throws IOException {
        String traineeId = "trainee-"+getHostAddress()+"-"+TRAINEE_ID_GENERATOR.incrementAndGet();

        String traineeVmOptions = settings.getVmOptions();
        String[] clientVmOptionsArray = new String[]{};
        if (traineeVmOptions != null && !traineeVmOptions.trim().isEmpty()) {
            clientVmOptionsArray = traineeVmOptions.split("\\s+");
        }

        File workoutHome = coach.getWorkoutHome();
        String javaHome = getJavaHome(settings.getJavaVendor(), settings.getJavaVersion());

        List<String> args = new LinkedList<String>();
        args.add("java");
        args.add(format("-XX:OnOutOfMemoryError=\"\"touch %s.oome\"\"", traineeId));
        args.add("-DSTABILIZER_HOME=" + getStablizerHome());
        args.add("-Dhazelcast.logging.type=log4j");
        args.add("-DtraineeId=" + traineeId);
        args.add("-Dlog4j.configuration=file:" + STABILIZER_HOME + File.separator + "conf" + File.separator + "trainee-log4j.xml");
        args.add("-classpath");

        File libDir = new File(coach.getWorkoutHome(), "lib");
        String s = CLASSPATH + CLASSPATH_SEPARATOR + new File(libDir, "*").getAbsolutePath();
        args.add(s);

        args.addAll(Arrays.asList(clientVmOptionsArray));
        args.add(Trainee.class.getName());
        args.add(traineeId);
        args.add(traineeHzFile.getAbsolutePath());

        ProcessBuilder processBuilder = new ProcessBuilder(args.toArray(new String[args.size()]))
                .directory(workoutHome)
                .redirectErrorStream(true);

        Map<String, String> environment = processBuilder.environment();
        String path = javaHome + File.pathSeparator + "bin:" + environment.get("PATH");
        environment.put("PATH", path);
        environment.put("JAVA_HOME", javaHome);

        Process process = processBuilder.start();
        final TraineeVm traineeJvm = new TraineeVm(traineeId, process);
        traineeJvms.add(traineeJvm);
        return traineeJvm;
    }

    private void waitForTraineesStartup(List<TraineeVm> trainees, int traineeTimeoutSec) throws InterruptedException {
        List<TraineeVm> todo = new ArrayList<TraineeVm>(trainees);

        for (int l = 0; l < traineeTimeoutSec; l++) {
            for (Iterator<TraineeVm> it = todo.iterator(); it.hasNext(); ) {
                TraineeVm jvm = it.next();

                InetSocketAddress address = readAddress(jvm);

                if (address != null) {
                    Member member = null;
                    for (Member m : traineeClient.getCluster().getMembers()) {
                        if (m.getInetSocketAddress().equals(address)) {
                            member = m;
                            break;
                        }
                    }

                    if (member != null) {
                        it.remove();
                        jvm.setMember(member);
                        log.info(format("Trainee: %s Started %s of %s",
                                jvm.getId(), trainees.size() - todo.size(), trainees.size()));
                    }
                }
            }

            if (todo.isEmpty())
                return;

            Utils.sleepSeconds(1);
        }

        StringBuffer sb = new StringBuffer();
        sb.append("[");
        sb.append(todo.get(0).getId());
        for (int l = 1; l < todo.size(); l++) {
            sb.append(",").append(todo.get(l).getId());
        }
        sb.append("]");

        throw new RuntimeException(format("Timeout: trainees %s of workout %s on host %s didn't start within %s seconds",
                sb, coach.getWorkout().getId(), coach.getCoachHz().getCluster().getLocalMember().getInetSocketAddress(),
                traineeTimeoutSec));
    }

    private InetSocketAddress readAddress(TraineeVm jvm) {
        File workoutHome = coach.getWorkoutHome();

        File file = new File(workoutHome, jvm.getId() + ".address");
        if (!file.exists()) {
            return null;
        }

        return (InetSocketAddress) Utils.readObject(file);
    }

    public void destroyAll() {
        if (traineeClient != null) {
            traineeClient.getLifecycleService().shutdown();
        }

        List<TraineeVm> trainees = new LinkedList<TraineeVm>(traineeJvms);
        traineeJvms.clear();

        for (TraineeVm jvm : trainees) {
            jvm.getProcess().destroy();
        }

        for (TraineeVm jvm : trainees) {
            int exitCode = 0;
            try {
                exitCode = jvm.getProcess().waitFor();
            } catch (InterruptedException e) {
            }

            if (exitCode != 0) {
                log.info(format("trainee process %s exited with exit code: %s", jvm.getId(), exitCode));
            }
        }
    }

      public void destroy(TraineeVm jvm) {
        jvm.getProcess().destroy();
        try {
            jvm.getProcess().waitFor();
        } catch (InterruptedException e) {
        }
        traineeJvms.remove(jvm);
    }

    public TraineeVm getTrainee(String traineeId) {
        for (TraineeVm trainee : traineeJvms) {
            if (traineeId.equals(trainee.getId())) {
                return trainee;
            }
        }
        return null;
    }
}
