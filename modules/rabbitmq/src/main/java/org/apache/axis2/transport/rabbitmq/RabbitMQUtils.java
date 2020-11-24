/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.axis2.transport.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.ParameterInclude;
import org.apache.axis2.description.ParameterIncludeImpl;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecureVaultException;

import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Helper class to support AMQP transport related functions
 */
public class RabbitMQUtils {

    private static final Log log = LogFactory.getLog(RabbitMQUtils.class);

    /**
     * Create a connection from given connection factory and address array
     *
     * @param factory   a {@link ConnectionFactory} object
     * @param addresses a {@link Address} object
     * @return a {@link Connection} object
     * @throws IOException
     */
    public static Connection createConnection(ConnectionFactory factory, Address[] addresses) throws IOException {
        Connection connection = null;
        try {
            connection = factory.newConnection(addresses);
        } catch (TimeoutException e) {
            log.error("Error occurred while creating a connection", e);
        }
        return connection;
    }

    /**
     * Get transport headers from the rabbitmq message
     *
     * @param properties the AMQP basic properties
     * @return a map of headers
     */
    public static Map<String, String> getTransportHeaders(AMQP.BasicProperties properties) {
        Map<String, String> map = new HashMap<>();

        // correlation ID
        if (properties.getCorrelationId() != null) {
            map.put(RabbitMQConstants.CORRELATION_ID, properties.getCorrelationId());
        }

        // if a AMQP message ID is found
        if (properties.getMessageId() != null) {
            map.put(RabbitMQConstants.MESSAGE_ID, properties.getMessageId());
        }

        // replyto destination name
        if (properties.getReplyTo() != null) {
            map.put(RabbitMQConstants.RABBITMQ_REPLY_TO, properties.getReplyTo());
        }

        // any other transport properties / headers
        Map<String, Object> headers = properties.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, Object> headerEntry : headers.entrySet()) {
                if (!BaseConstants.INTERNAL_TRANSACTION_COUNTED.equals(headerEntry.getKey())) {
                    map.put(headerEntry.getKey(), headerEntry.getValue().toString());
                }
            }
        }


        return map;
    }

    /**
     * Get SOAP action from the basic properties' headers
     *
     * @param properties the AMQP basic properties
     * @return the SOAP action if exist
     */
    public static String getSoapAction(AMQP.BasicProperties properties) {
        String soapAction = null;
        Map<String, Object> headers = properties.getHeaders();
        if (headers != null) {
            soapAction = String.valueOf(headers.get(RabbitMQConstants.SOAP_ACTION));
        }
        return soapAction;
    }

    public static boolean isDurableQueue(Map<String, String> properties) {
        String durableString = properties
                .getOrDefault(RabbitMQConstants.QUEUE_DURABLE, RabbitMQConstants.QUEUE_DURABLE_DEFAULT);
        return BooleanUtils.toBoolean(BooleanUtils.toBooleanObject(durableString));
    }

    public static boolean isExclusiveQueue(Map<String, String> properties) {
        return BooleanUtils
                .toBoolean(BooleanUtils.toBooleanObject(properties.get(RabbitMQConstants.QUEUE_EXCLUSIVE)));
    }

    public static boolean isAutoDeleteQueue(Map<String, String> properties) {
        return BooleanUtils
                .toBoolean(BooleanUtils.toBooleanObject(properties.get(RabbitMQConstants.QUEUE_AUTO_DELETE)));
    }

    public static boolean isDurableExchange(Map<String, String> properties) {
        String durableString = properties
                .getOrDefault(RabbitMQConstants.EXCHANGE_DURABLE, RabbitMQConstants.EXCHANGE_DURABLE_DEFAULT);
        return BooleanUtils.toBoolean(BooleanUtils.toBooleanObject(durableString));
    }

    public static boolean isAutoDeleteExchange(Map<String, String> properties) {
        return BooleanUtils
                .toBoolean(BooleanUtils.toBooleanObject(properties.get(RabbitMQConstants.EXCHANGE_AUTO_DELETE)));
    }

    /**
     * Sets optional arguments that can be defined at the queue declaration
     *
     * @param properties amqp properties
     * @return map of optional arguments
     */
    private static Map<String, Object> setQueueOptionalArguments(Map<String, String> properties) {
        Map<String, Object> optionalArgs = new HashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String propertyKey = entry.getKey();
            if (propertyKey.startsWith(RabbitMQConstants.QUEUE_OPTIONAL_ARG_PREFIX)) {
                String optionalArgName = propertyKey.substring(RabbitMQConstants.QUEUE_OPTIONAL_ARG_PREFIX.length());
                String optionalArgValue = entry.getValue();
                //check whether a boolean argument
                if ("true".equals(optionalArgValue) || "false".equals(optionalArgValue)) {
                    optionalArgs.put(optionalArgName, Boolean.parseBoolean(optionalArgValue));
                } else {
                    try {
                        //check whether a integer argument
                        optionalArgs.put(optionalArgName, Integer.parseInt(optionalArgValue));
                    } catch (NumberFormatException e) {
                        optionalArgs.put(optionalArgName, optionalArgValue);
                    }
                }
            }
        }
        return optionalArgs.size() == 0 ? null : optionalArgs;
    }

    /**
     * Sets optional arguments that can be defined at the exchange declaration
     *
     * @param properties amqp properties
     * @return map of optional arguments
     */
    private static Map<String, Object> setExchangeOptionalArguments(Map<String, String> properties) {
        Map<String, Object> optionalArgs = new HashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String propertyKey = entry.getKey();
            if (propertyKey.startsWith(RabbitMQConstants.EXCHANGE_OPTIONAL_ARG_PREFIX)) {
                String optionalArgName = propertyKey.substring(RabbitMQConstants.EXCHANGE_OPTIONAL_ARG_PREFIX.length());
                String optionalArgValue = entry.getValue();
                //check whether a boolean argument
                if ("true".equals(optionalArgValue) || "false".equals(optionalArgValue)) {
                    optionalArgs.put(optionalArgName, Boolean.parseBoolean(optionalArgValue));
                } else {
                    try {
                        //check whether a integer argument
                        optionalArgs.put(optionalArgName, Integer.parseInt(optionalArgValue));
                    } catch (NumberFormatException e) {
                        optionalArgs.put(optionalArgName, optionalArgValue);
                    }
                }
            }
        }
        return optionalArgs.size() == 0 ? null : optionalArgs;
    }

    /**
     * Helper method to declare and bind the exchange and the queue.
     *
     * @param channel    a rabbitmq channel
     * @param queueName  a name of the queue to declare
     * @param properties queue declaration properties
     * @throws IOException if any error occurs during the declaration/binding
     */
    public static void declareQueuesExchangesAndBindings(Channel channel, String queueName, String exchangeName,
            Map<String, String> properties) throws IOException {

        declareQueue(channel, queueName, properties);
        declareExchange(channel, exchangeName, properties);
        bindQueueToExchange(channel, queueName, exchangeName, properties);
    }

    /**
     * Helper method to declare queue when direct channel is given
     *
     * @param channel    a rabbitmq channel
     * @param queueName  a name of the queue to declare
     * @param properties queue declaration properties
     * @throws IOException
     */
    private static void declareQueue(Channel channel, String queueName,
                                    Map<String, String> properties) throws IOException {
        boolean autoDeclare = BooleanUtils.toBooleanDefaultIfNull(
                BooleanUtils.toBooleanObject(properties.get(RabbitMQConstants.QUEUE_AUTODECLARE)), true);
        if (StringUtils.isNotEmpty(queueName) && autoDeclare) {
            channel.queueDeclare(queueName, isDurableQueue(properties), isExclusiveQueue(properties),
                    isAutoDeleteQueue(properties), setQueueOptionalArguments(properties));
        }
    }

    /**
     * Helper method to declare exchange when direct channel is given
     *
     * @param channel      {@link Channel} object
     * @param exchangeName the exchange exchangeName
     * @param properties   RabbitMQ properties
     */
    private static void declareExchange(Channel channel, String exchangeName, Map<String, String> properties) throws IOException {
        boolean autoDeclare = BooleanUtils.toBooleanDefaultIfNull(
                BooleanUtils.toBooleanObject(properties.get(RabbitMQConstants.EXCHANGE_AUTODECLARE)), true);
        if (StringUtils.isNotEmpty(exchangeName) && autoDeclare && !exchangeName.startsWith(RabbitMQConstants.AMQ_PREFIX)) {
            // declare the exchange
            String exchangeType = properties
                    .getOrDefault(RabbitMQConstants.EXCHANGE_TYPE, RabbitMQConstants.EXCHANGE_TYPE_DEFAULT);
            channel.exchangeDeclare(exchangeName, exchangeType, isDurableExchange(properties),
                                    isAutoDeleteExchange(properties), setExchangeOptionalArguments(properties));
        }
    }

    /**
     * Helper method to bind a queue to a specified exchange
     *
     * @param channel      the channel to use for creating the binding
     * @param queueName    the name of the queue to bind to
     * @param exchangeName the name of the exchange to bind the queue to
     * @param properties   optional RabbitMQ properties for the binding creation
     * @throws IOException if an error occurs while creating the binding
     */
    private static void bindQueueToExchange(Channel channel, String queueName, String exchangeName,
            Map<String, String> properties) throws IOException {
        if (StringUtils.isNotEmpty(exchangeName)) {
            String routingKey = properties.get(RabbitMQConstants.QUEUE_ROUTING_KEY);
            // bind the queue and exchange with routing key
            if (StringUtils.isNotEmpty(queueName) && StringUtils.isNotEmpty(routingKey)) {
                channel.queueBind(queueName, exchangeName, routingKey);
            } else if (StringUtils.isNotEmpty(queueName) && StringUtils.isEmpty(routingKey)) {
                if (log.isDebugEnabled()) {
                    log.debug("No routing key specified. The queue name is using as the routing key.");
                }
                routingKey = queueName;
                channel.queueBind(queueName, exchangeName, routingKey);
            }
        }
    }

    /**
     * Resolve transport parameters
     *
     * @param trpDesc                   axis2 transport parameters
     * @param secretResolver            secure vault encryption resolver
     * @param rabbitMQConnectionFactory a rabbitmq connection factory
     * @return pool size for connection and channel pooling
     */
    public static int resolveTransportDescription(ParameterInclude trpDesc, SecretResolver secretResolver,
                                                  RabbitMQConnectionFactory rabbitMQConnectionFactory)
            throws AxisRabbitMQException {
        int poolSize = RabbitMQConstants.DEFAULT_POOL_SIZE;
        for (Parameter parameter : trpDesc.getParameters()) {
            String name = parameter.getName();
            if (StringUtils.equals(name, RabbitMQConstants.PARAM_POOL_SIZE)) {
                try {
                    poolSize = Integer.parseInt((String) parameter.getValue());
                } catch (NumberFormatException e) {
                    throw new AxisRabbitMQException("Pool size must be an integer value.");
                }
            } else {
                Map<String, String> parameters = new HashMap<>();
                ParameterIncludeImpl pi = new ParameterIncludeImpl();
                try {
                    pi.deserializeParameters((OMElement) parameter.getValue());
                } catch (AxisFault axisFault) {
                    throw new AxisRabbitMQException("Error reading parameters for RabbitMQ connection factory " + name,
                            axisFault);
                }
                for (Parameter p : pi.getParameters()) {
                    OMElement paramElement = p.getParameterElement();
                    String propertyValue = p.getValue().toString();
                    if (paramElement != null) {
                        OMAttribute attribute = paramElement.getAttribute(
                                new QName(RabbitMQConstants.SECURE_VAULT_NAMESPACE,
                                        RabbitMQConstants.SECRET_ALIAS_ATTRIBUTE));
                        if (attribute != null && attribute.getAttributeValue() != null
                                && !attribute.getAttributeValue().isEmpty()) {
                            if (secretResolver == null) {
                                throw new SecureVaultException("Axis2 Secret Resolver is null. Cannot resolve " +
                                        "encrypted entry for " + p.getName());
                            }
                            if (secretResolver.isTokenProtected(attribute.getAttributeValue())) {
                                propertyValue = secretResolver.resolve(attribute.getAttributeValue());
                            }
                        }
                    }
                    parameters.put(p.getName(), propertyValue);
                }
                rabbitMQConnectionFactory.addConnectionFactoryConfiguration(name, parameters);
            }
        }
        return poolSize;
    }

    /**
     * Get corresponding AMQP connection factory defined within the transport sender for the
     * transport-out information - usually constructed from a targetEPR
     *
     * @param props                     axis2 configuration parameters
     * @param rabbitMQConnectionFactory a rabbitmq connection factory
     * @return connection factory name
     */
    public static String resolveTransportDescriptionFromTargetEPR(Map<String, String> props,
                                                                  RabbitMQConnectionFactory rabbitMQConnectionFactory) {
        String factoryName = props.get(RabbitMQConstants.RABBITMQ_CON_FAC);
        if (StringUtils.isEmpty(factoryName)) {
            //add all properties to connection factory name in order to have a unique name
            factoryName = props.get(RabbitMQConstants.SERVER_HOST_NAME) + "_" +
                    props.get(RabbitMQConstants.SERVER_PORT) + "_" +
                    props.get(RabbitMQConstants.SERVER_USER_NAME) + "_" +
                    props.get(RabbitMQConstants.SERVER_PASSWORD) + "_" +
                    props.get(RabbitMQConstants.SERVER_VIRTUAL_HOST) + "_" +
                    props.get(RabbitMQConstants.SSL_ENABLED) + "_" +
                    props.get(RabbitMQConstants.SSL_KEYSTORE_LOCATION) + "_" +
                    props.get(RabbitMQConstants.SSL_KEYSTORE_TYPE) + "_" +
                    props.get(RabbitMQConstants.SSL_KEYSTORE_PASSWORD) + "_" +
                    props.get(RabbitMQConstants.SSL_TRUSTSTORE_LOCATION) + "_" +
                    props.get(RabbitMQConstants.SSL_TRUSTSTORE_TYPE) + "_" +
                    props.get(RabbitMQConstants.SSL_TRUSTSTORE_PASSWORD);
        }

        Map<String, String> configurationParameters = rabbitMQConnectionFactory
                .getConnectionFactoryConfiguration(factoryName);
        if (configurationParameters == null) {
            synchronized (rabbitMQConnectionFactory.getConnectionFactoryConfigurations()) {
                // handle concurrency
                configurationParameters = rabbitMQConnectionFactory.getConnectionFactoryConfiguration(factoryName);
                if (configurationParameters == null) {
                    rabbitMQConnectionFactory.addConnectionFactoryConfiguration(factoryName, props);
                }
            }
        }
        return factoryName;
    }

    /**
     * Build SOAP envelop from AMQP msgProperties and byte body
     *
     * @param msgProperties the AMQP basic msgProperties
     * @param body       the message body
     * @param msgContext the message context
     * @param serviceProperties
     * @return content-type used to build the soap message
     * @throws AxisFault
     */
    public static String buildMessage(AMQP.BasicProperties msgProperties, byte[] body, MessageContext msgContext,
                                      Map<String, String> serviceProperties) throws AxisFault {
        setCorrelationId(msgContext, msgProperties);
        String contentType = setContentType(msgContext, msgProperties, serviceProperties);
        setContentEncoding(msgContext, msgProperties, serviceProperties);
        processReplyToHeader(msgContext, msgProperties, contentType);
        setTransactionCountedProperty(msgContext, msgProperties);
        setSoapEnvelop(msgContext, body, contentType);
        return contentType;
    }

    private static void setSoapEnvelop(MessageContext msgContext, byte[] body, String contentType) throws AxisFault {

        Builder builder = getBuilder(msgContext, contentType);
        OMElement documentElement = builder.processDocument(new ByteArrayInputStream(body), contentType, msgContext);
        msgContext.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
    }

    private static Builder getBuilder(MessageContext msgContext, String rawContentType) throws AxisFault {
        try {
            ContentType contentType = new ContentType(rawContentType);
            String charset = contentType.getParameter("charset");
            msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charset);

            Builder builder = BuilderUtil.getBuilderFromSelector(contentType.getBaseType(), msgContext);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + contentType.getBaseType() + "'. Falling back to SOAP.");
                }
                builder = new SOAPBuilder();
            }
            return builder;
        } catch (ParseException e) {
            throw new AxisFault("Error parsing content type: " + rawContentType, e);
        }
    }

    /**
     * Set "INTERNAL_TRANSACTION_COUNTED" property in the message context if is present in the JMS message received.
     */
    private static void setTransactionCountedProperty(MessageContext msgContext, AMQP.BasicProperties msgProperties) {
        Map<String, Object> headers = msgProperties.getHeaders();
        if (headers != null && headers.containsKey(BaseConstants.INTERNAL_TRANSACTION_COUNTED)) {
            msgContext.setProperty(BaseConstants.INTERNAL_TRANSACTION_COUNTED,
                                   msgProperties.getHeaders().get(BaseConstants.INTERNAL_TRANSACTION_COUNTED));
        }
    }

    /**
     * Set out transport info to the message context for rpc messaging flow
     */
    private static void processReplyToHeader(MessageContext msgContext, AMQP.BasicProperties msgProperties,
                                             String contentType) {
        String replyTo = msgProperties.getReplyTo();
        if (replyTo != null) {
            String connectionFactoryName =
                    msgProperties.getHeaders().get(RabbitMQConstants.RABBITMQ_CON_FAC).toString();
            msgContext.setProperty(Constants.OUT_TRANSPORT_INFO,
                                   new RabbitMQOutTransportInfo(connectionFactoryName, contentType));
        }
    }

    private static void setContentEncoding(MessageContext msgContext, AMQP.BasicProperties msgProperties,
                                           Map<String, String> serviceProperties) {

        String encodingFromService = serviceProperties.get(RabbitMQConstants.CONTENT_ENCODING);
        String contentEncoding =
                (StringUtils.isEmpty(encodingFromService)) ? msgProperties.getContentEncoding() : encodingFromService;

        if (contentEncoding != null){
            msgContext.setProperty(RabbitMQConstants.CONTENT_ENCODING, contentEncoding);
        }
    }

    private static String setContentType(MessageContext msgContext, AMQP.BasicProperties msgProperties,
                                         Map<String, String> serviceProperties) {
        String contentTypeFromService = serviceProperties.get(RabbitMQConstants.CONTENT_TYPE);
        String contentTypeFromMessage = msgProperties.getContentType();
        String contentType;

        if (StringUtils.isNotEmpty(contentTypeFromService)) {
            contentType = contentTypeFromService;
        } else if (StringUtils.isNotEmpty(contentTypeFromMessage)) {
            contentType = msgProperties.getContentType();
        } else {
            log.warn("Unable to determine content type for message " + msgContext.getMessageID()
                     + " setting to " + RabbitMQConstants.DEFAULT_CONTENT_TYPE);
            contentType = RabbitMQConstants.DEFAULT_CONTENT_TYPE;
        }
        msgContext.setProperty(RabbitMQConstants.CONTENT_TYPE, contentType);
        return contentType;
    }

    private static void setCorrelationId(MessageContext msgContext, AMQP.BasicProperties msgProperties) {
        String correlationID;

        if (StringUtils.isNotEmpty(msgProperties.getCorrelationId())) {
            correlationID = msgProperties.getCorrelationId();
        } else {
            correlationID = msgProperties.getMessageId();
        }
        msgContext.setProperty(RabbitMQConstants.CORRELATION_ID, correlationID);
    }

    public static String buildMessage(AMQP.BasicProperties properties, byte[] body,
                                      MessageContext msgContext) throws AxisFault {
        return buildMessage(properties, body, msgContext, Collections.emptyMap());
    }

    /**
     * Get the message body from the message context
     *
     * @param msgContext the message context
     * @return the message as the byte array
     * @throws IOException
     */
    public static byte[] getMessageBody(MessageContext msgContext) throws IOException, AxisRabbitMQException {
        OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
        byte[] messageBody;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            MessageFormatter messageFormatter = MessageProcessorSelector.getMessageFormatter(msgContext);
            messageFormatter.writeTo(msgContext, format, out, false);
            messageBody = out.toByteArray();
        } catch (AxisFault axisFault) {
            throw new AxisRabbitMQException("Unable to get the message formatter to use", axisFault);
        }
        return messageBody;
    }
}
