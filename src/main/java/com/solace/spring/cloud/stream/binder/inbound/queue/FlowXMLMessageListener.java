package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageListener;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@SuppressWarnings("deprecation")
public class FlowXMLMessageListener implements XMLMessageListener {
    @SuppressWarnings("MismatchedReadAndWriteOfArray") // to keep the messageId's in memory and be able to analyze them in the stacktrace
    private final String[] messageIdRingBuffer = new String[128];
    private final BlockingQueue<BytesXMLMessage> messageQueue = new LinkedBlockingDeque<>();
    private final Set<MessageInProgress> activeMessages = new HashSet<>();
    private final AtomicReference<SolaceMeterAccessor> solaceMeterAccessor = new AtomicReference<>();
    private final AtomicReference<String> bindingName = new AtomicReference<>();
    private int messageIdIndex = 0;
    private volatile boolean running = true;

    public void setSolaceMeterAccessor(SolaceMeterAccessor solaceMeterAccessor, String bindingName) {
        this.solaceMeterAccessor.set(solaceMeterAccessor);
        this.bindingName.set(bindingName);
    }

    public void startReceiverThreads(int count, String threadNamePrefix, Consumer<BytesXMLMessage> messageConsumer, long maxProcessingTimeMs) {
        if (maxProcessingTimeMs < 100) {
            throw new IllegalArgumentException("maxProcessingTimeMs must be at least 100ms");
        }
        for (int i = 0; i < count; i++) {
            String threadName = threadNamePrefix + "-" + i;
            Thread thread = new Thread(() -> loop(threadName, messageConsumer));
            thread.setName(threadName);
            thread.start();
            log.info("Started receiving thread {}", thread.getName());
        }
        Thread thread = new Thread(() -> watchdog(maxProcessingTimeMs));
        thread.setName(threadNamePrefix + "-watchdog");
        thread.start();
    }

    public void stopReceiverThreads() {
        running = false;
    }

    @SuppressWarnings("BusyWait")
    private void watchdog(long maxProcessingTimeMs) {
        while (running) {
            try {
                if (solaceMeterAccessor.get() != null && bindingName.get() != null) {
                    solaceMeterAccessor.get().recordQueueSize(this.bindingName.get(), messageQueue.size());
                    solaceMeterAccessor.get().recordActiveMessages(this.bindingName.get(), activeMessages.size());
                }
                long currentTimeMillis = System.currentTimeMillis();
                long sleepMillis = maxProcessingTimeMs / 2;
                synchronized (activeMessages) {
                    for (MessageInProgress messageInProgress : activeMessages) {
                        long timeInProcessing = currentTimeMillis - messageInProgress.startMillis;
                        long timeTillWarning = maxProcessingTimeMs - timeInProcessing;
                        if (timeTillWarning < sleepMillis) {
                            sleepMillis = Math.min(sleepMillis, Math.max(10, timeTillWarning + 1));
                        }
                        if (!messageInProgress.warned && timeInProcessing > maxProcessingTimeMs) {
                            messageInProgress.setWarned(true);
                            log.warn("message is in progress for too long thread={} durationMs={} messageId={}", messageInProgress.threadName, timeInProcessing, messageInProgress.bytesXMLMessage.getMessageId());
                        }
                        if (!messageInProgress.errored && timeInProcessing > maxProcessingTimeMs * 10) {
                            messageInProgress.setErrored(true);
                            log.error("message is in progress for too long thread={} durationMs={} messageId={}", messageInProgress.threadName, timeInProcessing, messageInProgress.bytesXMLMessage.getMessageId());
                        }
                    }
                }
                Thread.sleep(sleepMillis);
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void loop(String threadName, Consumer<BytesXMLMessage> messageConsumer) {
        while (running) {
            try {
                BytesXMLMessage polled = messageQueue.poll(1, TimeUnit.SECONDS);
                if (polled != null) {
                    MessageInProgress mip = new MessageInProgress(System.currentTimeMillis(), threadName, polled);
                    synchronized (activeMessages) {
                        activeMessages.add(mip);
                    }
                    messageConsumer.accept(polled);
                    synchronized (activeMessages) {
                        activeMessages.remove(mip);
                    }
                }
            } catch (Throwable e) {
                log.error("Error was not properly handled in JCSMPInboundQueueMessageProducer", e);
            }
        }
    }

    @Override
    public void onReceive(BytesXMLMessage bytesXMLMessage) {
        log.debug("Received BytesXMLMessage:{}", bytesXMLMessage);
        keepMessageIdInMemoryForDebugPurposes(bytesXMLMessage);
        try {
            int i = 0;
            while (i++ < 100) {
                // since the messageQueue is unbounded this should never happen and is here for paranoia and because the blocking put had strange behaviours
                if (messageQueue.offer(bytesXMLMessage, 1, TimeUnit.SECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            log.warn("unable to add message:{}", bytesXMLMessage);
            try {
                bytesXMLMessage.settle(XMLMessage.Outcome.FAILED);
            } catch (JCSMPException ex) {
                log.error(ex.getMessage(), ex);
            }
        }
    }


    private void keepMessageIdInMemoryForDebugPurposes(BytesXMLMessage bytesXMLMessage) {
        this.messageIdRingBuffer[messageIdIndex] = bytesXMLMessage.getMessageId();
        messageIdIndex = ++messageIdIndex % messageIdRingBuffer.length;
        log.trace("Message ID stored in ring buffer. messageId={}", bytesXMLMessage.getMessageId());
    }

    @Override
    public void onException(JCSMPException e) {
        log.error("Failed to receive message", e);
    }

    @Data
    @RequiredArgsConstructor
    static class MessageInProgress {
        private final long startMillis;
        private final String threadName;
        private final BytesXMLMessage bytesXMLMessage;
        private boolean warned = false;
        private boolean errored = false;
    }
}
