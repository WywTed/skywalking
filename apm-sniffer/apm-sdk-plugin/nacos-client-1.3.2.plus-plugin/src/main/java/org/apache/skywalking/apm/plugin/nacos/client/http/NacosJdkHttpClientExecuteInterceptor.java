/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.nacos.client.http;

import com.alibaba.nacos.common.http.client.response.JdkHttpClientResponse;
import com.alibaba.nacos.common.model.RequestHttpEntity;
import com.alibaba.nacos.common.utils.JacksonUtils;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;

public class NacosJdkHttpClientExecuteInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) {
        URI uri = (URI) allArguments[0];
        String methodName = (String) allArguments[1];
        RequestHttpEntity requestEntity = (RequestHttpEntity) allArguments[2];
        ContextCarrier contextCarrier = new ContextCarrier();
        AbstractSpan span = ContextManager.createExitSpan(getPath(uri), contextCarrier, getPeer(uri));
        span.setComponent(ComponentsDefine.JDK_HTTP);
        Tags.HTTP.METHOD.set(span, methodName);
        Tags.URL.set(span, uri.toString());
        SpanLayer.asHttp(span);
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            requestEntity.getHeaders().addParam(next.getHeadKey(), next.getHeadValue());
        }
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        if (ret != null) {
            JdkHttpClientResponse response = (JdkHttpClientResponse) ret;
            int statusCode = response.getStatusCode();
            AbstractSpan span = ContextManager.activeSpan();
            if (statusCode >= 400) {
                span.errorOccurred();
                Tags.STATUS_CODE.set(span, Integer.toString(statusCode));
            }
            if (!HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS && span.isProfiling()) {
                collectHttpParam(allArguments, span);
            }
        }
        ContextManager.stopSpan();
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        AbstractSpan span = ContextManager.activeSpan();
        span.log(t);
    }

    private String getPeer(URI url) {
        String host = url.getHost();
        if (url.getPort() > 0) {
            return host + ":" + url.getPort();
        }
        return host;
    }

    private String getPath(URI uri) {
        return StringUtil.isEmpty(uri.getPath()) ? "/" : uri.getPath();
    }

    private void collectHttpParam(Object[] args, AbstractSpan span) {
        URI uri = (URI)args[0];
        String tagValue = uri.getQuery();
        RequestHttpEntity entity = (RequestHttpEntity) args[2];
        if (StringUtil.isNotEmpty(tagValue)) {
            if (entity != null) {
                tagValue += entity.getQuery().toQueryUrl();
            }
            tagValue = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD > 0 ?
                    StringUtil.cut(tagValue, HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD) :
                    tagValue;
            Tags.HTTP.PARAMS.set(span, tagValue);
        }
        if (entity != null) {
            Map<String, String> headers = entity.getHeaders().getHeader();
            Tags.HTTP.HEADERS.set(span, headers.toString());
            if (entity.getBody() != null) {
                Tags.HTTP.BODY.set(span, JacksonUtils.toJson(entity.getBody()));
            }
        }
    }
}

