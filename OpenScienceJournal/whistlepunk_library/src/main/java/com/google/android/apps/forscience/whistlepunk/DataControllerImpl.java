/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk;

import android.support.v4.util.ArrayMap;

import com.google.android.apps.forscience.javalib.Consumer;
import com.google.android.apps.forscience.javalib.FailureListener;
import com.google.android.apps.forscience.javalib.MaybeConsumer;
import com.google.android.apps.forscience.javalib.MaybeConsumers;
import com.google.android.apps.forscience.javalib.Success;
import com.google.android.apps.forscience.whistlepunk.data.GoosciSensorLayout;
import com.google.android.apps.forscience.whistlepunk.metadata.ApplicationLabel;
import com.google.android.apps.forscience.whistlepunk.metadata.Experiment;
import com.google.android.apps.forscience.whistlepunk.metadata.ExperimentRun;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.Label;
import com.google.android.apps.forscience.whistlepunk.metadata.MetaDataManager;
import com.google.android.apps.forscience.whistlepunk.metadata.Project;
import com.google.android.apps.forscience.whistlepunk.metadata.Run;
import com.google.android.apps.forscience.whistlepunk.metadata.RunStats;
import com.google.android.apps.forscience.whistlepunk.sensordb.ScalarReadingList;
import com.google.android.apps.forscience.whistlepunk.sensordb.SensorDatabase;
import com.google.android.apps.forscience.whistlepunk.sensordb.TimeRange;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

public class DataControllerImpl implements DataController, RecordingDataController {
    private final SensorDatabase mSensorDatabase;
    private final Executor mUiThread;
    private final Executor mMetaDataThread;
    private final Executor mSensorDataThread;
    private MetaDataManager mMetaDataManager;
    private Clock mClock;
    private Map<String, FailureListener> mSensorFailureListeners = new HashMap<>();

    public DataControllerImpl(SensorDatabase sensorDatabase, Executor uiThread,
            Executor metaDataThread,
            Executor sensorDataThread, MetaDataManager metaDataManager, Clock clock) {
        mSensorDatabase = sensorDatabase;
        mUiThread = uiThread;
        mMetaDataThread = metaDataThread;
        mSensorDataThread = sensorDataThread;
        mMetaDataManager = metaDataManager;
        mClock = clock;
    }

