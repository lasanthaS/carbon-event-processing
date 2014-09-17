/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.carbon.event.processor.storm.topology.component;

import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.tuple.Tuple;
import org.apache.log4j.Logger;
import org.wso2.carbon.databridge.commons.thrift.utils.HostAddressFinder;
import org.wso2.carbon.event.processor.storm.common.transport.client.TCPEventPublisher;
import org.wso2.carbon.event.processor.storm.common.management.client.ManagerServiceClient;
import org.wso2.carbon.event.processor.storm.common.management.client.ManagerServiceClientCallback;
import org.wso2.carbon.event.processor.storm.topology.util.SiddhiUtils;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.config.SiddhiConfiguration;
import org.wso2.siddhi.core.util.collection.Pair;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

/**
 * Publish events processed by Siddhi engine to CEP publisher
 */
public class EventPublisherBolt extends BaseBasicBolt implements ManagerServiceClientCallback {
    private transient Logger log = Logger.getLogger(EventPublisherBolt.class);
    /**
     * Exported stream IDs. Must declare output filed for each exported stream
     */
    private String[] exportedStreamIDs;
    /**
     * All stream definitions processed
     */
    private String[] streamDefinitions;
    /**
     * Queries processed by Siddhi engine. Required to extract field definitions of implicitly declared stream
     * definitions
     */
    private String[] queries;
    /**
     * Keep track of relevant data bridge stream id for a given Siddhi stream id
     */
    private Map<String, org.wso2.carbon.databridge.commons.StreamDefinition> siddhiStreamIdToDataBridgeStreamMap
            = new HashMap<String, org.wso2.carbon.databridge.commons.StreamDefinition>();

    private transient TCPEventPublisher TCPEventPublisher = null;

    private String executionPlanName;

    private String logPrefix;

    private int tenantId = -1234;
    /**
     * CEP Manager service host
     */
    private String cepManagerHost;
    /**
     * CEP manager service port
     */
    private int cepManagerPort;

    private String trustStorePath;
    private String trustStorePassword;
    private int eventCount;
    private long batchStartTime;
    private SiddhiManager siddhiManager;

    public EventPublisherBolt(String cepManagerHost, int cepManagerPort, String trustStorePath, String[] streamDefinitions, String[] queries, String[] exportedStreamIDs, String executionPlanName) {
        this.exportedStreamIDs = exportedStreamIDs;
        this.streamDefinitions = streamDefinitions;
        this.cepManagerHost = cepManagerHost;
        this.cepManagerPort = cepManagerPort;
        this.queries = queries;
        this.executionPlanName = executionPlanName;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = "wso2carbon";
        this.logPrefix = "{" + executionPlanName + ":" + tenantId + "} - ";
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector basicOutputCollector) {
        if (siddhiManager == null) {
            init(); // TODO : Understand why this init is required
        }

        if (TCPEventPublisher != null) {
            //TODO Do we need to keep databridge stream definitions inside the bolt??
            org.wso2.carbon.databridge.commons.StreamDefinition databridgeStream = siddhiStreamIdToDataBridgeStreamMap.get(tuple.getSourceStreamId());

            if (databridgeStream != null) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug(logPrefix + "Event published to CEP Publisher =>" + tuple.toString());
                    }
                    if (++eventCount % 10000 == 0) {
                        double timeSpentInSecs = (System.currentTimeMillis() - batchStartTime) / 1000.0D;
                        double throughput = 10000 / timeSpentInSecs;
                        log.info("Processed 10000 events in " + timeSpentInSecs + " seconds, throughput : " + throughput + " events/sec");
                        eventCount = 0;
                        batchStartTime = System.currentTimeMillis();
                    }

                    TCPEventPublisher.sendEvent(tuple.getSourceStreamId(), tuple.getValues().toArray());
                } catch (IOException e) {
                    log.error(logPrefix + "Error while publishing event to CEP publisher", e);
                }
            } else {
                log.warn(logPrefix + "Tuple received for unknown stream " + tuple.getSourceStreamId() + ". Discarding event : " + tuple.toString());
            }
        } else {
            log.warn("Dropping the event since the data publisher is not yet initialized for " + executionPlanName + ":" + tenantId);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {

    }

    @Override
    public void prepare(Map stormConf, TopologyContext context) {
        super.prepare(stormConf, context);
        init();
    }

    private void init() {
        // TODO : remove siddhi related stream definitions. Use only exported streams
        log = Logger.getLogger(EventPublisherBolt.class);
        siddhiManager = new SiddhiManager(new SiddhiConfiguration());
        eventCount = 0;
        batchStartTime = System.currentTimeMillis();
        System.setProperty("javax.net.ssl.trustStore", trustStorePath);
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);


        if (streamDefinitions != null) {
            for (String definition : streamDefinitions) {
                if (definition.contains("define stream")) {
                    siddhiManager.defineStream(definition);
                } else if (definition.contains("define partition")) {
                    siddhiManager.definePartition(definition);
                } else {
                    throw new RuntimeException("Invalid definition : " + definition);
                }
            }
        }

        if (queries != null) {
            for (String query : queries) {
                siddhiManager.addQuery(query);
            }
        }

        String thisHostIp = null;
        try {
            thisHostIp = HostAddressFinder.findAddress("localhost");
        } catch (SocketException e) {
            log.error("Cannot find IP address of the host");
        }

        for (String streamDefinitionId : exportedStreamIDs) {
            StreamDefinition siddhiStreamDefinition = siddhiManager.getStreamDefinition(streamDefinitionId);
            org.wso2.carbon.databridge.commons.StreamDefinition databridgeStreamDefinition = SiddhiUtils.toFlatDataBridgeStreamDefinition(siddhiStreamDefinition);
            siddhiStreamIdToDataBridgeStreamMap.put(siddhiStreamDefinition.getStreamId(), databridgeStreamDefinition);
        }
        // Connecting to CEP manager service to get details of CEP publisher
        ManagerServiceClient client = new ManagerServiceClient(cepManagerHost, cepManagerPort, this);
        client.getCepPublisher(executionPlanName, tenantId, 30, thisHostIp);
    }

    @Override
    public void onResponseReceived(Pair<String, Integer> endpoint) {
        synchronized (this) {
            try {
                TCPEventPublisher = new TCPEventPublisher(endpoint.getOne() + ":" + endpoint.getTwo());
                for (String siddhiStreamId : exportedStreamIDs) {
                    if (log.isDebugEnabled()) {
                        log.debug(logPrefix + "EventPublisherBolt adding stream definition to client for exported Siddhi stream: " + siddhiStreamId);
                    }
                    TCPEventPublisher.addStreamDefinition(siddhiManager.getStreamDefinition(siddhiStreamId));
                }
                log.info(logPrefix + " EventPublisherBolt connecting to CEP publisher at " + endpoint.getOne() + ":" + endpoint.getTwo());
            } catch (IOException e) {
                log.error("Error while creating event client:" + e.getMessage(), e);
            }
        }
    }
}