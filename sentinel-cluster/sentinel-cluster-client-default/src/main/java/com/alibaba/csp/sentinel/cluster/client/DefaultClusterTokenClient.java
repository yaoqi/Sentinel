/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.cluster.client;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.csp.sentinel.cluster.ClusterConstants;
import com.alibaba.csp.sentinel.cluster.ClusterTransportClient;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenServerDescriptor;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfigManager;
import com.alibaba.csp.sentinel.cluster.client.config.ServerChangeObserver;
import com.alibaba.csp.sentinel.cluster.log.ClusterClientStatLogUtil;
import com.alibaba.csp.sentinel.cluster.request.ClusterRequest;
import com.alibaba.csp.sentinel.cluster.request.data.FlowRequestData;
import com.alibaba.csp.sentinel.cluster.request.data.ParamFlowRequestData;
import com.alibaba.csp.sentinel.cluster.response.ClusterResponse;
import com.alibaba.csp.sentinel.cluster.response.data.FlowTokenResponseData;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.StringUtil;

/**
 * Default implementation of {@link ClusterTokenClient}.
 *
 * @author Eric Zhao
 * @since 1.4.0
 */
public class DefaultClusterTokenClient implements ClusterTokenClient {

    private ClusterTransportClient transportClient;
    private TokenServerDescriptor serverDescriptor;

    private final AtomicBoolean shouldStart = new AtomicBoolean(false);

    public DefaultClusterTokenClient() {
        ClusterClientConfigManager.addServerChangeObserver(new ServerChangeObserver() {
            @Override
            public void onRemoteServerChange(ClusterClientConfig clusterClientConfig) {
                changeServer(clusterClientConfig);
            }
        });
        // TODO: check here, who should start the client?
        initNewConnection();
    }

    private boolean serverEqual(TokenServerDescriptor descriptor, ClusterClientConfig config) {
        if (descriptor == null || config == null) {
            return false;
        }
        return descriptor.getHost().equals(config.getServerHost()) && descriptor.getPort() == config.getServerPort();
    }

    private void initNewConnection() {
        if (transportClient != null) {
            return;
        }
        String host = ClusterClientConfigManager.getServerHost();
        int port = ClusterClientConfigManager.getServerPort();
        if (StringUtil.isBlank(host) || port <= 0) {
            return;
        }

        try {
            this.transportClient = new NettyTransportClient(host, port);
            this.serverDescriptor = new TokenServerDescriptor(host, port);
            RecordLog.info("[DefaultClusterTokenClient] New client created: " + serverDescriptor);
        } catch (Exception ex) {
            RecordLog.warn("[DefaultClusterTokenClient] Failed to initialize new token client", ex);
        }
    }

    private void changeServer(/*@Valid*/ ClusterClientConfig config) {
        if (serverEqual(serverDescriptor, config)) {
            return;
        }
        try {
            // TODO: what if the client is pending init?
            if (transportClient != null) {
                transportClient.stop();
            }
            // Replace with new, even if the new client is not ready.
            this.transportClient = new NettyTransportClient(config);
            this.serverDescriptor = new TokenServerDescriptor(config.getServerHost(), config.getServerPort());
            startClientIfScheduled();
            RecordLog.info("[DefaultClusterTokenClient] New client created: " + serverDescriptor);
        } catch (Exception ex) {
            RecordLog.warn("[DefaultClusterTokenClient] Failed to change remote token server", ex);
        }
    }

    private void startClientIfScheduled() throws Exception {
        if (shouldStart.get()) {
            if (transportClient != null) {
                transportClient.start();
            }
        }
    }

    private void stopClientIfStarted() throws Exception {
        if (shouldStart.get()) {
            if (transportClient != null) {
                transportClient.stop();
            }
        }
    }

    @Override
    public void start() throws Exception {
        if (shouldStart.compareAndSet(false, true)) {
            startClientIfScheduled();
        }
    }

    @Override
    public void stop() throws Exception {
        if (shouldStart.compareAndSet(true, false)) {
            stopClientIfStarted();
        }
    }

    @Override
    public TokenServerDescriptor currentServer() {
        return serverDescriptor;
    }

    @Override
    public TokenResult requestToken(Long flowId, int acquireCount, boolean prioritized) {
        if (notValidRequest(flowId, acquireCount)) {
            return badRequest();
        }
        FlowRequestData data = new FlowRequestData().setCount(acquireCount)
            .setFlowId(flowId).setPriority(prioritized);
        ClusterRequest<FlowRequestData> request = new ClusterRequest<>(ClusterConstants.MSG_TYPE_FLOW, data);
        try {
            return sendTokenRequest(request);
        } catch (Exception ex) {
            ClusterClientStatLogUtil.log(ex.getMessage());
            return new TokenResult(TokenResultStatus.FAIL);
        }
    }

    @Override
    public TokenResult requestParamToken(Long flowId, int acquireCount, Collection<Object> params) {
        if (notValidRequest(flowId, acquireCount) || params == null || params.isEmpty()) {
            return badRequest();
        }
        ParamFlowRequestData data = new ParamFlowRequestData().setCount(acquireCount)
            .setFlowId(flowId).setParams(params);
        ClusterRequest<ParamFlowRequestData> request = new ClusterRequest<>(ClusterConstants.MSG_TYPE_PARAM_FLOW, data);
        try {
            return sendTokenRequest(request);
        } catch (Exception ex) {
            ClusterClientStatLogUtil.log(ex.getMessage());
            return new TokenResult(TokenResultStatus.FAIL);
        }
    }

    private TokenResult sendTokenRequest(ClusterRequest request) throws Exception {
        if (transportClient == null) {
            RecordLog.warn("[DefaultClusterTokenClient] Client not created, please check your config for cluster client");
            return clientFail();
        }
        ClusterResponse response = transportClient.sendRequest(request);
        TokenResult result = new TokenResult(response.getStatus());
        if (response.getData() != null) {
            FlowTokenResponseData responseData = (FlowTokenResponseData)response.getData();
            result.setRemaining(responseData.getRemainingCount())
                .setWaitInMs(responseData.getWaitInMs());
        }
        return result;
    }

    private boolean notValidRequest(Long id, int count) {
        return id == null || id <= 0 || count <= 0;
    }

    private TokenResult badRequest() {
        return new TokenResult(TokenResultStatus.BAD_REQUEST);
    }

    private TokenResult clientFail() {
        return new TokenResult(TokenResultStatus.FAIL);
    }
}