package io.github.the_pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * PocketFlow - A simple, synchronous workflow library for Java in a single file.
 */
public final class PocketFlow {

    private PocketFlow() {}

    private static void logWarn(String message) {
        System.err.println("WARN: PocketFlow - " + message);
    }

    public static class PocketFlowException extends RuntimeException {
        public PocketFlowException(String message) { super(message); }
        public PocketFlowException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Base class for all nodes in the workflow.
     *
     * @param <P> Type of the result from the prep phase.
     * @param <E> Type of the result from the exec phase.
     * The return type of post/run is always String (or null for default action).
     */
    public static abstract class BaseNode<P, E> {
        protected Map<String, Object> params = new HashMap<>();
        protected final Map<String, BaseNode<?, ?>> successors = new HashMap<>();
        public static final String DEFAULT_ACTION = "default";

        public BaseNode<P, E> setParams(Map<String, Object> params) {
            this.params = params != null ? new HashMap<>(params) : new HashMap<>();
            return this;
        }

        public <NEXT_P, NEXT_E> BaseNode<NEXT_P, NEXT_E> next(BaseNode<NEXT_P, NEXT_E> node) {
            return next(node, DEFAULT_ACTION);
        }

        public <NEXT_P, NEXT_E> BaseNode<NEXT_P, NEXT_E> next(BaseNode<NEXT_P, NEXT_E> node, String action) {
            Objects.requireNonNull(node, "Successor node cannot be null");
            Objects.requireNonNull(action, "Action cannot be null");
            if (this.successors.containsKey(action)) {
                logWarn("Overwriting successor for action '" + action + "' in node " + this.getClass().getSimpleName());
            }
            this.successors.put(action, node);
            return node;
        }

        public P prep(Map<String, Object> sharedContext) { return null; }
        public abstract E exec(P prepResult);
        /** Post method MUST return a String action, or null for the default action. */
        public String post(Map<String, Object> sharedContext, P prepResult, E execResult) { return null; }

        protected E internalExec(P prepResult) { return exec(prepResult); }

        protected String internalRun(Map<String, Object> sharedContext) {
            P prepRes = prep(sharedContext);
            E execRes = internalExec(prepRes);
            return post(sharedContext, prepRes, execRes);
        }

        public String run(Map<String, Object> sharedContext) {
            if (!successors.isEmpty()) {
                logWarn("Node " + getClass().getSimpleName() + " has successors, but run() was called. Successors won't be executed. Use Flow.");
            }
            return internalRun(sharedContext);
        }

        protected BaseNode<?, ?> getNextNode(String action) {
            String actionKey = (action != null) ? action : DEFAULT_ACTION;
            BaseNode<?, ?> nextNode = successors.get(actionKey);
            if (nextNode == null && !successors.isEmpty() && !successors.containsKey(actionKey)) {
                 logWarn("Flow might end: Action '" + actionKey + "' not found in successors "
                         + successors.keySet() + " of node " + this.getClass().getSimpleName());
            }
            return nextNode;
        }
    }

    /**
     * A synchronous node with built-in retry capabilities.
     */
    public static abstract class Node<P, E> extends BaseNode<P, E> {
        protected final int maxRetries;
        protected final long waitMillis;
        protected int currentRetry = 0;

        public Node() { this(1, 0); }
        public Node(int maxRetries, long waitMillis) {
            if (maxRetries < 1) throw new IllegalArgumentException("maxRetries must be at least 1");
            if (waitMillis < 0) throw new IllegalArgumentException("waitMillis cannot be negative");
            this.maxRetries = maxRetries;
            this.waitMillis = waitMillis;
        }

        public E execFallback(P prepResult, Exception lastException) throws Exception { throw lastException; }

        @Override
        protected E internalExec(P prepResult) {
            Exception lastException = null;
            for (currentRetry = 0; currentRetry < maxRetries; currentRetry++) {
                try { return exec(prepResult); }
                catch (Exception e) {
                    lastException = e;
                    if (currentRetry < maxRetries - 1 && waitMillis > 0) {
                        try { TimeUnit.MILLISECONDS.sleep(waitMillis); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new PocketFlowException("Thread interrupted during retry wait", ie); }
                    }
                }
            }
            try {
                if (lastException == null) throw new PocketFlowException("Execution failed, but no exception was captured.");
                return execFallback(prepResult, lastException);
            } catch (Exception fallbackException) {
                if (lastException != null && fallbackException != lastException) fallbackException.addSuppressed(lastException);
                if (fallbackException instanceof RuntimeException) throw (RuntimeException) fallbackException;
                else throw new PocketFlowException("Fallback execution failed", fallbackException);
            }
        }
    }

    /**
     * A synchronous node that processes a list of items individually.
     */
    public static abstract class BatchNode<IN_ITEM, OUT_ITEM> extends Node<List<IN_ITEM>, List<OUT_ITEM>> {
        public BatchNode() { super(); }
        public BatchNode(int maxRetries, long waitMillis) { super(maxRetries, waitMillis); }

