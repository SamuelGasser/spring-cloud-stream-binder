package com.solace.spring.cloud.stream.binder.outbound;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.transaction.RollbackException;
import com.solacesystems.jcsmp.transaction.TransactedSession;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

@Timeout(value = 10)
@ExtendWith(MockitoExtension.class)
public class JCSMPOutboundMessageHandlerTest {

    private JCSMPOutboundMessageHandler messageHandler;
    private ArgumentCaptor<JCSMPStreamingPublishCorrelatingEventHandler> pubEventHandlerCaptor;
    private ArgumentCaptor<XMLMessage> xmlMessageCaptor;
    private ArgumentCaptor<Destination> destinationCaptor;
    private ArgumentCaptor<ProducerFlowProperties> producerFlowPropertiesCaptor;
    private ExtendedProducerProperties<SolaceProducerProperties> producerProperties;
    private JCSMPSessionProducerManager sessionProducerManager;
    @Mock
    private JCSMPSession session;
    @Mock
    private XMLMessageProducer messageProducer;
    @Mock
    private TransactedSession transactedSession;
    @Mock
    private SolaceMeterAccessor solaceMeterAccessor;

    @BeforeEach
    public void init(@Mock MessageChannel errChannel, @Mock SolaceMessageHeaderErrorMessageStrategy errorMessageStrategy, @Mock XMLMessageProducer defaultGlobalSessionProducer) throws JCSMPException {
        xmlMessageCaptor = ArgumentCaptor.forClass(XMLMessage.class);
        destinationCaptor = ArgumentCaptor.forClass(Destination.class);

        Mockito.lenient().when(session.createTransactedSession()).thenReturn(transactedSession);

        producerFlowPropertiesCaptor = ArgumentCaptor.forClass(ProducerFlowProperties.class);
        pubEventHandlerCaptor = ArgumentCaptor.forClass(JCSMPStreamingPublishCorrelatingEventHandler.class);
        Mockito.lenient().when(session.createProducer(producerFlowPropertiesCaptor.capture(), pubEventHandlerCaptor.capture())).thenReturn(messageProducer);
        Mockito.lenient().when(transactedSession.createProducer(producerFlowPropertiesCaptor.capture(), pubEventHandlerCaptor.capture())).thenReturn(messageProducer);

        Mockito.when(session.getMessageProducer(Mockito.any())).thenReturn(defaultGlobalSessionProducer);

        ProducerDestination dest = Mockito.mock(ProducerDestination.class);
        Mockito.when(dest.getName()).thenReturn("fake/topic");

        producerProperties = new ExtendedProducerProperties<>(new SolaceProducerProperties());
        producerProperties.populateBindingName(RandomStringUtils.randomAlphanumeric(100));

        sessionProducerManager = Mockito.spy(new JCSMPSessionProducerManager(session));

        messageHandler = new JCSMPOutboundMessageHandler(dest, session, errChannel, sessionProducerManager, producerProperties, solaceMeterAccessor);
        messageHandler.setErrorMessageStrategy(errorMessageStrategy);
    }

    @CartesianTest(name = "[{index}] transacted={0}")
    public void test_start(@CartesianTest.Values(booleans = {false, true}) boolean transacted) throws Exception {
        producerProperties.getExtension().setTransacted(transacted);
        messageHandler.start();

        if (transacted) {
            Mockito.verify(session, Mockito.never()).createProducer(Mockito.any(), Mockito.any());
            Mockito.verify(transactedSession).createProducer(Mockito.any(), Mockito.any());
        } else {
            Mockito.verify(session).createProducer(Mockito.any(), Mockito.any());
            Mockito.verify(transactedSession, Mockito.never()).createProducer(Mockito.any(), Mockito.any());
        }
    }

    @CartesianTest(name = "[{index}] transacted={0}")
    public void test_start_fail(@CartesianTest.Values(booleans = {false, true}) boolean transacted) throws Exception {
        producerProperties.getExtension().setTransacted(transacted);

        JCSMPException exception = new JCSMPException("error");
        if (transacted) {
            Mockito.doThrow(exception).when(transactedSession).createProducer(Mockito.any(), Mockito.any());
        } else {
            Mockito.doThrow(exception).when(session).createProducer(Mockito.any(), Mockito.any());
        }

        assertThatThrownBy(() -> messageHandler.start()).hasRootCause(exception);
        Mockito.verify(sessionProducerManager).release(Mockito.any());
        if (transacted) {
            Mockito.verify(transactedSession).close();
        }
    }

