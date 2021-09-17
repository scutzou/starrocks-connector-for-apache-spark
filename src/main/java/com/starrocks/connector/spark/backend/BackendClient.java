// Modifications Copyright 2021 StarRocks Limited.
//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.connector.spark.backend;

import com.starrocks.connector.spark.cfg.ConfigurationOptions;
import com.starrocks.connector.spark.exception.ConnectedFailedException;
import com.starrocks.connector.spark.exception.StarrocksException;
import com.starrocks.connector.spark.exception.StarrocksInternalException;
import com.starrocks.connector.spark.util.ErrorMessages;
import com.starrocks.connector.spark.cfg.Settings;
import com.starrocks.connector.spark.serialization.Routing;
import com.starrocks.connector.thrift.TStarrocksExternalService;
import com.starrocks.connector.thrift.TScanBatchResult;
import com.starrocks.connector.thrift.TScanCloseParams;
import com.starrocks.connector.thrift.TScanCloseResult;
import com.starrocks.connector.thrift.TScanNextBatchParams;
import com.starrocks.connector.thrift.TScanOpenParams;
import com.starrocks.connector.thrift.TScanOpenResult;
import com.starrocks.connector.thrift.TStatusCode;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to request Starrocks BE
 */
public class BackendClient {
    private static Logger logger = LoggerFactory.getLogger(BackendClient.class);

    private Routing routing;

    private TStarrocksExternalService.Client client;
    private TTransport transport;

    private boolean isConnected = false;
    private final int retries;
    private final int socketTimeout;
    private final int connectTimeout;

    public BackendClient(Routing routing, Settings settings) throws ConnectedFailedException {
        this.routing = routing;
        this.connectTimeout = settings.getIntegerProperty(ConfigurationOptions.STARROCKS_REQUEST_CONNECT_TIMEOUT_MS,
                ConfigurationOptions.STARROCKS_REQUEST_CONNECT_TIMEOUT_MS_DEFAULT);
        this.socketTimeout = settings.getIntegerProperty(ConfigurationOptions.STARROCKS_REQUEST_READ_TIMEOUT_MS,
                ConfigurationOptions.STARROCKS_REQUEST_READ_TIMEOUT_MS_DEFAULT);
        this.retries = settings.getIntegerProperty(ConfigurationOptions.STARROCKS_REQUEST_RETRIES,
                ConfigurationOptions.STARROCKS_REQUEST_RETRIES_DEFAULT);
        logger.trace("connect timeout set to '{}'. socket timeout set to '{}'. retries set to '{}'.",
                this.connectTimeout, this.socketTimeout, this.retries);
        open();
    }

    private void open() throws ConnectedFailedException {
        logger.debug("Open client to Starrocks BE '{}'.", routing);
        TException ex = null;
        for (int attempt = 0; !isConnected && attempt < retries; ++attempt) {
            logger.debug("Attempt {} to connect {}.", attempt, routing);
            TBinaryProtocol.Factory factory = new TBinaryProtocol.Factory();
            transport = new TSocket(routing.getHost(), routing.getPort(), socketTimeout, connectTimeout);
            TProtocol protocol = factory.getProtocol(transport);
            client = new TStarrocksExternalService.Client(protocol);
            try {
                logger.trace("Connect status before open transport to {} is '{}'.", routing, isConnected);
                if (!transport.isOpen()) {
                    transport.open();
                    isConnected = true;
                }
            } catch (TTransportException e) {
                logger.warn(ErrorMessages.CONNECT_FAILED_MESSAGE, routing, e);
                ex = e;
            }
            if (isConnected) {
                logger.info("Success connect to {}.", routing);
                break;
            }
        }
        if (!isConnected) {
            logger.error(ErrorMessages.CONNECT_FAILED_MESSAGE, routing);
            throw new ConnectedFailedException(routing.toString(), ex);
        }
    }

    private void close() {
        logger.trace("Connect status before close with '{}' is '{}'.", routing, isConnected);
        isConnected = false;
        if (null != client) {
            client = null;
        }
        if ((transport != null) && transport.isOpen()) {
            transport.close();
            logger.info("Closed a connection to {}.", routing);
        }
    }

