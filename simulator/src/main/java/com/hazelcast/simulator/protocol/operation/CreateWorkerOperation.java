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
package com.hazelcast.simulator.protocol.operation;

import com.hazelcast.simulator.agent.workerjvm.WorkerJvmSettings;

import java.util.List;

/**
 * Creates one or more Simulator Workers, based on a list of {@link WorkerJvmSettings}.
 */
public class CreateWorkerOperation implements SimulatorOperation {

    /**
     * Defines a list of {@link WorkerJvmSettings} to create Simulator Workers.
     */
    private final List<WorkerJvmSettings> settingsList;
    private int delayMs;

    public CreateWorkerOperation(List<WorkerJvmSettings> settingsList, int delayMs) {
        this.settingsList = settingsList;
        this.delayMs = delayMs;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public List<WorkerJvmSettings> getWorkerJvmSettings() {
        return settingsList;
    }
}
