/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL
 * license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */

package com.mirth.connect.donkey.server.channel;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.mirth.connect.donkey.model.DonkeyException;
import com.mirth.connect.donkey.model.channel.ChannelState;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.QueueConnectorProperties;
import com.mirth.connect.donkey.model.channel.QueueConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.ContentType;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.Serializer;
import com.mirth.connect.donkey.server.StartException;
import com.mirth.connect.donkey.server.StopException;
import com.mirth.connect.donkey.server.channel.components.ResponseTransformer;
import com.mirth.connect.donkey.server.controllers.MessageController;
import com.mirth.connect.donkey.server.data.DonkeyDao;
import com.mirth.connect.donkey.server.data.DonkeyDaoFactory;
import com.mirth.connect.donkey.server.queue.ConnectorMessageQueue;
import com.mirth.connect.donkey.server.queue.ConnectorMessageQueueDataSource;
import com.mirth.connect.donkey.util.ThreadUtils;

public abstract class DestinationConnector extends Connector implements ConnectorInterface, Runnable {
    private Thread thread;
    private QueueConnectorProperties queueProperties;
    private ConnectorMessageQueue queue = new ConnectorMessageQueue();
    private String destinationName;
    private boolean enabled;
    private ResponseTransformer responseTransformer;
    private StorageSettings storageSettings = new StorageSettings();
    private ChannelState currentState = ChannelState.STOPPED;
    private boolean removeContentOnCompletion = false;
    private Logger logger = Logger.getLogger(getClass());

    public abstract ConnectorProperties getReplacedConnectorProperties(ConnectorMessage message);

    public abstract Response send(ConnectorProperties connectorProperties, ConnectorMessage message) throws InterruptedException;

    public ConnectorMessageQueue getQueue() {
        return queue;
    }

    public void setQueue(ConnectorMessageQueue connectorMessages) {
        this.queue = connectorMessages;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Deprecated
    public boolean isRunning() {
        return currentState != ChannelState.STOPPED && currentState != ChannelState.STOPPING;
    }

    @Override
    public void setConnectorProperties(ConnectorProperties connectorProperties) {
        super.setConnectorProperties(connectorProperties);

        if (connectorProperties instanceof QueueConnectorPropertiesInterface) {
            this.queueProperties = ((QueueConnectorPropertiesInterface) connectorProperties).getQueueConnectorProperties();
        }
    }

    public ResponseTransformer getResponseTransformer() {
        return responseTransformer;
    }

    public void setResponseTransformer(ResponseTransformer responseTransformer) {
        this.responseTransformer = responseTransformer;
    }

    public StorageSettings getStorageSettings() {
        return storageSettings;
    }

    public void setStorageSettings(StorageSettings storageSettings) {
        this.storageSettings = storageSettings;
    }

    public ChannelState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(ChannelState currentState) {
        this.currentState = currentState;
    }

    public boolean isRemoveContentOnCompletion() {
        return removeContentOnCompletion;
    }

    public void setRemoveContentOnCompletion(boolean removeContentOnCompletion) {
        this.removeContentOnCompletion = removeContentOnCompletion;
    }

    /**
     * Tells whether or not queueing is enabled
     */
    public boolean isQueueEnabled() {
        return (queueProperties != null && queueProperties.isQueueEnabled());
    }

    @Override
    public void start() throws StartException {
        setCurrentState(ChannelState.STARTING);

        if (isQueueEnabled()) {
            // set the queue data source if needed
            if (queue.getDataSource() == null) {
                queue.setDataSource(new ConnectorMessageQueueDataSource(getChannelId(), getMetaDataId(), Status.QUEUED));
            }

            // refresh the queue size from it's data source
            queue.updateSize();

            thread = new Thread(this);
            thread.start();
        }

        onStart();

        setCurrentState(ChannelState.STARTED);
    }

    @Override
    public void stop() throws StopException {
        setCurrentState(ChannelState.STOPPING);

        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopException("Failed to stop destination connector for channel: " + getChannelId(), e);
            }
        }

        onStop();

