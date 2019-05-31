package com.github.kubatatami.judonetworking.internals;

import android.util.Pair;

import com.github.kubatatami.judonetworking.AsyncResult;
import com.github.kubatatami.judonetworking.CacheInfo;
import com.github.kubatatami.judonetworking.Endpoint;
import com.github.kubatatami.judonetworking.Request;
import com.github.kubatatami.judonetworking.adapters.JudoAdapter;
import com.github.kubatatami.judonetworking.annotations.LocalCache;
import com.github.kubatatami.judonetworking.annotations.NamePrefix;
import com.github.kubatatami.judonetworking.annotations.NameSuffix;
import com.github.kubatatami.judonetworking.annotations.RequestMethod;
import com.github.kubatatami.judonetworking.annotations.SingleCall;
import com.github.kubatatami.judonetworking.batches.Batch;
import com.github.kubatatami.judonetworking.exceptions.CancelException;
import com.github.kubatatami.judonetworking.exceptions.ConnectionException;
import com.github.kubatatami.judonetworking.exceptions.JudoException;
import com.github.kubatatami.judonetworking.exceptions.ParseException;
import com.github.kubatatami.judonetworking.internals.batches.BatchProgressObserver;
import com.github.kubatatami.judonetworking.internals.cache.CacheMethod;
import com.github.kubatatami.judonetworking.internals.requests.RequestImpl;
import com.github.kubatatami.judonetworking.internals.results.CacheResult;
import com.github.kubatatami.judonetworking.internals.results.ErrorResult;
import com.github.kubatatami.judonetworking.internals.results.RequestResult;
import com.github.kubatatami.judonetworking.internals.results.RequestSuccessResult;
import com.github.kubatatami.judonetworking.internals.stats.TimeStat;
import com.github.kubatatami.judonetworking.logs.ErrorLogger;
import com.github.kubatatami.judonetworking.logs.JudoLogger;
import com.github.kubatatami.judonetworking.utils.ReflectionCache;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class RequestProxy implements InvocationHandler, AsyncResult {

    protected final EndpointImpl rpc;

    protected boolean batchEnabled = false;

    protected boolean batchFatal = true;

    protected final List<RequestImpl> batchRequests = new ArrayList<>();

    protected EndpointImpl.BatchMode mode = EndpointImpl.BatchMode.NONE;

    protected boolean cancelled, done, running;

    protected Batch<?> batchCallback;

    private long startTimeMillis;

    private long endTimeMillis;

    private long totalTimeMillis;

    public RequestProxy(EndpointImpl rpc, EndpointImpl.BatchMode mode, Batch<?> batchCallback) {
        this.rpc = rpc;
        this.mode = mode;
        this.batchCallback = batchCallback;
        batchEnabled = (mode == EndpointImpl.BatchMode.MANUAL);
    }

    protected final Runnable batchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(rpc.getProtocolController().getAutoBatchTime());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (batchRequests.size() == 1) {
                RequestImpl request;
                synchronized (batchRequests) {
                    request = batchRequests.get(0);
                    batchRequests.clear();
                    batchEnabled = false;
                }
                Future<?> future = rpc.getExecutorService().submit(request);
                request.setFuture(future);
            } else {
                callBatch();
            }
        }
    };

    public void setBatchFatal(boolean batchFatal) {
        this.batchFatal = batchFatal;
    }


    public static String createMethodName(Method method, RequestMethod ann) {
        NamePrefix namePrefix = ReflectionCache.getAnnotation(method.getDeclaringClass(), NamePrefix.class);
        NameSuffix nameSuffix = ReflectionCache.getAnnotation(method.getDeclaringClass(), NameSuffix.class);
        String name;
        if (ann != null && !("".equals(ann.name()))) {
            name = ann.name();
        } else {
            name = method.getName();
        }
        if (namePrefix != null) {
            name = namePrefix.value() + name;
        }
        if (nameSuffix != null) {
            name += nameSuffix.value();
        }
        return name;
    }

    public static StackTraceElement getExternalStacktrace(StackTraceElement[] stackTrace) {
        String packageName = RequestProxy.class.getPackage().getName();
        boolean current = false;
        for (StackTraceElement element : stackTrace) {
            if (!current && element.getClassName().contains(packageName)) {
                current = true;
            } else if (current && !element.getClassName().contains(packageName)
                    && !element.getClassName().contains("$Proxy")
                    && !element.getClassName().contains("java.lang.reflect.Proxy")) {
                return element;
            }
        }
        return stackTrace[0];
    }

    protected AsyncResult performAsyncRequest(RequestImpl request) {
        synchronized (batchRequests) {
            if (batchEnabled) {
                request.setBatchFatal(batchFatal);
                batchRequests.add(request);
                batchEnabled = true;
                return null;
            } else {
                if (mode == EndpointImpl.BatchMode.AUTO) {
                    batchRequests.add(request);
                    batchEnabled = true;
                    rpc.getExecutorService().execute(batchRunnable);
                } else {
                    Future<?> future = rpc.getExecutorService().submit(request);
                    request.setFuture(future);
                }
                return request;
            }
        }
    }


    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        RequestImpl request = null;
        try {
            RequestMethod ann = ReflectionCache.getAnnotation(m, RequestMethod.class);
            if (ann != null) {
                String name = createMethodName(m, ann);
                int timeout = rpc.getRequestConnector().getMethodTimeout();


                if ((rpc.getDebugFlags() & Endpoint.REQUEST_LINE_DEBUG) > 0) {
                    try {
                        StackTraceElement stackTraceElement = getExternalStacktrace(Thread.currentThread().getStackTrace());
                        if (batchEnabled && mode == EndpointImpl.BatchMode.MANUAL) {
                            JudoLogger.log("In batch request " + name + " from " +
                                    stackTraceElement.getClassName() +
                                    "(" + stackTraceElement.getFileName() + ":" + stackTraceElement.getLineNumber() + ")", JudoLogger.LogLevel.DEBUG);
                        } else {
                            JudoLogger.log("Request " + name + " from " +
                                    stackTraceElement.getClassName() +
                                    "(" + stackTraceElement.getFileName() + ":" + stackTraceElement.getLineNumber() + ")", JudoLogger.LogLevel.DEBUG);
                        }
                    } catch (Exception ex) {
                        JudoLogger.log("Can't log stacktrace", JudoLogger.LogLevel.ASSERT);
                    }
                }

                if (ann.timeout() != 0) {
                    timeout = ann.timeout();
                }

                Type returnType = m.getGenericReturnType();
                if (!ann.async()) {
                    request = new RequestImpl(rpc, m, name, ann, args, returnType,
                            timeout, null, rpc.getProtocolController().getAdditionalRequestData());
                    ann.modifier().newInstance().modify(request);
                    rpc.filterNullArgs(request);
                    if (request.getSingleCall() != null) {
                        throw new JudoException("SingleCall is not supported on no async method.");
                    }
                    rpc.startRequest(request);
                    return rpc.getRequestConnector().call(request);
                } else {
                    request = new RequestImpl(rpc, m, name, ann, timeout, rpc.getProtocolController().getAdditionalRequestData());
                    final RequestImpl finalRequest = request;
                    Runnable run = new Runnable() {
                        @Override
                        public void run() {
                            finalRequest.reset();
                            rpc.startRequest(finalRequest);
                            performAsyncRequest(finalRequest);
                        }
                    };
                    MethodInfo methodInfo = getMethodInfo(returnType, args, ReflectionCache.getGenericParameterTypes(m), run);
                    request.setArgs(methodInfo.getArgs());
                    request.setReturnType(methodInfo.getResultType());
                    request.setCallback(methodInfo.getCallback());
                    ann.modifier().newInstance().modify(request);
                    rpc.filterNullArgs(request);
                    if (registerSingleCallMethod(m, request, name)) {
                        return request;
                    }
                    if (methodInfo.isRunImmediately()) run.run();
                    return methodInfo.getReturnObject() == null ? request : methodInfo.getReturnObject();
                }
            } else {
                try {
                    return m.invoke(this, args);
                } catch (IllegalArgumentException e) {
                    throw new JudoException("No @RequestMethod on " + m.getName());
                }
            }
        } catch (final JudoException e) {
            final RequestImpl finalRequest = request;
            if (rpc.getErrorLoggers().size() == 0 && !(e instanceof CancelException) && !finalRequest.isCancelled()) {
                rpc.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        for (ErrorLogger errorLogger : rpc.getErrorLoggers()) {
                            errorLogger.onError(e, finalRequest);
                        }
                    }
                });
            }
            throw e;
        }
    }

    private boolean registerSingleCallMethod(Method m, RequestImpl request, String name) {
        if (request.getSingleCall() != null) {
            if (rpc.getSingleCallMethods().containsKey(CacheMethod.getMethodId(m))) {
                SingleCall.SingleMode mode = request.getSingleCall().mode();

                if (mode == SingleCall.SingleMode.CANCEL_NEW) {
                    if ((rpc.getDebugFlags() & Endpoint.REQUEST_LINE_DEBUG) > 0) {
                        JudoLogger.log("Request " + name + " rejected - SingleCall.", JudoLogger.LogLevel.DEBUG);
                    }
                    request.cancel();
                    return true;
                }
                if (mode == SingleCall.SingleMode.CANCEL_OLD) {
                    RequestImpl oldRequest = rpc.getSingleCallMethods().get(CacheMethod.getMethodId(m));
                    if ((rpc.getDebugFlags() & Endpoint.REQUEST_LINE_DEBUG) > 0) {
                        JudoLogger.log("Request " + oldRequest.getName() + " rejected - SingleCall.", JudoLogger.LogLevel.DEBUG);
                    }
                    oldRequest.cancel();
                    synchronized (rpc.getSingleCallMethods()) {
                        rpc.getSingleCallMethods().put(request.getMethodId(), request);
                    }
                }
            } else {
                synchronized (rpc.getSingleCallMethods()) {
                    rpc.getSingleCallMethods().put(request.getMethodId(), request);
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private MethodInfo getMethodInfo(Type returnType, Object[] args, Type[] types, Runnable run) {
        for (JudoAdapter adapter : rpc.getAdapters()) {
            if (adapter.canHandle(returnType)) {
                return adapter.getMethodInfo(returnType, args, types, run);
            }
        }
        throw new JudoException("No adapter register for type " + returnType.toString());
    }

    public void callBatch() {
        List<RequestImpl> batches;
        synchronized (batchRequests) {
            for (int i = batchRequests.size() - 1; i >= 0; i--) {
                if (batchRequests.get(i).isCancelled()) {
                    batchRequests.remove(i);
                }
            }
        }
        if (batchRequests.size() > 0) {

            if (mode.equals(EndpointImpl.BatchMode.AUTO)) {
                synchronized (batchRequests) {
                    batches = new ArrayList<>(batchRequests.size());
                    batches.addAll(batchRequests);
                    batchRequests.clear();
                    this.batchEnabled = false;
                }
            } else {
                this.batchEnabled = false;
                batches = batchRequests;
            }

            RequestImpl.invokeBatchCallbackStart(rpc, this);

            Map<Integer, Pair<RequestImpl, Object>> cacheObjects = new HashMap<>();
            if (rpc.isCacheEnabled()) {
                for (int i = batches.size() - 1; i >= 0; i--) {
                    RequestImpl req = batches.get(i);
                    if (req.isLocalCacheable()) {
                        CacheResult result = rpc.getMemoryCache().get(req.getMethodId(), req.getArgs(), req.getLocalCacheLifeTime(), req.getLocalCacheSize());
                        LocalCache.CacheLevel cacheLevel = req.getLocalCacheLevel();
                        if (result.result) {
                            if (rpc.getCacheMode() == Endpoint.CacheMode.CLONE) {
                                result.object = rpc.getClonner().clone(result.object);
                            }
                            cacheObjects.put(req.getId(), new Pair<>(req, result.object));
                            if (req.getLocalCacheOnlyOnErrorMode().equals(LocalCache.OnlyOnError.NO)) {
                                batches.remove(i);
                                req.invokeStart(new CacheInfo(true, result.time));
                                req.setHeaders(result.headers);
                            }


                        } else if (cacheLevel != LocalCache.CacheLevel.MEMORY_ONLY) {
                            CacheMethod cacheMethod = new CacheMethod(CacheMethod.getMethodId(req.getMethod()),
                                    req.getName(), req.getMethod().getDeclaringClass().getSimpleName(), rpc.getUrl(), cacheLevel);
                            result = rpc.getDiskCache().get(cacheMethod, Arrays.deepToString(req.getArgs()), req.getLocalCacheLifeTime());
                            if (result.result) {
                                rpc.getMemoryCache().put(req.getMethodId(),
                                        req.getArgs(),
                                        result.object,
                                        req.getLocalCacheSize(),
                                        result.headers);
                                cacheObjects.put(req.getId(), new Pair<>(req, result.object));
                                if (req.getLocalCacheOnlyOnErrorMode().equals(LocalCache.OnlyOnError.NO)) {
                                    batches.remove(i);
                                    req.invokeStart(new CacheInfo(true, result.time));
                                    req.setHeaders(result.headers);
                                }
                            }

                        }
                    }

                }
            }

            BatchProgressObserver batchProgressObserver = new BatchProgressObserver(rpc, this, batches);
            List<RequestResult> responses;
            if (batches.size() > 0) {
                sendBatchRequest(batches, batchProgressObserver, cacheObjects);

            } else {
                responses = new ArrayList<>();
                batchProgressObserver.setMaxProgress(1);
                batchProgressObserver.progressTick(1);
                receiveResponse(batches, responses, cacheObjects);
            }


        } else {
            this.batchEnabled = false;
            if (batchCallback != null) {
                RequestImpl.invokeBatchCallbackProgress(rpc, this, 100);
                RequestImpl.invokeBatchCallback(rpc, this, new Object[]{});
            }
        }
    }

    protected void receiveResponse(List<RequestImpl> batches, List<RequestResult> responses, Map<Integer, Pair<RequestImpl, Object>> cacheObjects) {
        if (rpc.isCacheEnabled()) {
            for (int i = responses.size() - 1; i >= 0; i--) {
                RequestResult result = responses.get(i);
                if (cacheObjects.containsKey(result.id) && result instanceof ErrorResult) {
                    LocalCache.OnlyOnError onlyOnErrorMode = cacheObjects.get(result.id).first.getLocalCacheOnlyOnErrorMode();
                    if (onlyOnErrorMode.equals(LocalCache.OnlyOnError.ON_ALL_ERROR) ||
                            (onlyOnErrorMode.equals(LocalCache.OnlyOnError.ON_CONNECTION_ERROR) && result.error instanceof ConnectionException)) {
                        responses.remove(result);
                        RequestSuccessResult res = new RequestSuccessResult(cacheObjects.get(result.id).second);
                        res.id = result.id;
                        responses.add(res);
                    }
                    cacheObjects.remove(result.id);
                } else {
                    cacheObjects.remove(result.id);
                }
            }

            for (Map.Entry<Integer, Pair<RequestImpl, Object>> pairs : cacheObjects.entrySet()) {
                RequestSuccessResult res = new RequestSuccessResult(cacheObjects.get(pairs.getKey()).second);
                res.id = pairs.getKey();
                responses.add(res);
                batches.add(pairs.getValue().first);
            }
        }
        //TODO change to map implementation
        Collections.sort(batches, new Comparator<RequestImpl>() {
            @Override
            public int compare(RequestImpl lhs, RequestImpl rhs) {
                return lhs.getId().compareTo(rhs.getId());
            }
        });
        Collections.sort(responses, new Comparator<RequestResult>() {
            @Override
            public int compare(RequestResult lhs, RequestResult rhs) {
                return lhs.id.compareTo(rhs.id);
            }
        });
        handleBatchResponse(batches, batchCallback, responses);
    }

    protected int calculateTimeout(List<RequestImpl> batches) {
        int timeout = 0;
        if (rpc.getTimeoutMode() == Endpoint.BatchTimeoutMode.TIMEOUTS_SUM) {
            for (RequestImpl req : batches) {
                timeout += req.getTimeout();
            }
        } else {
            for (RequestImpl req : batches) {
                timeout = Math.max(timeout, req.getTimeout());
            }
        }
        return timeout;
    }

    public void sendBatchRequest(final List<RequestImpl> batches, BatchProgressObserver progressObserver,
                                 final Map<Integer, Pair<RequestImpl, Object>> cacheObjects) {
        final List<RequestResult> responses = Collections.synchronizedList(new ArrayList<RequestResult>(batches.size()));

        try {
            rpc.getHandler().post(new AsyncResultSender(new ArrayList<>(batches)));
            progressObserver.setMaxProgress(TimeStat.TICKS);
            responses.addAll(rpc.getRequestConnector().callBatch(batches, progressObserver, calculateTimeout(batches)));
            Collections.sort(responses);
            receiveResponse(batches, responses, cacheObjects);
        } catch (final JudoException e) {
            for (RequestImpl request : batches) {
                responses.add(new ErrorResult(request.getId(), e));
            }
            Collections.sort(responses);
            receiveResponse(batches, responses, cacheObjects);
        }
    }

    protected void handleBatchResponse(List<RequestImpl> requests, Batch batch, List<RequestResult> responses) {
        Object[] results = new Object[requests.size()];
        JudoException ex = null;
        RequestImpl exceptionRequest = null;
        int i = 0;
        if (requests.size() != responses.size()) {
            StringBuilder requestNameBuilder = new StringBuilder();
            StringBuilder responseNameBuilder = new StringBuilder();
            for (RequestImpl req : requests) {
                requestNameBuilder.append(req.getName());
                requestNameBuilder.append("\n");
            }
            for (RequestResult res : responses) {
                String resultString;
                if (res.result != null) {
                    resultString = res.result.toString();
                } else if (res.error != null) {
                    resultString = res.error.toString();
                } else {
                    resultString = "NO RESPONSE";
                }
                responseNameBuilder.append(resultString);
                responseNameBuilder.append("\n");
            }

            ex = new ParseException("Wrong server response. Expect " + requests.size() +
                    " batch responses, get " + responses.size()
                    + "\nRequests:\n" + requestNameBuilder.toString()
                    + "\nResponse:\n" + responseNameBuilder.toString()
            );
        } else {
            for (RequestImpl request : requests) {
                try {
                    RequestResult response = null;

                    if (i < responses.size()) {
                        response = responses.get(i);
                    }

                    if (response != null && response.cacheObject != null) {
                        results[i] = response.cacheObject;
                    } else {

                        if (response.error != null) {
                            throw response.error;
                        }

                        if (!request.isVoidResult()) {
                            results[i] = response.result;
                            if ((rpc.isCacheEnabled() && request.isLocalCacheable())) {
                                rpc.getMemoryCache().put(request.getMethodId(), request.getArgs(), results[i], request.getLocalCacheSize(), request.getHeaders());
                                if (rpc.getCacheMode() == Endpoint.CacheMode.CLONE) {
                                    results[i] = rpc.getClonner().clone(results[i]);
                                }
                                LocalCache.CacheLevel cacheLevel = request.getLocalCacheLevel();

                                if (cacheLevel != LocalCache.CacheLevel.MEMORY_ONLY) {
                                    CacheMethod cacheMethod = new CacheMethod(CacheMethod.getMethodId(request.getMethod()),
                                            request.getName(), request.getMethod().getDeclaringClass().getSimpleName(), rpc.getUrl(), cacheLevel);
                                    rpc.getDiskCache().put(cacheMethod, Arrays.deepToString(request.getArgs()), results[i], request.getLocalCacheSize(), request.getHeaders());
                                }
                            }
                        }
                    }
                    request.invokeCallback(results[i]);
                } catch (JudoException e) {
                    if (request.isBatchFatal()) {
                        ex = e;
                        exceptionRequest = request;
                    }
                    request.invokeCallbackException(e);
                }
                i++;
            }
        }
        if (batch != null) {
            if (ex == null) {
                RequestImpl.invokeBatchCallback(rpc, this, results);
            } else {
                RequestImpl.invokeBatchCallbackException(rpc, this, ex);
            }
        }
        if (ex != null) {
            final JudoException finalEx = ex;
            final RequestImpl finalRequest = exceptionRequest;
            if (rpc.getErrorLoggers().size() != 0 && !(ex instanceof CancelException) && !isCancelled()) {
                rpc.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        for (ErrorLogger errorLogger : rpc.getErrorLoggers()) {
                            errorLogger.onError(finalEx, finalRequest);
                        }
                    }
                });

            }
        }
    }

    static void addToExceptionMessage(String additionalMessage, Exception exception) {
        try {
            Field field = Throwable.class.getDeclaredField("detailMessage");
            field.setAccessible(true);
            String message = additionalMessage + ": " + field.get(exception);
            field.set(exception, message);
        } catch (Exception ex) {
            JudoLogger.log(ex);
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        if (!cancelled) {
            this.cancelled = true;
            synchronized (this) {
                notifyAll();
            }
            for (int i = batchRequests.size() - 1; i >= 0; i--) {
                batchRequests.get(i).cancel();
            }
            if (running) {
                running = false;
                rpc.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (batchCallback != null) {
                            batchCallback.onFinish();
                            clearBatchCallback();
                        }
                    }
                });
            }
        }
    }

    @Override
    public void await() throws InterruptedException {
        synchronized (this) {
            if (!isDone() && !isCancelled()) {
                wait();
            }
        }
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        throw new UnsupportedOperationException("getHeaders of batch is not supported");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    @Override
    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    @Override
    public long getTotalTimeMillis() {
        return totalTimeMillis;
    }

    public void done() {
        synchronized (this) {
            this.done = true;
            this.running = false;
            notifyAll();
        }
    }

    public void start() {
        this.startTimeMillis = System.currentTimeMillis();
        this.running = true;
    }

    public Batch<?> getBatchCallback() {
        return batchCallback;
    }

    public void clearBatchCallback() {
        batchCallback = null;
        for (Request request : batchRequests) {
            rpc.stopRequest(request);
        }
    }

    public void calcTime() {
        this.endTimeMillis = System.currentTimeMillis();
        this.totalTimeMillis = this.endTimeMillis - this.startTimeMillis;
    }
}