    @CartesianTest(name = "[{index}] transacted={0}")
    public void test_responseReceived_withInTimeout(@CartesianTest.Values(booleans = {false, true}) boolean transacted) throws Exception {
        producerProperties.getExtension().setTransacted(transacted);
        messageHandler.start();

        CorrelationData correlationData = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationData));

        if (transacted) {
            Mockito.verify(transactedSession).commit();
        }

        pubEventHandlerCaptor.getValue().responseReceivedEx(getCorrelationKey());

        correlationData.getFuture().get(100, TimeUnit.MILLISECONDS);
    }

    @CartesianTest(name = "[{index}] transacted={0}")
    public void test_handleError_withInTimeout(@CartesianTest.Values(booleans = {false, true}) boolean transacted) throws Exception {
        producerProperties.getExtension().setTransacted(transacted);
        messageHandler.start();

        CorrelationData correlationData = new CorrelationData();
        Message<String> msg = getMessage(correlationData);
        messageHandler.handleMessage(msg);

        if (transacted) {
            Mockito.verify(transactedSession).commit();
        }

        pubEventHandlerCaptor.getValue().handleErrorEx(createCorrelationKey(correlationData, msg), new JCSMPException("ooooops"), 1111);

        assertThatThrownBy(() -> correlationData.getFuture().get(100, TimeUnit.MILLISECONDS)).isInstanceOf(ExecutionException.class).cause().isInstanceOf(MessagingException.class).cause().isInstanceOf(JCSMPException.class).hasMessage("ooooops");
    }

    @Test()
    public void test_responseReceived_withOutTimeout() {
        messageHandler.start();
        CorrelationData correlationData = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationData));

        assertThrows(TimeoutException.class, () -> correlationData.getFuture().get(100, TimeUnit.MILLISECONDS));
    }

    @Test()
    public void test_responseReceived_raceCondition() throws ExecutionException, InterruptedException, TimeoutException {
        messageHandler.start();
        CorrelationData correlationDataA = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationDataA));
        CorrelationData correlationDataB = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationDataB));
        CorrelationData correlationDataC = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationDataC));

        JCSMPStreamingPublishCorrelatingEventHandler pubEventHandler = pubEventHandlerCaptor.getValue();
        pubEventHandler.responseReceivedEx(createCorrelationKey(correlationDataB));
        pubEventHandler.responseReceivedEx(createCorrelationKey(correlationDataA));
        pubEventHandler.responseReceivedEx(createCorrelationKey(correlationDataC));

        correlationDataA.getFuture().get(100, TimeUnit.MILLISECONDS);
        correlationDataB.getFuture().get(100, TimeUnit.MILLISECONDS);
        correlationDataC.getFuture().get(100, TimeUnit.MILLISECONDS);
    }

    @Test()
    public void test_responseReceived_messageIdCollision_oneAfterTheOther() throws ExecutionException, InterruptedException, TimeoutException {
        messageHandler.start();
        JCSMPStreamingPublishCorrelatingEventHandler pubEventHandler = pubEventHandlerCaptor.getValue();

        CorrelationData correlationDataA = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationDataA));
        pubEventHandler.responseReceivedEx(createCorrelationKey(correlationDataA));

        correlationDataA.getFuture().get(100, TimeUnit.MILLISECONDS);


        CorrelationData correlationDataB = new CorrelationData();
        messageHandler.handleMessage(getMessage(correlationDataB));
        pubEventHandler.responseReceivedEx(createCorrelationKey(correlationDataB));

        correlationDataB.getFuture().get(100, TimeUnit.MILLISECONDS);
    }

    @ParameterizedTest
    @ValueSource(classes = {JCSMPException.class, RollbackException.class})
    public void test_transactionRollback_onError(Class<JCSMPException> commitError) throws Exception {
        producerProperties.getExtension().setTransacted(true);
        messageHandler.start();

        JCSMPException exception = commitError.getConstructor(String.class).newInstance("test");
        Mockito.doThrow(exception).when(transactedSession).commit();

        assertThatThrownBy(() -> messageHandler.handleMessage(getMessage(new CorrelationData()))).isInstanceOf(MessagingException.class).hasRootCause(exception);

        Mockito.verify(transactedSession, Mockito.times(commitError.equals(RollbackException.class) ? 0 : 1)).rollback();
    }

    @Test
    public void test_transactionRollbackFailure() throws Exception {
        producerProperties.getExtension().setTransacted(true);
        messageHandler.start();

        JCSMPException commitException = new JCSMPException("commit error");
        Mockito.doThrow(commitException).when(transactedSession).commit();

        JCSMPException rollbackException = new JCSMPException("rollback error");
        Mockito.doThrow(rollbackException).when(transactedSession).rollback();

        assertThatThrownBy(() -> messageHandler.handleMessage(getMessage(new CorrelationData()))).isInstanceOf(MessagingException.class).rootCause().isEqualTo(commitException).hasSuppressedException(rollbackException);
        Mockito.verify(transactedSession).rollback();
    }

    @ParameterizedTest(name = "[{index}] success={0}")
    @ValueSource(booleans = {false, true})
    public void testMeter(boolean success) throws Exception {
        messageHandler.start();

        Message<String> message = MessageBuilder.withPayload(RandomStringUtils.randomAlphanumeric(100)).build();

        if (success) {
            messageHandler.handleMessage(message);
        } else {
            JCSMPException exception = new JCSMPException("Expected exception");
            Mockito.doThrow(exception).when(messageProducer).send(xmlMessageCaptor.capture(), any(Destination.class));
            assertThatThrownBy(() -> messageHandler.handleMessage(message)).isInstanceOf(MessagingException.class).hasCause(exception);
        }

        Mockito.verify(solaceMeterAccessor, Mockito.times(1)).recordMessage(Mockito.eq(producerProperties.getBindingName()), any());
    }

    @Test
    public void test_dynamic_destinationName_only() throws JCSMPException {
        messageHandler.start();

        Message<?> message = MessageBuilder.withPayload("the payload").setHeader(BinderHeaders.TARGET_DESTINATION, "dynamicDestinationName").setHeader("SOME_HEADER", "HOLA") //add extra header and confirm it is kept
                .build();
        messageHandler.handleMessage(message);

        Mockito.verify(messageProducer).send(xmlMessageCaptor.capture(), destinationCaptor.capture());
        Destination targetDestination = destinationCaptor.getValue();
        assertThat(targetDestination).isInstanceOf(Topic.class);
        assertThat(targetDestination.getName()).isEqualTo("dynamicDestinationName");

        XMLMessage sentMessage = xmlMessageCaptor.getValue();
        assertThat(sentMessage.getProperties().get(BinderHeaders.TARGET_DESTINATION)).isNull();
        assertThat(sentMessage.getProperties().get("SOME_HEADER")).isEqualTo("HOLA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"topic", "queue", " TOPIc ", " QueUe  ", "", "   "})
    public void test_dynamic_destinationName_and_destinationType(String destinationType) throws JCSMPException {
        messageHandler.start();

        Message<?> message = getMessageForDynamicDestination("dynamicDestinationName", destinationType);
        messageHandler.handleMessage(message);

        Mockito.verify(messageProducer).send(xmlMessageCaptor.capture(), destinationCaptor.capture());
        Destination targetDestination = destinationCaptor.getValue();

        //MessageHandler uses default producerProperties so blank and unspecified destinationType defaults to Topic
        assertThat(targetDestination).isInstanceOf(destinationType.trim().equalsIgnoreCase("queue") ? Queue.class : Topic.class);
        assertThat(targetDestination.getName()).isEqualTo("dynamicDestinationName");

        //Verify headers don't get set on ongoing Solace message
        XMLMessage sentMessage = xmlMessageCaptor.getValue();
        assertThat(sentMessage.getProperties().get(BinderHeaders.TARGET_DESTINATION)).isNull();
        assertThat(sentMessage.getProperties().get(SolaceBinderHeaders.TARGET_DESTINATION_TYPE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"queue", "topic"})
    public void test_dynamic_destinationName_with_destinationType_configured_on_messageHandler(String type) throws JCSMPException {
        messageHandler.start();

        SolaceProducerProperties producerProperties = new SolaceProducerProperties();
        producerProperties.setDestinationType(type.equals("queue") ? DestinationType.QUEUE : DestinationType.TOPIC);
        ProducerDestination dest = Mockito.mock(ProducerDestination.class);
        Mockito.when(dest.getName()).thenReturn("thisIsOverriddenByDynamicDestinationName");

        messageHandler = new JCSMPOutboundMessageHandler(dest, session, null, new JCSMPSessionProducerManager(session), new ExtendedProducerProperties<>(producerProperties), solaceMeterAccessor);
        messageHandler.start();

        Message<?> message = getMessageForDynamicDestination("dynamicDestinationName", null);
        messageHandler.handleMessage(message);

        Mockito.verify(messageProducer).send(any(), destinationCaptor.capture());
        Destination targetDestination = destinationCaptor.getValue();
        assertThat(targetDestination).isInstanceOf(type.equals("queue") ? Queue.class : Topic.class);
        assertThat(targetDestination.getName()).isEqualTo("dynamicDestinationName");
    }

    @Test
    public void test_dynamic_destination_with_invalid_destinationType() {
        messageHandler.start();
        Message<?> message = getMessageForDynamicDestination("dynamicDestinationName", "INVALID");
        Exception exception = assertThrows(MessagingException.class, () -> messageHandler.handleMessage(message));
        assertThat(exception).hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Incorrect value specified for header 'solace_scst_targetDestinationType'. Expected [ TOPIC|QUEUE ] but actual value is [ INVALID ]");
    }

    @Test
    public void test_dynamic_destinationName_with_invalid_header_value_type() {
        messageHandler.start();
        Message<?> message = getMessageForDynamicDestination(Instant.now(), null);
        Exception exception = assertThrows(MessagingException.class, () -> messageHandler.handleMessage(message));
        assertThat(exception).hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Incorrect type specified for header 'scst_targetDestination'. Expected [class java.lang.String] but actual type is [class java.time.Instant]");
    }

    @Test
    public void test_dynamic_destinationType_with_invalid_header_value_type() {
        messageHandler.start();
        Message<?> message = MessageBuilder.withPayload("the payload").setHeader(BinderHeaders.TARGET_DESTINATION, "someDynamicDestinationName").setHeader(SolaceBinderHeaders.TARGET_DESTINATION_TYPE, Instant.now()).build();
        Exception exception = assertThrows(MessagingException.class, () -> messageHandler.handleMessage(message));
        assertThat(exception).hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Incorrect type specified for header 'solace_scst_targetDestinationType'. Expected [class java.lang.String] but actual type is [class java.time.Instant]");
    }

    // Can remove test if/when SOL-118898 is completed
    @CartesianTest(name = "[{index}] pubAckWindowSize={0}, ackEventMode={1}")
    public void testJCSMPPropertiesInheritanceWorkaround(@CartesianTest.Values(ints = {1, 100, 255}) int pubAckWindowSize, @CartesianTest.Values(strings = {JCSMPProperties.SUPPORTED_ACK_EVENT_MODE_PER_MSG, JCSMPProperties.SUPPORTED_ACK_EVENT_MODE_WINDOWED}) String ackEventMode) {

        Mockito.when(session.getProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE)).thenReturn(pubAckWindowSize);
        Mockito.when(session.getProperty(JCSMPProperties.ACK_EVENT_MODE)).thenReturn(ackEventMode);
        messageHandler.start();

        assertThat(producerFlowPropertiesCaptor.getValue()).satisfies(p -> assertThat(p.getWindowSize()).isEqualTo(pubAckWindowSize), p -> assertThat(p.getAckEventMode()).isEqualTo(ackEventMode));
    }

    Message<String> getMessage(CorrelationData correlationData) {
        return MessageBuilder.withPayload("the payload").setHeader(SolaceBinderHeaders.CONFIRM_CORRELATION, correlationData).build();
    }

    private Message<String> getMessageForDynamicDestination(Object targetDestination, Object targetDestinationType) {
        MessageBuilder<String> builder = MessageBuilder.withPayload("the payload");
        if (targetDestination != null) {
            builder.setHeader(BinderHeaders.TARGET_DESTINATION, targetDestination);
        }
        if (targetDestinationType != null) {
            builder.setHeader(SolaceBinderHeaders.TARGET_DESTINATION_TYPE, targetDestinationType);
        }
        return builder.build();
    }

    private ErrorChannelSendingCorrelationKey getCorrelationKey() throws JCSMPException {
        Mockito.verify(messageProducer).send(xmlMessageCaptor.capture(), any(Destination.class));
        return (ErrorChannelSendingCorrelationKey) xmlMessageCaptor.getValue().getCorrelationKey();
    }

    private ErrorChannelSendingCorrelationKey createCorrelationKey(CorrelationData correlationData, Message<?> msg) {
        ErrorChannelSendingCorrelationKey key = new ErrorChannelSendingCorrelationKey(msg, Mockito.mock(MessageChannel.class), new SolaceMessageHeaderErrorMessageStrategy());
        key.setConfirmCorrelation(correlationData);
        return key;
    }

    private ErrorChannelSendingCorrelationKey createCorrelationKey(CorrelationData correlationData) {
        Message<String> msg = MessageBuilder.withPayload("the empty payload").build();
        return createCorrelationKey(correlationData, msg);
    }
}