        setCurrentState(ChannelState.STOPPED);
    }

    @Override
    public void halt() throws StopException {
        setCurrentState(ChannelState.STOPPING);

        if (thread != null) {
            try {
                thread.interrupt();
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopException("Failed to stop destination connector for channel: " + getChannelId(), e);
            }
        }

        onStop();

        setCurrentState(ChannelState.STOPPED);
    }

    private MessageContent getSentContent(ConnectorMessage message, ConnectorProperties connectorProperties) {
        MessageContent sentContent = new MessageContent();
        sentContent.setChannelId(message.getChannelId());
        sentContent.setContentType(ContentType.SENT);
        sentContent.setEncrypted(false);
        sentContent.setMessageId(message.getMessageId());
        sentContent.setMetaDataId(message.getMetaDataId());
        // TODO: store the serializer as a class variable?
        sentContent.setContent(Donkey.getInstance().getSerializer().serialize(connectorProperties));

        return sentContent;
    }

    /**
     * Process a transformed message. Attempt to send the message unless the
     * destination connector is configured to immediately queue messages.
     * 
     * @return The status of the message at the end of processing. If the
     *         message was placed in the destination connector queue, then
     *         QUEUED is returned.
     * @throws InterruptedException
     */
    public void process(DonkeyDao dao, ConnectorMessage message, Status previousStatus) throws InterruptedException {
        ConnectorProperties connectorProperties = null;
        boolean attemptSend = (!isQueueEnabled() || queueProperties.isSendFirst());

        // we need to get the connector envelope if we will be attempting to send the message or if we are not regenerating the envelope on every send attempt        
        if (attemptSend || !queueProperties.isRegenerateTemplate()) {
            ThreadUtils.checkInterruptedStatus();

            // have the connector generate the connector envelope and store it in the message
            connectorProperties = getReplacedConnectorProperties(message);

            if (storageSettings.isStoreSent()) {
                ThreadUtils.checkInterruptedStatus();

                MessageContent sentContent = getSentContent(message, connectorProperties);
                message.setSent(sentContent);

                if (sentContent != null) {
                    ThreadUtils.checkInterruptedStatus();
                    dao.insertMessageContent(sentContent);
                }
            }

            if (attemptSend) {
                int retryCount = (queueProperties == null) ? 0 : queueProperties.getRetryCount();
                int sendAttempts = 0;
                Response response = null;
                Status responseStatus = null;

                do {
                    // pause for the given retry interval if this is not the first send attempt
                    if (sendAttempts > 0) {
                        Thread.sleep(queueProperties.getRetryIntervalMillis());
                    } else {
                        ThreadUtils.checkInterruptedStatus();
                    }

                    // have the connector send the message and return a response
                    response = handleSend(connectorProperties, message);
                    message.setSendAttempts(++sendAttempts);
                    fixResponseStatus(response);
                    responseStatus = response.getStatus();
                } while ((responseStatus == Status.ERROR || responseStatus == Status.QUEUED) && (sendAttempts - 1) < retryCount);

                // Insert errors if necessary
                if (StringUtils.isNotBlank(response.getError())) {
                    message.setErrors(response.getError());
                    dao.updateErrors(message);
                }

                afterSend(dao, message, response, previousStatus);
            } else {
                dao.updateStatus(message, previousStatus);
            }
        }
    }

    /**
     * Process a connector message with PENDING status
     * 
     * @throws InterruptedException
     */
    public void processPendingConnectorMessage(DonkeyDao dao, ConnectorMessage message) throws InterruptedException {
        Response response = Response.fromString(message.getResponse().getContent());
        runResponseTransformer(dao, message, response);
        afterResponse(dao, message, response, message.getStatus());
    }

    @Override
    public void run() {
        DonkeyDao dao = null;
        try {
            Serializer serializer = Donkey.getInstance().getSerializer();
            DonkeyDaoFactory daoFactory = getDaoFactory();
            ConnectorMessage connectorMessage = null;
            int retryIntervalMillis = queueProperties.getRetryIntervalMillis();
            long lastMessageId = -1;
            int sendAttempts = 0;

            do {
                connectorMessage = queue.peek();

                if (connectorMessage != null) {
                    try {
                        dao = daoFactory.getDao();
                        Status previousStatus = connectorMessage.getStatus();

                        if (connectorMessage.getMessageId() != lastMessageId) {
                            lastMessageId = connectorMessage.getMessageId();
                            sendAttempts = queueProperties.isSendFirst() ? 1 : 0;
                        } else {
                            // If the same message is still queued, allow some time before attempting it again.
                            Thread.sleep(retryIntervalMillis);
                        }

                        ConnectorProperties connectorProperties = null;

                        if (queueProperties.isRegenerateTemplate()) {
                            ThreadUtils.checkInterruptedStatus();
                            connectorProperties = getReplacedConnectorProperties(connectorMessage);
                            MessageContent sentContent = getSentContent(connectorMessage, connectorProperties);
                            connectorMessage.setSent(sentContent);

                            if (sentContent != null && storageSettings.isStoreSent()) {
                                ThreadUtils.checkInterruptedStatus();
                                dao.storeMessageContent(sentContent);
                            }
                        } else {
                            connectorProperties = (ConnectorProperties) serializer.deserialize(connectorMessage.getSent().getContent());
                        }

                        ThreadUtils.checkInterruptedStatus();
                        Response response = handleSend(connectorProperties, connectorMessage);
                        connectorMessage.setSendAttempts(++sendAttempts);
                        fixResponseStatus(response);

                        if (response == null) {
                            throw new RuntimeException("Received null response from destination " + destinationName + ".");
                        }

                        afterSend(dao, connectorMessage, response, previousStatus);

                        /*
                         * if the "remove content on completion" setting is enabled, we will need to
                         * retrieve a list of the other connector messages for this message id and
                         * check if the message is "completed"
                         */
                        if (removeContentOnCompletion) {
                            Map<Integer, ConnectorMessage> connectorMessages = dao.getConnectorMessages(getChannelId(), connectorMessage.getMessageId());

                            // update the map with the message that was just sent
                            connectorMessages.put(getMetaDataId(), connectorMessage);

                            if (MessageController.getInstance().isMessageCompleted(connectorMessages)) {
                                dao.deleteAllContent(getChannelId(), connectorMessage.getMessageId());
                            }
                        }

                        ThreadUtils.checkInterruptedStatus();
                        dao.commit(storageSettings.isDurable());

                        if (connectorMessage.getStatus() != Status.QUEUED) {
                            ThreadUtils.checkInterruptedStatus();

                            // We only peeked before, so this time actually remove the head of the queue, which is the message we just finished
                            queue.poll();

                            // Get the next message in the queue
                            connectorMessage = queue.peek();
                        }
                    } catch (RuntimeException e) {
                        logger.error("Error processing queued " + (connectorMessage != null ? connectorMessage.toString() : "message (null)") + " for channel " + getChannelId() + " (" + destinationName + ").", e);
                        dao.rollback();
                    } finally {
                        if (dao != null) {
                            dao.close();
                        }
                    }
                } else {
                    Thread.sleep(retryIntervalMillis);
                }
            } while (currentState == ChannelState.STARTED || currentState == ChannelState.STARTING);
        } catch (InterruptedException e) {
        } catch (Exception e) {
            logger.error(e);
        } finally {
            queue.clearBuffer();
            currentState = ChannelState.STOPPED;

            if (dao != null) {
                dao.close();
            }
        }
    }

    private Response handleSend(ConnectorProperties connectorProperties, ConnectorMessage message) throws InterruptedException {
        return send(connectorProperties, message);
    }

    private void afterSend(DonkeyDao dao, ConnectorMessage message, Response response, Status previousStatus) throws InterruptedException {
        MessageContent responseContent = new MessageContent(message.getChannelId(), message.getMessageId(), message.getMetaDataId(), ContentType.RESPONSE, response.toString(), false);

        if (storageSettings.isStoreResponse()) {
            ThreadUtils.checkInterruptedStatus();

            if (message.getResponse() != null) {
                dao.storeMessageContent(responseContent);
            } else {
                dao.insertMessageContent(responseContent);
            }
        }

        message.setResponse(responseContent);

        if (responseTransformer != null) {
            ThreadUtils.checkInterruptedStatus();
            message.setStatus(Status.PENDING);

            dao.updateStatus(message, previousStatus);
            dao.commit(storageSettings.isDurable());

            previousStatus = message.getStatus();

            runResponseTransformer(dao, message, response);
        } else {
            fixResponseStatus(response);
        }

        message.getResponseMap().put(destinationName, response);

        if (storageSettings.isStoreResponseMap()) {
            dao.updateResponseMap(message);
        }

        ThreadUtils.checkInterruptedStatus();
        afterResponse(dao, message, response, previousStatus);
    }

    private void runResponseTransformer(DonkeyDao dao, ConnectorMessage message, Response response) throws InterruptedException {
        ThreadUtils.checkInterruptedStatus();

        try {
            responseTransformer.doTransform(response);
        } catch (DonkeyException e) {
            logger.error("Error executing response transformer for channel " + message.getChannelId() + " (" + destinationName + ").", e);
            response.setStatus(Status.ERROR);
            response.setError(e.getFormattedError());
            message.setErrors(message.getErrors() != null ? message.getErrors() + System.getProperty("line.separator") + System.getProperty("line.separator") + e.getFormattedError() : e.getFormattedError());
            dao.updateErrors(message);
            return;
        }

        fixResponseStatus(response);

        /*
         * TRANSACTION: Process Response
         * - (if there is a response transformer) store the processed response
         * and the response map
         * - update message status based on the response status
         * - if there is a next destination in the chain, create it's message
         */

        // store the processed response in the message
        MessageContent processedResponse = new MessageContent(getChannelId(), message.getMessageId(), message.getMetaDataId(), ContentType.PROCESSED_RESPONSE, response.toString(), false);
        message.setProcessedResponse(processedResponse);

        if (storageSettings.isStoreProcessedResponse()) {
            ThreadUtils.checkInterruptedStatus();

            if (message.getProcessedResponse() != null) {
                dao.storeMessageContent(processedResponse);
            } else {
                dao.insertMessageContent(processedResponse);
            }
        }
    }

    private void afterResponse(DonkeyDao dao, ConnectorMessage connectorMessage, Response response, Status previousStatus) {
        // the response status from the response transformer should be one of: FILTERED, ERROR, SENT, or QUEUED
        connectorMessage.setStatus(response.getStatus());
        dao.updateStatus(connectorMessage, previousStatus);
        previousStatus = connectorMessage.getStatus();
    }

    private void fixResponseStatus(Response response) {
        if (response != null) {
            Status status = response.getStatus();

            if (status != Status.FILTERED && status != Status.ERROR && status != Status.SENT && status != Status.QUEUED) {
                // If the response is invalid for a final destination status, change the status to ERROR
                response.setStatus(Status.ERROR);
            } else if (!isQueueEnabled() && status == Status.QUEUED) {
                // If the status is QUEUED and queuing is disabled, change the status to ERROR
                response.setStatus(Status.ERROR);
            }
        }
    }
}