        public abstract OUT_ITEM execItem(IN_ITEM item);
        public OUT_ITEM execItemFallback(IN_ITEM item, Exception lastException) throws Exception { throw lastException; }

        @Override
        protected List<OUT_ITEM> internalExec(List<IN_ITEM> batchPrepResult) {
            if (batchPrepResult == null || batchPrepResult.isEmpty()) return Collections.emptyList();
            List<OUT_ITEM> results = new ArrayList<>(batchPrepResult.size());
            for (IN_ITEM item : batchPrepResult) {
                 Exception lastItemException = null;
                 OUT_ITEM itemResult = null;
                 boolean itemSuccess = false;
                 for (currentRetry = 0; currentRetry < maxRetries; currentRetry++) {
                    try { itemResult = execItem(item); itemSuccess = true; break; }
                    catch (Exception e) {
                        lastItemException = e;
                        if (currentRetry < maxRetries - 1 && waitMillis > 0) {
                             try { TimeUnit.MILLISECONDS.sleep(waitMillis); }
                             catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new PocketFlowException("Interrupted batch retry wait: " + item, ie); }
                        }
                    }
                 }
                 if (!itemSuccess) {
                     try {
                         if (lastItemException == null) throw new PocketFlowException("Item exec failed without exception: " + item);
                         itemResult = execItemFallback(item, lastItemException);
                     } catch (Exception fallbackException) {
                         if (lastItemException != null && fallbackException != lastItemException) fallbackException.addSuppressed(lastItemException);
                         if (fallbackException instanceof RuntimeException) throw (RuntimeException) fallbackException;
                         else throw new PocketFlowException("Item fallback failed: " + item, fallbackException);
                     }
                 }
                 results.add(itemResult);
            }
            return results;
        }
    }

    /**
     * Orchestrates the execution of a sequence of connected nodes.
     */
    public static class Flow extends BaseNode<Void, String> {
        protected BaseNode<?, ?> startNode;

        public Flow() { this(null); }
        public Flow(BaseNode<?, ?> startNode) { this.start(startNode); }

        public <SN_P, SN_E> BaseNode<SN_P, SN_E> start(BaseNode<SN_P, SN_E> startNode) {
            this.startNode = Objects.requireNonNull(startNode, "Start node cannot be null");
            return startNode;
        }

        @Override public final String exec(Void prepResult) {
            throw new UnsupportedOperationException("Flow.exec() is internal and should not be called directly.");
        }


        @SuppressWarnings({"unchecked", "rawtypes"}) // Raw types needed for successors map
        protected String orchestrate(Map<String, Object> sharedContext, Map<String, Object> initialParams) {
            if (startNode == null) { logWarn("Flow started with no start node."); return null; }
            BaseNode<?, ?> currentNode = this.startNode;
            String lastAction = null;
            Map<String, Object> currentParams = new HashMap<>(this.params);
            if (initialParams != null) { currentParams.putAll(initialParams); }
            while (currentNode != null) {
                currentNode.setParams(currentParams);
                lastAction = (String) ((BaseNode)currentNode).internalRun(sharedContext);
                currentNode = currentNode.getNextNode(lastAction);
            }
            return lastAction;
        }

        @Override
        protected String internalRun(Map<String, Object> sharedContext) {
            Void prepRes = prep(sharedContext);
            String orchRes = orchestrate(sharedContext, null);
            return post(sharedContext, prepRes, orchRes);
        }

        /** Post method for the Flow itself. Default returns the last action from orchestration. */
        @Override public String post(Map<String, Object> sharedContext, Void prepResult, String execResult) {
            return execResult;
        }
    }

    /**
     * A flow that runs its entire sequence for each parameter set from `prepBatch`.
     */
    public static abstract class BatchFlow extends Flow {
        public BatchFlow() { super(); }
        public BatchFlow(BaseNode<?, ?> startNode) { super(startNode); }

        public abstract List<Map<String, Object>> prepBatch(Map<String, Object> sharedContext);
        /** Post method MUST return a String action, or null for the default action. */
        public abstract String postBatch(Map<String, Object> sharedContext, List<Map<String, Object>> batchPrepResult);

        @Override
        protected String internalRun(Map<String, Object> sharedContext) {
            List<Map<String, Object>> batchParamsList = this.prepBatch(sharedContext);
            if (batchParamsList == null) { batchParamsList = Collections.emptyList(); }
            for (Map<String, Object> batchParams : batchParamsList) {
                Map<String, Object> currentRunParams = new HashMap<>(this.params);
                if (batchParams != null) { currentRunParams.putAll(batchParams); }
                orchestrate(sharedContext, currentRunParams); // Run for side-effects
            }
            return postBatch(sharedContext, batchParamsList);
        }

         @Override
         public final String post(Map<String, Object> sharedContext, Void prepResult, String execResult) {
             throw new UnsupportedOperationException("Use postBatch for BatchFlow, not post.");
         }
    }

}