package io.zeebe.logstreams.log;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.logstreams.util.*;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.ActorCondition;
import org.agrona.DirectBuffer;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

public class LogStorageAppenderTest
{
    private static final DirectBuffer EVENT = wrapString("FOO");

    private TemporaryFolder temporaryFolder = new TemporaryFolder();

    private LogStreamRule logStreamRule = new LogStreamRule(temporaryFolder, b ->
    {
        final LogStorage logStorage = b.getLogStorage();
        b.logStorage(spy(logStorage));
    });

    private LogStreamWriterRule writer = new LogStreamWriterRule(logStreamRule);
    private LogStreamReaderRule reader = new LogStreamReaderRule(logStreamRule);

    @Rule
    public RuleChain ruleChain =
        RuleChain.outerRule(temporaryFolder)
                 .around(logStreamRule)
                 .around(reader)
                 .around(writer);

    private LogStream logStream;
    private LogStorage logStorageSpy;

    @Before
    public void setup()
    {
        logStream = logStreamRule.getLogStream();
        logStorageSpy = logStream.getLogStorage();
    }

    @Test
    public void shouldAppendEvents()
    {
        writer.writeEvents(10, EVENT, true);

        reader.assertEvents(10, EVENT);
    }

    @Test
    public void shouldUpdateAppenderPosition()
    {
        final long positionBefore = logStream.getCurrentAppenderPosition();

        writer.writeEvent(EVENT);

        waitUntil(() -> logStream.getCurrentAppenderPosition() > positionBefore);
    }

    @Test
    public void shouldInvokeOnAppendConditions()
    {
        final AtomicInteger counter = new AtomicInteger();

        logStreamRule.getActorScheduler().submitActor(new Actor()
        {
            @Override
            protected void onActorStarting()
            {
                final ActorCondition condition = actor.onCondition("appended", () -> counter.incrementAndGet());
                logStream.registerOnAppendCondition(condition);
            }

        }).join();

        writer.writeEvent(EVENT);

        waitUntil(() -> counter.get() == 1);
    }

    @Test
    public void shouldDiscardEventsIfFailToAppend()
    {
        final Subscription subscription = logStream.getWriteBuffer().openSubscription("test");

        final long positionBefore = logStream.getCurrentAppenderPosition();

        doReturn(-1L).when(logStorageSpy).append(any());

        // when log storage append fails
        writer.writeEvent(EVENT);

        // then
        waitUntil(() -> logStream.getCurrentAppenderPosition() > positionBefore);

        assertThat(logStream.getLogStreamController().isFailed()).isTrue();

        // verify that the segment is marked as failed
        final AtomicBoolean fragmentIsFailed = new AtomicBoolean();
        subscription.poll(new FragmentHandler()
        {

            @Override
            public int onFragment(DirectBuffer buffer, int offset, int length, int streamId, boolean isMarkedFailed)
            {
                fragmentIsFailed.set(isMarkedFailed);
                return 0;
            }
        }, 1);

        assertThat(fragmentIsFailed).isTrue();
    }

}
