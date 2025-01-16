package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.config.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.util.FlowReceiverContainer.FlowReceiverReference;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension.ExecSvc;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.action.model.ActionMsgVpnClientDisconnect;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.ApiException;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnClientTransactedSession;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueTxFlow;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempMetaOnlyResponse;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.*;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public class FlowReceiverContainerIT {
    private String vpnName;
    private final AtomicReference<FlowReceiverContainer> flowReceiverContainerReference = new AtomicReference<>();
    private XMLMessageProducer producer;


    @BeforeEach
    public void setup(JCSMPSession jcsmpSession) throws Exception {
        vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        producer = jcsmpSession.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {
                log.debug("Got message with key: " + key);
            }

            @Override
            public void handleErrorEx(Object o, JCSMPException e, long l) {
                log.error("Failed to send message", e);
            }
        });
    }

    @AfterEach
    public void cleanup() {
        if (producer != null) {
            producer.close();
        }

        Optional.ofNullable(flowReceiverContainerReference.getAndSet(null))
                .map(FlowReceiverContainer::getFlowReceiverReference)
                .map(FlowReceiverReference::get)
                .ifPresent(Consumer::close);
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testBind(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        assertNull(flowReceiverContainer.getFlowReceiverReference());
        UUID flowReferenceId = flowReceiverContainer.bind();
        assertNotNull(flowReferenceId);

        Assertions.assertThat(flowReceiverContainer.getFlowReceiverReference())
                .satisfies(
                        r -> Assertions.assertThat(r.getId()).isEqualTo(flowReferenceId),
                        r -> Assertions.assertThat(r.get().getEndpoint()).isEqualTo(queue)
                );
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testBindABoundFlow(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        UUID flowReferenceId = flowReceiverContainer.bind();
        assertEquals(flowReferenceId, flowReceiverContainer.bind());

        FlowReceiverReference flowReference = flowReceiverContainer.getFlowReceiverReference();
        assertNotNull(flowReference);

        MonitorMsgVpnQueue queueInfo = getQueueInfo(sempV2Api, queue);
        assertNotNull(queueInfo);
        assertEquals((Long) 1L, queueInfo.getBindRequestCount());
        assertEquals((Long) 1L, queueInfo.getBindSuccessCount());

        List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
        assertThat(txFlows, hasSize(1));
        assertEquals(jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), txFlows.get(0).getClientName());

        Assertions.assertThat(getTransactedSessions(sempV2Api, jcsmpSession)).isEmpty();
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testBindAnUnboundFlow(

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        UUID flowReferenceId = flowReceiverContainer.bind();
        flowReceiverContainer.unbind();

        UUID reboundFlowReferenceId = flowReceiverContainer.bind();
        assertNotEquals(flowReferenceId, reboundFlowReferenceId);

        FlowReceiverReference flowReference = flowReceiverContainer.getFlowReceiverReference();
        assertNotNull(flowReference);

        Assertions.assertThat(getTxFlows(sempV2Api, queue, 2))
                .singleElement()
                .extracting(MonitorMsgVpnQueueTxFlow::getClientName)
                .isEqualTo(jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testBindWhileReceiving(

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api,
            @ExecSvc(poolSize = 1) ExecutorService executorService) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        UUID flowReferenceId = flowReceiverContainer.bind();
        Future<MessageContainer> receiveFuture = executorService.submit((Callable<MessageContainer>) flowReceiverContainer::receive);

        // To make sure the flow receive is actually blocked
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        assertFalse(receiveFuture.isDone());

        UUID newFlowReferenceId = flowReceiverContainer.bind();
        assertNotNull(flowReceiverContainer.getFlowReceiverReference());
        assertEquals(flowReferenceId, newFlowReferenceId);
        assertThat(getTxFlows(sempV2Api, queue, 2), hasSize(1));

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        assertNotNull(receiveFuture.get(1, TimeUnit.MINUTES));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testConcurrentBind(

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        CyclicBarrier barrier = new CyclicBarrier(30);
        ExecutorService executorService = Executors.newFixedThreadPool(barrier.getParties());
        try {
            Set<Future<UUID>> futures = IntStream.range(0, barrier.getParties())
                    .mapToObj(i -> (Callable<UUID>) () -> {
                        barrier.await();
                        return flowReceiverContainer.bind();
                    })
                    .map(executorService::submit)
                    .collect(Collectors.toSet());
            executorService.shutdown();

            List<UUID> newFlowReferenceIds = futures.stream()
                    .map((ThrowingFunction<Future<UUID>, UUID>) f -> f.get(1, TimeUnit.MINUTES))
                    .toList();
            assertThat(newFlowReferenceIds.stream().distinct().collect(Collectors.toList()), hasSize(1));

            UUID newFlowReferenceId = newFlowReferenceIds.stream()
                    .filter(Objects::nonNull)
                    .findAny()
                    .orElse(null);
            assertNotNull(newFlowReferenceId);

            FlowReceiverReference flowReference = flowReceiverContainer.getFlowReceiverReference();
            assertNotNull(flowReference);

            MonitorMsgVpnQueue queueInfo = getQueueInfo(sempV2Api, queue);
            assertNotNull(queueInfo);
            assertEquals((Long) 1L, queueInfo.getBindRequestCount());
            assertEquals((Long) 1L, queueInfo.getBindSuccessCount());

            List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
            assertThat(txFlows, hasSize(1));
            assertEquals(jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), txFlows.get(0).getClientName());
        } finally {
            executorService.shutdownNow();
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testUnbind(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        flowReceiverContainer.unbind();
        assertNull(flowReceiverContainer.getFlowReceiverReference());
        assertThat(getTxFlows(sempV2Api, queue, 1), hasSize(0));
        assertThat(getTransactedSessions(sempV2Api, jcsmpSession), hasSize(0));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testUnbindANonBoundFlow(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.unbind();
        if (isDurable) {
            MonitorMsgVpnQueue queueInfo = getQueueInfo(sempV2Api, queue);
            assertNotNull(queueInfo);
            assertEquals((Long) 0L, queueInfo.getBindRequestCount());
        } else {
            assertNull(getQueueInfo(sempV2Api, queue));
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testUnbindAnUnboundFlow(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        flowReceiverContainer.unbind();
        flowReceiverContainer.unbind();
        if (isDurable) {
            MonitorMsgVpnQueue queueInfo = getQueueInfo(sempV2Api, queue);
            assertNotNull(queueInfo);
            assertEquals((Long) 1L, queueInfo.getBindRequestCount());
            assertEquals((Long) 1L, queueInfo.getBindSuccessCount());
        } else {
            assertNull(getQueueInfo(sempV2Api, queue));
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testUnbindWhileReceiving(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Future<MessageContainer> receiveFuture = executorService.submit((Callable<MessageContainer>) flowReceiverContainer::receive);

            // To make sure the flow receive is actually blocked
            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            assertFalse(receiveFuture.isDone());

            flowReceiverContainer.unbind();
            assertNull(flowReceiverContainer.getFlowReceiverReference());
            assertThat(getTxFlows(sempV2Api, queue, 1), hasSize(0));
            assertThat(getTransactedSessions(sempV2Api, jcsmpSession), hasSize(0));

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> receiveFuture.get(1, TimeUnit.MINUTES));
            assertThat(exception.getCause(), instanceOf(JCSMPTransportException.class));
            assertThat(exception.getMessage(), containsString("Consumer was closed while in receive"));
        } finally {
            executorService.shutdownNow();
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testUnbindWithUnacknowledgedMessage(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        List<MessageContainer> receivedMsgs = new ArrayList<>();
        receivedMsgs.add(flowReceiverContainer.receive());
        assertNotNull(receivedMsgs.get(0));
        receivedMsgs.add(flowReceiverContainer.receive());
        assertNotNull(receivedMsgs.get(receivedMsgs.size() - 1));

        flowReceiverContainer.unbind();
        assertTrue(receivedMsgs.stream().allMatch(MessageContainer::isStale));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testConcurrentUnbind(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();

        CyclicBarrier barrier = new CyclicBarrier(30);
        ExecutorService executorService = Executors.newFixedThreadPool(barrier.getParties());
        try {
            Set<Future<?>> futures = IntStream.range(0, barrier.getParties())
                    .mapToObj(i -> (Callable<?>) () -> {
                        barrier.await();
                        flowReceiverContainer.unbind();
                        return null;
                    })
                    .map(executorService::submit)
                    .collect(Collectors.toSet());
            executorService.shutdown();

            for (Future<?> future : futures) {
                future.get(1, TimeUnit.MINUTES);
            }

            MonitorMsgVpnQueue queueInfo = getQueueInfo(sempV2Api, queue);
            if (isDurable) {
                assertNotNull(queueInfo);
                assertEquals((Long) 1L, queueInfo.getBindRequestCount());
                assertEquals((Long) 1L, queueInfo.getBindSuccessCount());

                assertThat(getTxFlows(sempV2Api, queue, 1), hasSize(0));
            } else {
                assertNull(queueInfo);
            }
            assertThat(getTransactedSessions(sempV2Api, jcsmpSession), hasSize(0));
        } finally {
            executorService.shutdownNow();
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testPauseResume(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        int defaultWindowSize = (int) jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        assertThat(getTxFlows(sempV2Api, queue, 2), hasSize(0));
        flowReceiverContainer.bind();
        assertEquals(defaultWindowSize, getTxFlows(sempV2Api, queue, 2).get(0).getWindowSize());

        flowReceiverContainer.pause();
        assertTrue(flowReceiverContainer.isPaused());
        assertEquals(0, getTxFlows(sempV2Api, queue, 1).get(0).getWindowSize());

        flowReceiverContainer.resume();
        assertFalse(flowReceiverContainer.isPaused());
        assertEquals(defaultWindowSize, getTxFlows(sempV2Api, queue, 1).get(0).getWindowSize());
    }

    @CartesianTest(name = "[{index}] testResuming={0} isDurable={1}")
    public void testPauseResumeANonBoundFlow(
            @Values(booleans = {false, true}) boolean testResuming,

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        if (testResuming) {
            flowReceiverContainer.pause();
        }

        assertNull(flowReceiverContainer.getFlowReceiverReference());
        assertEquals(testResuming, flowReceiverContainer.isPaused());

        if (testResuming) {
            flowReceiverContainer.resume();
        } else {
            flowReceiverContainer.pause();
        }
        assertNull(flowReceiverContainer.getFlowReceiverReference());
        assertEquals(!testResuming, flowReceiverContainer.isPaused());
        assertEquals(0, getTxFlows(sempV2Api, queue, 1).size());
    }

    @CartesianTest(name = "[{index}] testResuming={0} isDurable={1}")
    public void testPauseResumeAnUnboundFlow(
            @Values(booleans = {false, true}) boolean testResuming,
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        flowReceiverContainer.unbind();

        if (testResuming) {
            flowReceiverContainer.pause();
        }

        assertNull(flowReceiverContainer.getFlowReceiverReference());
        assertEquals(testResuming, flowReceiverContainer.isPaused());
        if (testResuming) {
            flowReceiverContainer.resume();
        } else {
            flowReceiverContainer.pause();
        }
        assertNull(flowReceiverContainer.getFlowReceiverReference());
        assertEquals(!testResuming, flowReceiverContainer.isPaused());
        assertEquals(0, getTxFlows(sempV2Api, queue, 1).size());
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testPauseWhileResuming(

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api,
            @ExecSvc ExecutorService executorService) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        flowReceiverContainer.pause();
        assertTrue(flowReceiverContainer.isPaused());

        CountDownLatch midResumeLatch = new CountDownLatch(1);
        // can't use latch, or else pause() will take write lock
        CountDownLatch finishResumeLatch = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            // Call real method first since the potential race condition can happen right after
            // this method returns in flowReceiverContainer.resume()
            Object toReturn = invocation.callRealMethod();
            midResumeLatch.countDown();
            finishResumeLatch.await();
            return toReturn;
        }).when(flowReceiverContainer).doFlowReceiverReferenceResume();

        Future<?> resumeFuture = executorService.submit(() -> {
            try {
                flowReceiverContainer.resume();
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(midResumeLatch.await(1, TimeUnit.MINUTES));
        Future<?> pauseFuture = executorService.submit(flowReceiverContainer::pause);
        executorService.shutdown();

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        assertFalse(resumeFuture.isDone());
        assertFalse(pauseFuture.isDone());
        assertTrue(flowReceiverContainer.isPaused());

        finishResumeLatch.countDown();
        pauseFuture.get(1, TimeUnit.MINUTES);
        resumeFuture.get(1, TimeUnit.MINUTES);
        assertTrue(flowReceiverContainer.isPaused());

        List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
        assertThat(txFlows, hasSize(1));
        assertEquals(0, txFlows.get(0).getWindowSize());
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testResumeWhilePausing(

            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api,
            @ExecSvc ExecutorService executorService) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        assertFalse(flowReceiverContainer.isPaused());

        CountDownLatch midPauseLatch = new CountDownLatch(1);
        CountDownLatch finishPauseLatch = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            // Call real method first since the potential race condition can happen right after
            // this method returns in flowReceiverContainer.pause()
            Object toReturn = invocation.callRealMethod();
            midPauseLatch.countDown();
            finishPauseLatch.await();
            return toReturn;
        }).when(flowReceiverContainer).doFlowReceiverReferencePause();

        Future<?> pauseFuture = executorService.submit(flowReceiverContainer::pause);
        assertTrue(midPauseLatch.await(1, TimeUnit.MINUTES));
        Future<?> resumeFuture = executorService.submit(() -> {
            try {
                flowReceiverContainer.resume();
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        });
        executorService.shutdown();

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        assertFalse(pauseFuture.isDone());
        assertFalse(resumeFuture.isDone());
        assertFalse(flowReceiverContainer.isPaused());

        finishPauseLatch.countDown();
        resumeFuture.get(1, TimeUnit.MINUTES);
        pauseFuture.get(1, TimeUnit.MINUTES);
        assertFalse(flowReceiverContainer.isPaused());

        List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
        assertThat(txFlows, hasSize(1));
        int defaultWindowSize = (int) jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE);
        assertEquals(defaultWindowSize, txFlows.get(0).getWindowSize());
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceive(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

        UUID flowReferenceId1 = flowReceiverContainer.bind();
        producer.send(message, queue);
        MessageContainer messageReceived = flowReceiverContainer.receive();

        assertNotNull(messageReceived);
        assertThat(messageReceived.getMessage(), instanceOf(TextMessage.class));
        assertEquals(flowReferenceId1, messageReceived.getFlowReceiverReferenceId());
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveOnANonBoundFlow(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        UnboundFlowReceiverContainerException exception = assertThrows(UnboundFlowReceiverContainerException.class,
                flowReceiverContainer::receive);
        assertThat(exception.getMessage(), containsString("is not bound"));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveNoWait(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        assertNull(flowReceiverContainer.receive(0));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveWithTimeout(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

        UUID flowReferenceId = flowReceiverContainer.bind();
        producer.send(message, queue);
        MessageContainer messageReceived =
                flowReceiverContainer.receive((int) TimeUnit.MINUTES.toMillis(5));

        assertNotNull(messageReceived);
        assertThat(messageReceived.getMessage(), instanceOf(TextMessage.class));
        assertEquals(flowReferenceId, messageReceived.getFlowReceiverReferenceId());
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveElapsedTimeout(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        assertNull(flowReceiverContainer.receive(1));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveNegativeTimeout(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        assertNull(flowReceiverContainer.receive(-1));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveZeroTimeout(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        assertNull(flowReceiverContainer.receive(0));
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveInterrupt(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Future<MessageContainer> receiveFuture = executorService.submit((Callable<MessageContainer>) flowReceiverContainer::receive);
            executorService.shutdown();

            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            assertFalse(receiveFuture.isDone());

            executorService.shutdownNow(); // interrupt
            assertNull(receiveFuture.get(1, TimeUnit.MINUTES));
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testReceiveInterruptedByFlowReconnect(
            JCSMPSession jcsmpSession,
            Queue queue,
            SempV2Api sempV2Api) throws Exception {
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);
        flowReceiverContainer.bind();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Future<MessageContainer> receiveFuture = executorService.submit((Callable<MessageContainer>) flowReceiverContainer::receive);
            executorService.shutdown();

            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            assertFalse(receiveFuture.isDone());

            log.info(String.format("Disabling egress to queue %s", queue.getName()));
            sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), vpnName, queue.getName(),
                    null, null);
            retryAssert(() -> assertFalse(sempV2Api.monitor()
                    .getMsgVpnQueue(vpnName, queue.getName(), null)
                    .getData()
                    .isEgressEnabled()));

            log.info(String.format("Sending message to queue %s", queue.getName()));
            producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);

            log.info(String.format("Enabling egress to queue %s", queue.getName()));
            sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), vpnName, queue.getName(),
                    null, null);
            retryAssert(() -> assertTrue(sempV2Api.monitor()
                    .getMsgVpnQueue(vpnName, queue.getName(), null)
                    .getData()
                    .isEgressEnabled()));

            assertNotNull(receiveFuture.get(1, TimeUnit.MINUTES));
        } finally {
            executorService.shutdownNow();
        }
    }

    @CartesianTest(name = "[{index}] isDurable={0}")
    public void testReceiveInterruptedBySessionReconnect(
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();

        String clientName = (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Future<MessageContainer> receiveFuture = executorService.submit((Callable<MessageContainer>) flowReceiverContainer::receive);
            executorService.shutdown();

            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            assertFalse(receiveFuture.isDone());

            log.info(String.format("Remotely disconnecting client %s", clientName));
            sempV2Api.action().doMsgVpnClientDisconnect(new ActionMsgVpnClientDisconnect(), vpnName, clientName);
            Thread.sleep(TimeUnit.SECONDS.toMillis(5));

            log.info(String.format("Sending message to queue %s", queue.getName()));
            producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);

            assertNotNull(receiveFuture.get(1, TimeUnit.MINUTES));
        } finally {
            executorService.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAcknowledgeNull(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.acknowledge(null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAcknowledgeAfterUnbind(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        flowReceiverContainer.bind();

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageReceived = flowReceiverContainer.receive();
        assertNotNull(messageReceived);

        flowReceiverContainer.unbind();
        assertTrue(messageReceived.isStale());

        assertThrows(SolaceAcknowledgmentException.class, () -> flowReceiverContainer.acknowledge(messageReceived));
    }

    @Test
    public void testAcknowledgeAfterFlowReconnect(JCSMPSession jcsmpSession, Queue queue, SempV2Api sempV2Api) throws Exception {
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

        flowReceiverContainer.bind();

        producer.send(message, queue);
        MessageContainer receivedMessage = flowReceiverContainer.receive();
        assertNotNull(receivedMessage);

        log.info(String.format("Disabling egress to queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), vpnName, queue.getName(),
                null, null);
        retryAssert(() -> assertFalse(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        Thread.sleep(TimeUnit.SECONDS.toMillis(1));

        log.info(String.format("Enabling egress to queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), vpnName, queue.getName(),
                null, null);
        retryAssert(() -> assertTrue(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        log.info(String.format("Acknowledging message %s", receivedMessage.getMessage().getMessageId()));
        flowReceiverContainer.acknowledge(receivedMessage);

        receivedMessage = flowReceiverContainer.receive();
        flowReceiverContainer.acknowledge(receivedMessage);

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
        assertThat(txFlows, hasSize(1));
        assertEquals((Long) 1L, txFlows.get(0).getAckedMsgCount());
        assertEquals((Long) 0L, txFlows.get(0).getUnackedMsgCount());
        assertEquals((Long) 1L, txFlows.get(0).getRedeliveredMsgCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testAcknowledgeAfterSessionReconnect(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                                     SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

        flowReceiverContainer.bind();

        producer.send(message, queue);
        MessageContainer receivedMessage = flowReceiverContainer.receive();
        assertNotNull(receivedMessage);

        String clientName = (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME);

        log.info(String.format("Remotely disconnecting client %s", clientName));
        sempV2Api.action().doMsgVpnClientDisconnect(new ActionMsgVpnClientDisconnect(), vpnName, clientName);
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info(String.format("Acknowledging message %s", receivedMessage.getMessage().getMessageId()));
        //The redelivery flow is kind of Renumber flow, below ack will be discarded by broker
        flowReceiverContainer.acknowledge(receivedMessage);

        //The message will be redelivered
        MessageContainer redeliveredMessage = flowReceiverContainer.receive();
        //Ack redelivered message
        flowReceiverContainer.acknowledge(redeliveredMessage);

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        List<MonitorMsgVpnQueueTxFlow> txFlows = getTxFlows(sempV2Api, queue, 2);
        assertThat(txFlows, hasSize(1));
        assertEquals((Long) 1L, txFlows.get(0).getAckedMsgCount());
        assertEquals((Long) 0L, txFlows.get(0).getUnackedMsgCount());
        assertEquals((Long) 1L, txFlows.get(0).getRedeliveredMsgCount());
    }

    private FlowReceiverContainer createFlowReceiverContainer(JCSMPSession jcsmpSession,
                                                              Queue queue) {
        AtomicReference<FlowReceiverContainer> ref = flowReceiverContainerReference;
        if (ref.compareAndSet(null, Mockito.spy(new FlowReceiverContainer(
                jcsmpSession,
                JCSMPFactory.onlyInstance().createQueue(queue.getName()),
                new EndpointProperties(),
                new ConsumerFlowProperties())))) {
            log.info("Created new FlowReceiverContainer {}", ref.get().getId());
        }

        return ref.get();
    }

    private MonitorMsgVpnQueue getQueueInfo(SempV2Api sempV2Api, Queue queue) throws ApiException {
        try {
            return sempV2Api.monitor().getMsgVpnQueue(vpnName, queue.getName(), null).getData();
        } catch (ApiException e) {
            return processApiException(sempV2Api, e);
        }
    }

    private List<MonitorMsgVpnQueueTxFlow> getTxFlows(SempV2Api sempV2Api, Queue queue, Integer count)
            throws ApiException {
        try {
            return sempV2Api.monitor()
                    .getMsgVpnQueueTxFlows(vpnName, queue.getName(), count, null, null, null)
                    .getData();
        } catch (ApiException e) {
            return processApiException(sempV2Api, e);
        }
    }

    private List<MonitorMsgVpnClientTransactedSession> getTransactedSessions(SempV2Api sempV2Api,
                                                                             JCSMPSession jcsmpSession)
            throws ApiException {
        try {
            return sempV2Api.monitor()
                    .getMsgVpnClientTransactedSessions(vpnName,
                            (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME),
                            null, null, null, null)
                    .getData();
        } catch (ApiException e) {
            return processApiException(sempV2Api, e);
        }
    }

    private <T> T processApiException(SempV2Api sempV2Api, ApiException e) throws ApiException {
        MonitorSempMetaOnlyResponse response = sempV2Api.monitor()
                .getApiClient()
                .getJSON()
                .deserialize(e.getResponseBody(), MonitorSempMetaOnlyResponse.class);
        if (response.getMeta().getError().getStatus().equals("NOT_FOUND")) {
            return null;
        } else {
            throw e;
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> extends Function<T, R> {

        @Override
        default R apply(T t) {
            try {
                return applyThrows(t);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        R applyThrows(T t) throws Exception;
    }
}
