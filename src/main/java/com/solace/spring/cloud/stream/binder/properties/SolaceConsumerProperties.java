package com.solace.spring.cloud.stream.binder.properties;

import com.solace.spring.cloud.stream.binder.util.EndpointType;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solacesystems.jcsmp.EndpointProperties;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

import static com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties.DEFAULTS_PREFIX;

@Getter
@Setter
@SuppressWarnings("ConfigurationProperties")
@ConfigurationProperties(DEFAULTS_PREFIX + ".consumer")
public class SolaceConsumerProperties extends SolaceCommonProperties {
    /**
     * The type of endpoint messages are consumed from.
     */
    private EndpointType endpointType = EndpointType.QUEUE;

    /**
     * <p>The maximum number of messages per batch.</p>
     * <p>Only applicable when {@code batchMode} is {@code true}.</p>
     */
    @Min(1)
    private int batchMaxSize = 255;

    /**
     * <p>The maximum wait time in milliseconds to receive a batch of messages. If this timeout is reached, then the
     * messages that have already been received will be used to create the batch. A value of {@code 0} means wait
     * forever.</p>
     * <p>Only applicable when {@code batchMode} is {@code true}.</p>
     */
    @Min(0)
    private int batchTimeout = 5000;

    /**
     * Maximum wait time for polled consumers to receive a message from their consumer group queue.
     * <p>Only applicable when {@code batchMode} is {@code false}.</p>
     */
    private int polledConsumerWaitTimeInMillis = 100;

    /**
     * An array of additional topic subscriptions to be applied on the consumer group queue.
     * These subscriptions may also contain wildcards.
     */
    private String[] queueAdditionalSubscriptions = new String[0];

    /**
     * A SpEL expression for creating the consumer group’s queue name.
     * Modifying this can cause naming conflicts between the queue names of consumer groups.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private String queueNameExpression = "'scst/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')";

    /**
     * A SQL-92 selector expression to use for selection of messages for consumption. Max of 2000 characters.
     */
    private String selector = null;

    // Error Queue Properties ---------
    /**
     * Whether to automatically create a durable error queue to which messages will be republished when message processing failures are encountered.
     * Only applies once all internal retries have been exhausted.
     */
    private boolean autoBindErrorQueue = false;
    /**
     * Whether to provision durable queues for error queues when autoBindErrorQueue is true.
     * This should only be set to false if you have externally pre-provisioned the required queue on the message broker.
     */
    private boolean provisionErrorQueue = true;
    /**
     * A SpEL expression for creating the error queue’s name.
     * Modifying this can cause naming conflicts between the error queue names.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private String errorQueueNameExpression = "'scst/error/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')";

    /**
     * Maximum number of attempts to send a failed message to the error queue.
     * When all delivery attempts have been exhausted, the failed message will be requeued.
     */
    private long errorQueueMaxDeliveryAttempts = 3;
    /**
     * Access type for the error queue.
     */
    private int errorQueueAccessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE;
    /**
     * Permissions for the error queue.
     */
    private int errorQueuePermission = EndpointProperties.PERMISSION_CONSUME;
    /**
     * If specified, whether to notify sender if a message fails to be enqueued to the error queue.
     */
    private Integer errorQueueDiscardBehaviour = null;
    /**
     * Sets the maximum message redelivery count on the error queue. (Zero means retry forever).
     */
    private Integer errorQueueMaxMsgRedelivery = null;
    /**
     * Maximum message size for the error queue.
     */
    private Integer errorQueueMaxMsgSize = null;
    /**
     * Message spool quota for the error queue.
     */
    private Integer errorQueueQuota = null;
    /**
     * Whether the error queue respects Message TTL.
     */
    private Boolean errorQueueRespectsMsgTtl = null;
    /**
     * The eligibility for republished messages to be moved to a Dead Message Queue.
     */
    private Boolean errorMsgDmqEligible = null;
    /**
     * The number of milliseconds before republished messages are discarded or moved to a Dead Message Queue.
     */
    private Long errorMsgTtl = null;
    /**
     * Indicated if messages should be consumed using a queue or directly via topic.
     */
    private QualityOfService qualityOfService = QualityOfService.AT_LEAST_ONCE;
    // ------------------------

    /**
     * The list of headers to exclude when converting consumed Solace message to Spring message.
     */
    private List<String> headerExclusions = new ArrayList<>();


    public void setBatchMaxSize(int batchMaxSize) {
        Assert.isTrue(batchMaxSize >= 1, "max batch size must be greater than 0");
        this.batchMaxSize = batchMaxSize;
    }


    public void setBatchTimeout(int batchTimeout) {
        Assert.isTrue(batchTimeout >= 0, "batch timeout must be greater than or equal to 0");
        this.batchTimeout = batchTimeout;
    }
}
