package com.tyron.builder.internal.logging.progress;

import com.tyron.builder.util.GUtil;
import com.tyron.builder.internal.logging.progress.ProgressLogger;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.BuildOperationMetadata;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;

import javax.annotation.Nullable;

public class DefaultProgressLoggerFactory implements ProgressLoggerFactory {
    private final ProgressListener progressListener;
    private final Clock clock;
    private final BuildOperationIdFactory buildOperationIdFactory;
    private final ThreadLocal<ProgressLoggerImpl> current = new ThreadLocal<ProgressLoggerImpl>();
    private final CurrentBuildOperationRef currentBuildOperationRef = CurrentBuildOperationRef.instance();

    public DefaultProgressLoggerFactory(ProgressListener progressListener, Clock clock, BuildOperationIdFactory buildOperationIdFactory) {
        this.progressListener = progressListener;
        this.clock = clock;
        this.buildOperationIdFactory = buildOperationIdFactory;
    }

    @Override
    public ProgressLogger newOperation(Class<?> loggerCategory) {
        return newOperation(loggerCategory.getName());
    }

    @Override
    public ProgressLogger newOperation(Class<?> loggerCategory, BuildOperationDescriptor buildOperationDescriptor) {
        String category = ProgressStartEvent.BUILD_OP_CATEGORY;
        BuildOperationMetadata metadata = buildOperationDescriptor.getMetadata();
        BuildOperationCategory buildOperationCategory = metadata == BuildOperationMetadata.NONE
                ? BuildOperationCategory.UNCATEGORIZED
                : (BuildOperationCategory) metadata;
        if (buildOperationCategory == BuildOperationCategory.TASK) {
            // This is a legacy quirk.
            // Scans use this to determine that progress logging is indicating start/finish of tasks.
            // This can be removed in Gradle 5.0 (along with the concept of a “logging category” of an operation)
            category = ProgressStartEvent.TASK_CATEGORY;
        }

        ProgressLoggerImpl logger = new ProgressLoggerImpl(
                null,
                buildOperationDescriptor.getId(),
                category,
                progressListener,
                clock,
                true,
                buildOperationDescriptor.getId(),
                buildOperationDescriptor.getParentId(),
                buildOperationCategory
        );
        logger.totalProgress = buildOperationDescriptor.getTotalProgress();

        // Make some assumptions about the console output
        if (buildOperationCategory.isTopLevelWorkItem()) {
            logger.loggingHeader = buildOperationDescriptor.getProgressDisplayName();
        }

        return logger;
    }

    @Override
    public ProgressLogger newOperation(String loggerCategory) {
        return init(loggerCategory, null);
    }

    @Override
    public ProgressLogger newOperation(Class<?> loggerClass, ProgressLogger parent) {
        return init(loggerClass.toString(), parent);
    }

    private ProgressLogger init(
            String loggerCategory,
            @Nullable ProgressLogger parentOperation
    ) {
        if (parentOperation != null && !(parentOperation instanceof ProgressLoggerImpl)) {
            throw new IllegalArgumentException("Unexpected parent logger.");
        }
        BuildOperationRef currentBuildOperation = currentBuildOperationRef.get();
        return new ProgressLoggerImpl(
                (ProgressLoggerImpl) parentOperation,
                new OperationIdentifier(buildOperationIdFactory.nextId()),
                loggerCategory,
                progressListener,
                clock,
                false,
                currentBuildOperation != null ? currentBuildOperation.getId() : null,
                currentBuildOperation != null ? currentBuildOperation.getParentId() : null,
                null
        );
    }

    private enum State {idle, started, completed}