    /**
     * Open a scanner for reading Starrocks data.
     *
     * @param openParams thrift struct to required by request
     * @return scan open result
     * @throws ConnectedFailedException throw if cannot connect to Starrocks BE
     */
    public TScanOpenResult openScanner(TScanOpenParams openParams) throws ConnectedFailedException {
        logger.debug("OpenScanner to '{}', parameter is '{}'.", routing, openParams);
        if (!isConnected) {
            open();
        }
        TException ex = null;
        for (int attempt = 0; attempt < retries; ++attempt) {
            logger.debug("Attempt {} to openScanner {}.", attempt, routing);
            try {
                TScanOpenResult result = client.open_scanner(openParams);
                if (result == null) {
                    logger.warn("Open scanner result from {} is null.", routing);
                    continue;
                }
                if (!TStatusCode.OK.equals(result.getStatus().getStatus_code())) {
                    logger.warn("The status of open scanner result from {} is '{}', error message is: {}.",
                            routing, result.getStatus().getStatus_code(), result.getStatus().getError_msgs());
                    continue;
                }
                return result;
            } catch (TException e) {
                logger.warn("Open scanner from {} failed.", routing, e);
                ex = e;
            }
        }
        logger.error(ErrorMessages.CONNECT_FAILED_MESSAGE, routing);
        throw new ConnectedFailedException(routing.toString(), ex);
    }

    /**
     * get next row batch from Starrocks BE
     *
     * @param nextBatchParams thrift struct to required by request
     * @return scan batch result
     * @throws ConnectedFailedException throw if cannot connect to Starrocks BE
     */
    public TScanBatchResult getNext(TScanNextBatchParams nextBatchParams) throws StarrocksException {
        logger.debug("GetNext to '{}', parameter is '{}'.", routing, nextBatchParams);
        if (!isConnected) {
            open();
        }
        TException ex = null;
        TScanBatchResult result = null;
        for (int attempt = 0; attempt < retries; ++attempt) {
            logger.debug("Attempt {} to getNext {}.", attempt, routing);
            try {
                result = client.get_next(nextBatchParams);
                if (result == null) {
                    logger.warn("GetNext result from {} is null.", routing);
                    continue;
                }
                if (!TStatusCode.OK.equals(result.getStatus().getStatus_code())) {
                    logger.warn("The status of get next result from {} is '{}', error message is: {}.",
                            routing, result.getStatus().getStatus_code(), result.getStatus().getError_msgs());
                    continue;
                }
                return result;
            } catch (TException e) {
                logger.warn("Get next from {} failed.", routing, e);
                ex = e;
            }
        }
        if (result != null && (TStatusCode.OK != (result.getStatus().getStatus_code()))) {
            logger.error(ErrorMessages.STARROCKS_INTERNAL_FAIL_MESSAGE, routing, result.getStatus().getStatus_code(),
                    result.getStatus().getError_msgs());
            throw new StarrocksInternalException(routing.toString(), result.getStatus().getStatus_code(),
                    result.getStatus().getError_msgs());
        }
        logger.error(ErrorMessages.CONNECT_FAILED_MESSAGE, routing);
        throw new ConnectedFailedException(routing.toString(), ex);
    }

    /**
     * close an scanner.
     *
     * @param closeParams thrift struct to required by request
     */
    public void closeScanner(TScanCloseParams closeParams) {
        logger.debug("CloseScanner to '{}', parameter is '{}'.", routing, closeParams);
        if (!isConnected) {
            try {
                open();
            } catch (ConnectedFailedException e) {
                logger.warn("Cannot connect to Starrocks BE {} when close scanner.", routing);
                return;
            }
        }
        for (int attempt = 0; attempt < retries; ++attempt) {
            logger.debug("Attempt {} to closeScanner {}.", attempt, routing);
            try {
                TScanCloseResult result = client.close_scanner(closeParams);
                if (result == null) {
                    logger.warn("CloseScanner result from {} is null.", routing);
                    continue;
                }
                if (!TStatusCode.OK.equals(result.getStatus().getStatus_code())) {
                    logger.warn("The status of get next result from {} is '{}', error message is: {}.",
                            routing, result.getStatus().getStatus_code(), result.getStatus().getError_msgs());
                    continue;
                }
                break;
            } catch (TException e) {
                logger.warn("Close scanner from {} failed.", routing, e);
            }
        }
        logger.info("CloseScanner to Starrocks BE '{}' success.", routing);
        close();
    }
}
