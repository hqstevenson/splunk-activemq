/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pronoia.splunk.jms;

import com.pronoia.splunk.eventcollector.SplunkMDCHelper;
import com.pronoia.splunk.eventcollector.util.NamedThreadFactory;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;
import javax.jms.Message;


/**
 * Receives JMS Messages from an ActiveMQ broker in the same JVM and delivers them to Splunk using the HTTP Event
 * Collector.
 *
 * This class is intended to be run on a schedule with a fixed delay.
 */
public class SplunkJmsConsumerRunnable extends SplunkJmsConsumerSupport implements Runnable {
    long receiveTimeoutMillis = 15000;
    long initialDelaySeconds = 5;
    long delaySeconds = 60;
    /**
     * Start the JMS Consumer and process messages.
     *
     * TODO:  Fixup error handling
     */
    ScheduledExecutorService consumerExecutor;

    Date startTime;
    Date stopTime;

    public SplunkJmsConsumerRunnable(String destinationName) {
        super(destinationName, false);
    }

    public SplunkJmsConsumerRunnable(String destinationName, boolean useTopic) {
        super(destinationName, useTopic);
    }

    public boolean isRunning() {
        return consumerExecutor != null && !(consumerExecutor.isShutdown() || consumerExecutor.isTerminated());
    }

    public Date getStartTime() {
        return startTime;
    }

    public Date getStopTime() {
        return stopTime;
    }

    public void start() {
        try (SplunkMDCHelper helper = createMdcHelper()) {
            log.info("Starting JMS consumer for {}", destinationName);
            verifyConfiguration();

            NamedThreadFactory threadFactory = new NamedThreadFactory(this.getClass().getSimpleName() + "{" + destinationName + "}");
            consumerExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);

            consumerExecutor.scheduleWithFixedDelay(this, initialDelaySeconds, delaySeconds, TimeUnit.SECONDS);
            consumedMessageCount = 0;
            startTime = new Date();
        }
    }

    public void stop() {
        try (SplunkMDCHelper helper = createMdcHelper()) {
            if (consumerExecutor != null) {
                log.info("Shutting-down executor service");
                consumerExecutor.shutdownNow();
                log.info("Executor service shutdown");
                consumerExecutor = null;
                stopTime = new Date();
            }
        }
    }

    public void restart() {
        stop();
        try {
            Thread.sleep(getInitialDelaySeconds());
            start();
        } catch (InterruptedException interruptedEx) {
            log.warn("Restart was interrupted - consumer will not be restarted", interruptedEx);
        }
    }



    public long getReceiveTimeoutMillis() {
        return receiveTimeoutMillis;
    }

    public void setReceiveTimeoutMillis(long receiveTimeoutMillis) {
        this.receiveTimeoutMillis = receiveTimeoutMillis;
    }

    public long getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public void setInitialDelaySeconds(long initialDelaySeconds) {
        this.initialDelaySeconds = initialDelaySeconds;
    }

    public long getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(long delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    /**
     * Read JMS Messages.
     */
    public void run() {
        try (SplunkMDCHelper helper = createMdcHelper()) {
            log.debug("Entering message processing loop for consumer {}", destinationName);

            if (!Thread.currentThread().isInterrupted()) {
                if (!createConnection(false)) {
                    return;
                }
                try {
                    createConsumer();
                    startConnection();

                    while (isConnectionStarted() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Message message = consumer.receive(receiveTimeoutMillis);
                            if (message != null) {
                                sendMessageToSplunk(message);
                            }
                        } catch (JMSException jmsEx) {
                            Throwable cause = jmsEx.getCause();
                            if (cause != null && cause instanceof InterruptedException) {
                                // If we're still supposed to be scheduled, re-throw the exception; otherwise just log it
                                cleanup(false);
                                if (log.isDebugEnabled()) {
                                    final String debugMessageFormat = "Consumer.receive(%d) call interrupted in processing loop for %s - stopping consumer";
                                    final String debugMessage = String.format(debugMessageFormat, receiveTimeoutMillis, destinationName);
                                    log.debug(debugMessage, jmsEx);
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    String infoMessage = String.format("Exception encountered in processing loop for %s - stopping consumer", destinationName, ex);
                    log.warn(infoMessage, ex);
                } finally {
                    log.info("JMS consumer for {} stopped", destinationName);
                    stopConnection();
                }
            }
        }
    }

}
