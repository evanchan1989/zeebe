/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.logstreams.util;

import java.util.function.Consumer;

import io.zeebe.logstreams.LogStreams;
import io.zeebe.logstreams.fs.FsLogStreamBuilder;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.spi.SnapshotStorage;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.clock.ControlledActorClock;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

public class LogStreamRule extends ExternalResource
{

    public static final String DEFAULT_NAME = "test-logstream";

    private final String name;
    private final TemporaryFolder temporaryFolder;

    private ActorScheduler actorScheduler;
    private LogStream logStream;

    private ControlledActorClock clock = new ControlledActorClock();
    private SnapshotStorage snapshotStorage;

    private final Consumer<FsLogStreamBuilder> streamBuilder;

    public LogStreamRule(final TemporaryFolder temporaryFolder)
    {
        this(DEFAULT_NAME, temporaryFolder);
    }

    public LogStreamRule(final TemporaryFolder temporaryFolder, final Consumer<FsLogStreamBuilder> streamBuilder)
    {
        this(DEFAULT_NAME, temporaryFolder, streamBuilder);
    }

    public LogStreamRule(final String name, final TemporaryFolder temporaryFolder)
    {
        this(name, temporaryFolder, b ->
        { });
    }

    public LogStreamRule(final String name, final TemporaryFolder temporaryFolder, final Consumer<FsLogStreamBuilder> streamBuilder)
    {
        this.name = name;
        this.temporaryFolder = temporaryFolder;
        this.streamBuilder = streamBuilder;
    }

    @Override
    protected void before()
    {
        actorScheduler = new ActorSchedulerRule(clock).get();
        actorScheduler.start();

        final FsLogStreamBuilder builder = LogStreams.createFsLogStream(BufferUtil.wrapString(name), 0)
                .logDirectory(temporaryFolder.getRoot().getAbsolutePath())
                .actorScheduler(actorScheduler)
                .deleteOnClose(true);

        // apply additional configs
        streamBuilder.accept(builder);

        snapshotStorage = builder.getSnapshotStorage();
        logStream = builder.build();

        logStream.open();
        logStream.openLogStreamController().join();
    }


    @Override
    protected void after()
    {
        logStream.close();
        actorScheduler.stop();
    }

    public LogStream getLogStream()
    {
        return logStream;
    }

    public SnapshotStorage getSnapshotStorage()
    {
        return snapshotStorage;
    }

    public void setCommitPosition(final long position)
    {
        logStream.setCommitPosition(position);
    }

    public long getCommitPosition()
    {
        return logStream.getCommitPosition();
    }

    public ControlledActorClock getClock()
    {
        return clock;
    }

    public ActorScheduler getActorScheduler()
    {
        return actorScheduler;
    }

}