    private class ProgressLoggerImpl implements ProgressLogger {
        private final OperationIdentifier progressOperationId;
        private final String category;
        private final ProgressListener listener;
        private final Clock clock;
        private final boolean buildOperationStart;
        @Nullable
        private final OperationIdentifier buildOperationId;
        @Nullable
        private final OperationIdentifier parentBuildOperationId;
        private final BuildOperationCategory buildOperationCategory;
        private ProgressLoggerImpl previous;
        private ProgressLoggerImpl parent;
        private String description;
        private String loggingHeader;
        private State state = State.idle;
        private int totalProgress;

        ProgressLoggerImpl(
                ProgressLoggerImpl parent,
                OperationIdentifier progressOperationId,
                String category,
                ProgressListener listener,
                Clock clock,
                boolean buildOperationStart,
                @Nullable OperationIdentifier buildOperationId,
                @Nullable OperationIdentifier parentBuildOperationId,
                @Nullable BuildOperationCategory buildOperationCategory
        ) {
            this.parent = parent;
            this.progressOperationId = progressOperationId;
            this.category = category;
            this.listener = listener;
            this.clock = clock;
            this.buildOperationStart = buildOperationStart;
            this.buildOperationId = buildOperationId;
            this.parentBuildOperationId = parentBuildOperationId;
            this.buildOperationCategory = buildOperationCategory;
        }

        @Override
        public String toString() {
            return category + " - " + description;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public ProgressLogger setDescription(String description) {
            assertCanConfigure();
            this.description = description;
            return this;
        }

        @Override
        public ProgressLogger start(String description, String status) {
            setDescription(description);
            started(status);
            return this;
        }

        @Override
        public void started() {
            started(null);
        }

        @Override
        public void started(String status) {
            started(status, totalProgress);
        }

        private void started(String status, int totalProgress) {
            if (!GUtil.isTrue(description)) {
                throw new IllegalStateException("A description must be specified before this operation is started.");
            }
            assertNotStarted();
            state = State.started;
            previous = current.get();
            OperationIdentifier parentProgressId;
            if (parent == null) {
                if (previous != null) {
                    parent = previous;
                    parentProgressId = parent.progressOperationId;
                } else if (buildOperationStart) {
                    parentProgressId = parentBuildOperationId;
                } else {
                    parentProgressId = buildOperationId;
                }
            } else {
                parentProgressId = parent.progressOperationId;
                parent.assertRunning();
            }
            current.set(this);
            listener.started(new ProgressStartEvent(
                    progressOperationId,
                    parentProgressId,
                    clock.getCurrentTime(),
                    category,
                    description,
                    loggingHeader,
                    ensureNotNull(status),
                    totalProgress,
                    buildOperationStart,
                    buildOperationId,
                    buildOperationCategory
            ));
        }

        @Override
        public void progress(String status) {
            progress(status, false);
        }

        @Override
        public void progress(String status, boolean failing) {
            assertRunning();
            listener.progress(new ProgressEvent(progressOperationId, ensureNotNull(status), failing));
        }

        @Override
        public void completed() {
            completed(null, false);
        }

        @Override
        public void completed(String status, boolean failed) {
            assertRunning();
            state = State.completed;
            current.set(previous);
            listener.completed(new ProgressCompleteEvent(progressOperationId, clock.getCurrentTime(), ensureNotNull(status), failed));
        }

        private String ensureNotNull(String status) {
            return status == null ? "" : status;
        }

        private void assertNotStarted() {
            if (state == State.started) {
                throw new IllegalStateException(String.format("This operation (%s) has already been started.", this));
            }
            if (state == State.completed) {
                throw new IllegalStateException(String.format("This operation (%s) has already completed.", this));
            }
        }

        private void assertRunning() {
            if (state == State.idle) {
                throw new IllegalStateException(String.format("This operation (%s) has not been started.", this));
            }
            if (state == State.completed) {
                throw new IllegalStateException(String.format("This operation (%s) has already been completed.", this));
            }
        }

        private void assertCanConfigure() {
            if (state != State.idle) {
                throw new IllegalStateException(String.format("Cannot configure this operation (%s) once it has started.", this));
            }
        }

    }
}