    public void replaceSensorInExperiment(final String experimentId, final String oldSensorId,
            final String newSensorId, final MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.removeSensorFromExperiment(oldSensorId, experimentId);
                mMetaDataManager.addSensorToExperiment(newSensorId, experimentId);
                replaceIdInLayouts(experimentId, oldSensorId, newSensorId);
                return Success.SUCCESS;
            }
        });
    }

    private void replaceIdInLayouts(String experimentId, String oldSensorId, String newSensorId) {
        List<GoosciSensorLayout.SensorLayout> layouts = mMetaDataManager.getExperimentSensorLayout(
                experimentId);
        for (GoosciSensorLayout.SensorLayout layout : layouts) {
            if (layout.sensorId.equals(oldSensorId)) {
                layout.sensorId = newSensorId;
            }
        }
        mMetaDataManager.setExperimentSensorLayout(experimentId, layouts);
    }

    public void stopRun(final Experiment experiment, final String runId,
            final List<GoosciSensorLayout.SensorLayout> sensorLayouts,
            final MaybeConsumer<ApplicationLabel> onSuccess) {
        addApplicationLabel(experiment, ApplicationLabel.TYPE_RECORDING_STOP, runId,
                MaybeConsumers.chainFailure(onSuccess, new Consumer<ApplicationLabel>() {
                    @Override
                    public void take(final ApplicationLabel applicationLabel) {
                        background(DataControllerImpl.this.mMetaDataThread, onSuccess,
                                new Callable<ApplicationLabel>() {
                            @Override
                            public ApplicationLabel call() throws Exception {
                                mMetaDataManager.newRun(experiment, runId, sensorLayouts);
                                return applicationLabel;
                            }
                        });
                    }
                }));
    }

    @Override
    public void updateRun(final Run run, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.updateRun(run);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void deleteRun(final String runId, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.deleteRun(runId);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void addScalarReading(final String sensorId, final int resolutionTier,
            final long timestampMillis, final double value) {
        mSensorDataThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mSensorDatabase.addScalarReading(sensorId, resolutionTier, timestampMillis,
                            value);
                } catch (final Exception e) {
                    mUiThread.execute(new Runnable() {
                        @Override
                        public void run() {
                            notifyFailureListener(sensorId, e);
                        }
                    });
                }
            }
        });
    }

    private void notifyFailureListener(String sensorId, Exception e) {
        FailureListener listener = mSensorFailureListeners.get(sensorId);
        if (listener != null) {
            listener.fail(e);
        }
    }

    @Override
    public void getScalarReadings(final String databaseTag, final int resolutionTier,
            final TimeRange timeRange, final int maxRecords,
            final MaybeConsumer<ScalarReadingList> onSuccess) {
        Preconditions.checkNotNull(databaseTag);
        background(mSensorDataThread, onSuccess, new Callable<ScalarReadingList>() {
            @Override
            public ScalarReadingList call() throws Exception {
                return mSensorDatabase.getScalarReadings(databaseTag, timeRange, resolutionTier,
                        maxRecords);
            }
        });
    }

    public void addLabel(final Label label, final MaybeConsumer<Label> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Label>() {
            @Override
            public Label call() throws Exception {
                mMetaDataManager.addLabel(label.getExperimentId(), label);
                return label;
            }
        });
    }

    @Override
    public void editLabel(final Label updatedLabel, final MaybeConsumer<Label> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Label>() {
            @Override
            public Label call() throws Exception {
                mMetaDataManager.editLabel(updatedLabel);
                return updatedLabel;
            }
        });
    }

    @Override
    public void deleteLabel(final Label label, final MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.deleteLabel(label);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void getLastUsedProject(MaybeConsumer<Project> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Project>() {
            @Override
            public Project call() throws Exception {
                return mMetaDataManager.getLastUsedProject();
            }
        });
    }

    @Override public void startRun(
            final Experiment experiment, final MaybeConsumer<ApplicationLabel> onSuccess) {
        String id = generateNewLabelId();
        addApplicationLabelWithId(
                experiment, ApplicationLabel.TYPE_RECORDING_START, id, id, onSuccess);
    }

    private void addApplicationLabel(
            final Experiment experiment, final @ApplicationLabel.Type int type,
            final String startLabelId, final MaybeConsumer<ApplicationLabel> onSuccess) {
        addApplicationLabelWithId(experiment, type, generateNewLabelId(), startLabelId, onSuccess);
    }

    private void addApplicationLabelWithId(
            final Experiment experiment, final @ApplicationLabel.Type int type, final String id,
            final String startLabelId, final MaybeConsumer<ApplicationLabel> onSuccess) {
        // Adds an application label with the given ID and startLabelId.
        background(mMetaDataThread, onSuccess, new Callable<ApplicationLabel>() {
            @Override
            public ApplicationLabel call() throws Exception {
                final ApplicationLabel label = new ApplicationLabel(type, id, startLabelId,
                        mClock.getNow());
                mMetaDataManager.addLabel(experiment, label);
                return label;
            }
        });
    }

    @Override
    public void createExperiment(final Project project,
                                 final MaybeConsumer<Experiment> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Experiment>() {
            @Override
            public Experiment call() throws Exception {
                Experiment experiment = mMetaDataManager.newExperiment(project);
                mMetaDataManager.updateLastUsedExperiment(experiment);
                mMetaDataManager.updateLastUsedProject(project);
                return experiment;
            }
        });
    }

    @Override
    public void deleteExperiment(final Experiment experiment,
                                 final MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {

            @Override
            public Success call() throws Exception {
                mMetaDataManager.deleteExperiment(experiment);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void getExperimentById(final String experimentId,
                                  final MaybeConsumer<Experiment> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Experiment>() {
            @Override
            public Experiment call() throws Exception {
                return mMetaDataManager.getExperimentById(experimentId);
            }
        });
    }

    @Override
    public void updateExperiment(final Experiment experiment, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.updateExperiment(experiment);
                return Success.SUCCESS;
            }
        });

    }

    @Override
    public String generateNewLabelId() {
        return "label_" + System.currentTimeMillis();
    }

    // TODO(saff): test
    @Override public void getExperimentRun(final String startLabelId,
            final MaybeConsumer<ExperimentRun> onSuccess) {
        Preconditions.checkNotNull(startLabelId);
        background(mMetaDataThread, onSuccess, new Callable<ExperimentRun>() {
            @Override
            public ExperimentRun call() throws Exception {
                return buildExperimentRunOnDataThread(startLabelId);
            }
        });
    }

    @Override
    public void getExperimentRuns(final String experimentId, final boolean includeArchived,
            final MaybeConsumer<List<ExperimentRun>> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<List<ExperimentRun>>() {
            @Override
            public List<ExperimentRun> call() throws Exception {
                final List<ExperimentRun> runs = new ArrayList<>();
                List<String> startLabelIds = mMetaDataManager.getExperimentRunIds(experimentId,
                        includeArchived);
                for (String startLabelId : startLabelIds) {
                    ExperimentRun run = buildExperimentRunOnDataThread(startLabelId);
                    if (run.isValidRun()) {
                        runs.add(run);
                    }
                }
                return runs;
            }
        });
    }

    private ExperimentRun buildExperimentRunOnDataThread(String startLabelId) {
        final List<Label> allLabels = mMetaDataManager.getLabelsWithStartId(startLabelId);
        Run run = mMetaDataManager.getRun(startLabelId);
        return ExperimentRun.fromLabels(run, allLabels);
    }

    @Override public void createProject(final MaybeConsumer<Project> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Project>() {
            @Override
            public Project call() throws Exception {
                Project project = mMetaDataManager.newProject();
                mMetaDataManager.updateLastUsedProject(project);
                return project;
            }
        });
    }

    @Override
    public void updateProject(final Project project, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.updateProject(project);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void deleteProject(final Project project, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.deleteProject(project);
                return Success.SUCCESS;
            }
        });
    }

    @Override public void getProjects(final int maxNumber, final boolean includeArchived,
                                      final MaybeConsumer<List<Project>> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<List<Project>>() {
            @Override
            public List<Project> call() throws Exception {
                return mMetaDataManager.getProjects(maxNumber, includeArchived);
            }
        });
    }

    @Override public void getExperimentsForProject(final Project project,
            final boolean includeArchived, final MaybeConsumer<List<Experiment>> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<List<Experiment>>() {
            @Override
            public List<Experiment> call() throws Exception {
                return mMetaDataManager.getExperimentsForProject(project, includeArchived);
            }
        });
    }

    @Override
    public void getProjectById(final String projectId, final MaybeConsumer<Project> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Project>() {
            @Override
            public Project call() throws Exception {
                return mMetaDataManager.getProjectById(projectId);
            }
        });
    }

    @Override
    public void getExternalSensors(final MaybeConsumer<Map<String, ExternalSensorSpec>> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Map<String, ExternalSensorSpec>>() {
            @Override
            public Map<String, ExternalSensorSpec> call() throws Exception {
                return mMetaDataManager.getExternalSensors();
            }
        });
    }

    @Override
    public void getExternalSensorsByExperiment(final String experimentId,
            final MaybeConsumer<Map<String, ExternalSensorSpec>> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Map<String, ExternalSensorSpec>>() {
            @Override
            public Map<String, ExternalSensorSpec> call() throws Exception {
                return mMetaDataManager.getExperimentExternalSensors(experimentId);
            }
        });
    }


    @Override
    public void getExternalSensorById(final String id,
                                      final MaybeConsumer<ExternalSensorSpec> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<ExternalSensorSpec>() {
            @Override
            public ExternalSensorSpec call() throws Exception {
                return mMetaDataManager.getExternalSensorById(id);
            }
        });
    }

    @Override
    public void addSensorToExperiment(final String experimentId, final String sensorId,
            MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.addSensorToExperiment(sensorId, experimentId);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void removeSensorFromExperiment(final String experimentId, final String sensorId,
            MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {

            @Override
            public Success call() throws Exception {
                mMetaDataManager.removeSensorFromExperiment(sensorId, experimentId);
                replaceIdInLayouts(experimentId, sensorId, "");
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void getLabelsForExperiment(final Experiment experiment,
                                       MaybeConsumer<List<Label>> onSuccess) {
        Preconditions.checkNotNull(experiment);
        background(mMetaDataThread, onSuccess, new Callable<List<Label>>() {
            @Override
            public List<Label> call() throws Exception {
                return mMetaDataManager.getLabelsForExperiment(experiment);
            }
        });
    }

    @Override
    public void updateLastUsedExperiment(
            final Experiment experiment, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.updateLastUsedExperiment(experiment);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void getStats(final String runId, final String sensorId,
            MaybeConsumer<RunStats> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<RunStats>() {
            @Override
            public RunStats call() throws Exception {
                return mMetaDataManager.getStats(runId, sensorId);
            }
        });
    }

    @Override
    public void setStats(final String runId, final String sensorId, final RunStats runStats) {
        mMetaDataThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mMetaDataManager.setStats(runId, sensorId, runStats);
                } catch (final Exception e) {
                    mUiThread.execute(new Runnable() {
                        @Override
                        public void run() {
                            notifyFailureListener(sensorId, e);
                        }
                    });
                }
            }
        });
    }

    @Override
    public void setDataErrorListenerForSensor(String sensorId, FailureListener listener) {
        mSensorFailureListeners.put(sensorId, listener);
    }

    @Override
    public void clearDataErrorListenerForSensor(String sensorId) {
        mSensorFailureListeners.remove(sensorId);
    }

    @Override
    public void getExperimentStats(final String experimentId,
            MaybeConsumer<Map<String, RunStats>> onSuccess) {
        // TODO: perhaps return a different data structure?
        background(mMetaDataThread, onSuccess, new Callable<Map<String, RunStats>>() {
            @Override
            public Map<String, RunStats> call() throws Exception {
                Map<String, RunStats> returnValues = new ArrayMap<String, RunStats>();
                // Need to count the runs that have the sensor, or else the averages will be off.
                Map<String, Integer> runCount = new ArrayMap<String, Integer>();
                List<String> runIds = mMetaDataManager.getExperimentRunIds(experimentId,
                        /* don't include archived runs */ false);
                for (String runId : runIds) {
                    Run run = mMetaDataManager.getRun(runId);
                    for (String sensorId : run.getSensorIds()) {
                        // First increment the runCount for this sensor.
                        if (!runCount.containsKey(sensorId)) {
                            runCount.put(sensorId, 0);
                        }
                        runCount.put(sensorId, runCount.get(sensorId) + 1);

                        RunStats stats;
                        if (!returnValues.containsKey(sensorId)) {
                            returnValues.put(sensorId, new RunStats());
                        }
                        stats = returnValues.get(sensorId);
                        RunStats runStats = mMetaDataManager.getStats(runId, sensorId);
                        for (String key : runStats.getKeys()) {
                            double existingValue = stats.getStat(key, 0.0d);
                            double newValue = runStats.getStat(key, 0.0d);
                            // Currently just averaging these numbers, might do something else based
                            // on the value.
                            stats.putStat(key, existingValue + newValue);
                        }
                    }
                }

                // Now loop through the stats and divide by the runCount to get the average.
                for (String sensorId : runCount.keySet()) {
                    RunStats stats = returnValues.get(sensorId);
                    for (String key : stats.getKeys()) {
                        stats.putStat(key, stats.getStat(key, 0.0d) / runCount.get(sensorId));
                    }
                }

                return returnValues;
            }
        });
    }

    @Override
    public void setSensorLayouts(final String experimentId,
            final List<GoosciSensorLayout.SensorLayout> layouts, MaybeConsumer<Success> onSuccess) {
        background(mMetaDataThread, onSuccess, new Callable<Success>() {
            @Override
            public Success call() throws Exception {
                mMetaDataManager.setExperimentSensorLayout(experimentId, layouts);
                return Success.SUCCESS;
            }
        });
    }

    @Override
    public void getSensorLayouts(final String experimentId,
            MaybeConsumer<List<GoosciSensorLayout.SensorLayout>> onSuccess) {
        background(mMetaDataThread, onSuccess,
                new Callable<List<GoosciSensorLayout.SensorLayout>>() {
            @Override
            public List<GoosciSensorLayout.SensorLayout> call() throws Exception {
                List<GoosciSensorLayout.SensorLayout> sensorLayout =
                        mMetaDataManager.getExperimentSensorLayout(experimentId);
                return sensorLayout;
            }
        });
    }

    @Override
    public void addOrGetExternalSensor(final ExternalSensorSpec sensor,
            final MaybeConsumer<String> onSensorId) {
        background(mMetaDataThread, onSensorId, new Callable<String>() {
            @Override
            public String call() throws Exception {
                return mMetaDataManager.addOrGetExternalSensor(sensor);
            }
        });
    }

    private <T> void background(Executor dataThread, final MaybeConsumer<T> onSuccess,
            final Callable<T> job) {
        dataThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T result = job.call();
                    mUiThread.execute(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess.success(result);
                        }
                    });
                } catch (final Exception e) {
                    mUiThread.execute(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess.fail(e);
                        }
                    });
                }
            }
        });
    }
}
