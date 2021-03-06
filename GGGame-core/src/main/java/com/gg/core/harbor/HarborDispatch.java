package com.gg.core.harbor;

import com.gg.common.KryoHelper;
import com.gg.common.StringUtils;
import com.gg.core.Async;
import com.gg.core.harbor.protocol.HarborOuterClass.HarborMessage;
import com.gg.core.harbor.protocol.HarborOuterClass.MessageType;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author guofeng.qin
 */
public class HarborDispatch {
    private static final Logger logger = LoggerFactory.getLogger(HarborDispatch.class);

    private AtomicInteger requestId = new AtomicInteger(0); // request id index
    private Map<Integer, HarborFutureTask> rmap = new ConcurrentHashMap<>();
    private Map<String, Class<?>> instanceCacheMap = new ConcurrentHashMap<>();
    private Map<String, MethodEntry> methodCacheMap = new ConcurrentHashMap<>();
    private Map<String, HarborStreamTunnel> harborMap = new HashMap<>();
    private Map<String, String> nameKeyMap = new HashMap<>();
    private Executor exepool;

    public HarborDispatch(Executor exepool) {
        this.exepool = exepool;
    }

    public void onCompleted(String identity) {}

    private void handleResponse(HarborMessage msg) {
        int rid = msg.getRid();
        HarborFutureTask future = rmap.remove(rid);
        if (future != null) {
            if (future.isAsync()) { // 异步调用，需要把逻辑引导到exepool中执行
                exepool.execute(() -> {
                    future.remoteFinish(msg.getPayload(0).toByteArray());
                });
            } else { // 同步调用，直接设置完成状态
                future.remoteFinish(msg.getPayload(0).toByteArray());
            }
        } else {
            // TODO ... 响应对应的请求找不到，如何处理
        }
    }

    // TODO ... 这个方法多线程触发的一瞬间会有线程问题，待解决
    private MethodEntry getMethodWith(String instanceName, String methodName) {
        String tag = StringUtils.join(":", instanceName, methodName);
        MethodEntry methodEntry = methodCacheMap.get(tag);
        if (methodEntry == null) { // cache miss
            Class<?> instance = instanceCacheMap.get(instanceName);
            if (instance == null) {
                try {
                    instance = Class.forName(instanceName);
                    if (instance != null) {
                        instanceCacheMap.putIfAbsent(instanceName, instance);
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
            instance = instanceCacheMap.get(instanceName);
            if (instance != null) {
                Method ms[] = ReflectionUtils.getAllDeclaredMethods(instance);
                Method method = null;
                if (ms != null) {
                    for (Method m : ms) {
                        if (m.getName().equals(methodName)) {
                            method = m;
                            break;
                        }
                    }
                }
                if (method != null) {
                    Object target = GGHarbor.getCtx().getBean(instance);
                    ReflectionUtils.makeAccessible(method);
                    methodEntry = new MethodEntry(method, target);
                    methodCacheMap.put(tag, methodEntry);
                }

            }
        }

        return methodEntry;
    }

    private void invokeMethodInExepoll(Runnable runnable) {
        exepool.execute(runnable);
    }

    private void handleMessage(HarborMessage msg) {
        // TODO ... 异常处理
        // 反射调用对应的方法
        String instanceName = msg.getInstance();
        String methodName = msg.getMethod();
        MethodEntry methodEntry = getMethodWith(instanceName, methodName);
        if (methodEntry != null) {
            Method method = methodEntry.method;
            // 反序列化参数
            List<ByteString> payloads = msg.getPayloadList();
            List<Object> params = new ArrayList<Object>();
            if (payloads != null) {
                for (ByteString payload : payloads) {
                    byte[] value = payload.toByteArray();
                    Object param = KryoHelper.readClassAndObject(value);
                    params.add(param);
                }
            }
            invokeMethodInExepoll(() -> {
                try {
                    Object result = method.invoke(methodEntry.target, params.toArray(new Object[0]));
                    if (msg.getType() == MessageType.Request) { // need response
                        Async asyncs[] = method.getAnnotationsByType(Async.class);
                        // async function
                        if (asyncs != null && asyncs.length > 0) {
                            HarborFutureTask future = (HarborFutureTask) result;
                            future.addCallback((obj) -> {
                                post(msg.getSource().getName(),
                                        HarborHelper.buildHarborResponse(msg.getSid(), msg.getRid(), obj));
                            });
                        } else { // normal function
                            post(msg.getSource().getName(),
                                    HarborHelper.buildHarborResponse(msg.getSid(), msg.getRid(), result));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void onMessage(String identity, HarborMessage msg) {
        if (msg.getType() == MessageType.Response) { // handle response
            handleResponse(msg);
        } else {
            handleMessage(msg);
        }
    }

    public void onError(String identity, Throwable error) {
        logger.error(identity + " error...", error);
        removeRemote(identity);
    }

    public void removeRemote(String identity) {
        if (harborMap.containsKey(identity)) {
            harborMap.remove(identity);
        }
        if (nameKeyMap.containsValue(identity)) {
            String key = null;
            for (Map.Entry<String, String> entry : nameKeyMap.entrySet()) {
                if (entry.getValue().equals(identity)) {
                    key = entry.getKey();
                    break;
                }
            }
            if (key != null) {
                nameKeyMap.remove(key);
            }
        }
    }

    public void remoteHarborHandshake(String service, String key, HarborStreamTunnel tunnel) {
        if (harborMap.containsKey(key)) {
            // TODO ...
        }
        harborMap.put(key, tunnel);
        nameKeyMap.put(service, key);
    }

    public void post(String service, HarborMessage msg) {
        if (!nameKeyMap.containsKey(service)) {
            // throw new RuntimeException("remote service not found");
            System.exit(0);
        }
        harborMap.get(nameKeyMap.get(service)).sendToRemote(msg);
    }

    public void call(String service, HarborMessage msg, HarborFutureTask future) {
        if (!nameKeyMap.containsKey(service)) {
            throw new RuntimeException("remote service not found");
        }
        int reqid = requestId.incrementAndGet();
        msg = msg.toBuilder().setRid(reqid).build();
        rmap.put(reqid, future);
        harborMap.get(nameKeyMap.get(service)).sendToRemote(msg);
    }

    static class MethodEntry {
        public Method method;
        public Object target;

        public MethodEntry(Method method, Object target) {
            this.method = method;
            this.target = target;
        }
    }
}
