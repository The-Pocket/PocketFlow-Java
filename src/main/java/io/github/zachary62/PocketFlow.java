package io.github.zachary62;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * PocketFlow - A simple workflow library for Java in a single file.
 * Uses generics for improved type safety compared to direct Object usage.
 */
public final class PocketFlow {

    // Private constructor to prevent instantiation of the utility class
    private PocketFlow() {}

    // Simple shared executor for delays - consider providing externally for production
    private static final Executor DELAYED_EXECUTOR = Executors.newScheduledThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2), // Reasonable default thread count
         r -> {
            Thread t = new Thread(r);
            t.setDaemon(true); // Allow JVM exit even if these threads are waiting
            return t;
        }
    );


    // Simple Warning Logger (replace with SLF4j or JUL if proper logging is needed)
    private static void logWarn(String message) {
        System.err.println("WARN: " + message);
    }

    /**
     * Custom RuntimeException for PocketFlow specific errors.
     */
    public static class PocketFlowException extends RuntimeException {
        public PocketFlowException(String message) {
            super(message);
        }

        public PocketFlowException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Base class for all nodes in the workflow.
     *
     * @param <P> Type of the result from the prep phase.
     * @param <E> Type of the result from the exec phase.
     * @param <R> Type of the final result/action returned by the node's run cycle (often String).
     */
    public static abstract class BaseNode<P, E, R> {
        protected Map<String, Object> params = new HashMap<>();
        // Successors map actions (Strings) to the next node
        protected final Map<String, BaseNode<?, ?, ?>> successors = new HashMap<>();
        public static final String DEFAULT_ACTION = "default";

        public BaseNode<P, E, R> setParams(Map<String, Object> params) {
            // Create a defensive copy if external modification is a concern
            this.params = params != null ? new HashMap<>(params) : new HashMap<>();
            return this;
        }

        /** Connects this node to the next using the default action ("default"). */
        public <NEXT_P, NEXT_E, NEXT_R> BaseNode<NEXT_P, NEXT_E, NEXT_R> next(BaseNode<NEXT_P, NEXT_E, NEXT_R> node) {
            return next(node, DEFAULT_ACTION);
        }

        /** Connects this node to the next using a specific action string. */
        public <NEXT_P, NEXT_E, NEXT_R> BaseNode<NEXT_P, NEXT_E, NEXT_R> next(BaseNode<NEXT_P, NEXT_E, NEXT_R> node, String action) {
            Objects.requireNonNull(node, "Successor node cannot be null");
            Objects.requireNonNull(action, "Action cannot be null");

            if (this.successors.containsKey(action)) {
                logWarn("Overwriting successor for action '" + action + "' in node " + this.getClass().getSimpleName());
            }
            this.successors.put(action, node);
            return node; // Return the added node to allow chaining
        }

        // --- Lifecycle Methods (to be implemented by concrete nodes) ---

        /** Prepare data or context needed for execution. */
        public P prep(Map<String, Object> sharedContext) { return null; } // Default no-op

        /** Execute the core logic of the node. */
        public abstract E exec(P prepResult);

        /** Perform actions after execution (e.g., update context, determine next step). */
        public R post(Map<String, Object> sharedContext, P prepResult, E execResult) { return null; } // Default no-op

        // --- Internal Execution Logic ---

        /** Internal execution step, potentially overridden for retries etc. */
        protected E internalExec(P prepResult) {
            return exec(prepResult);
        }

        /** Runs the full prep -> exec -> post lifecycle for this node. */
        protected R runLifecycle(Map<String, Object> sharedContext) {
            P prepRes = prep(sharedContext);
            E execRes = internalExec(prepRes);
            return post(sharedContext, prepRes, execRes);
        }

        /** Executes this node standalone. Issues warning if successors exist. */
        public R run(Map<String, Object> sharedContext) {
            if (!successors.isEmpty()) {
                logWarn("Node " + getClass().getSimpleName() + " has successors, but run() was called. Successors won't be executed. Use Flow.");
            }
            return runLifecycle(sharedContext);
        }

        /** Finds the next node based on the action returned by the current node. */
        protected BaseNode<?, ?, ?> getNextNode(R action) {
            String actionKey = (action != null) ? action.toString() : DEFAULT_ACTION;
            BaseNode<?, ?, ?> nextNode = successors.get(actionKey);

            if (nextNode == null && !successors.isEmpty() && !actionKey.equals(successors.keySet().iterator().next())) {
                 // Avoid warning if only one successor exists and it's default, but action was null
                 if (!(successors.size() == 1 && actionKey.equals(DEFAULT_ACTION) && successors.containsKey(DEFAULT_ACTION)) ) {
                     logWarn("Flow might end: Action '" + actionKey + "' not found in successors "
                             + successors.keySet() + " of node " + this.getClass().getSimpleName());
                 }
            }
            return nextNode;
        }
    }

    /**
     * A synchronous node with built-in retry capabilities.
     */
    public static abstract class Node<P, E, R> extends BaseNode<P, E, R> {
        protected final int maxRetries;
        protected final long waitMillis;
        protected int currentRetry = 0; // Track retries for potential use in exec/fallback

        public Node() { this(1, 0); }

        public Node(int maxRetries, long waitMillis) {
            if (maxRetries < 1) throw new IllegalArgumentException("maxRetries must be at least 1");
            if (waitMillis < 0) throw new IllegalArgumentException("waitMillis cannot be negative");
            this.maxRetries = maxRetries;
            this.waitMillis = waitMillis;
        }

        /** Fallback logic if exec fails after all retries. Default re-throws. */
        public E execFallback(P prepResult, Exception lastException) throws Exception {
            throw lastException;
        }

        @Override
        protected E internalExec(P prepResult) {
            Exception lastException = null;
            for (currentRetry = 0; currentRetry < maxRetries; currentRetry++) {
                try {
                    return exec(prepResult); // Attempt execution
                } catch (Exception e) {
                    lastException = e;
                    if (currentRetry < maxRetries - 1 && waitMillis > 0) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(waitMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); // Restore interrupt status
                            throw new PocketFlowException("Thread interrupted during retry wait", ie);
                        }
                    }
                }
            }

            // All retries failed, attempt fallback
            try {
                return execFallback(prepResult, lastException);
            } catch (Exception fallbackException) {
                // If fallback also fails, wrap the *fallback* exception.
                // Add the last execution exception as suppressed if they differ.
                if (lastException != null && fallbackException != lastException) {
                    fallbackException.addSuppressed(lastException);
                }
                 if (fallbackException instanceof RuntimeException) {
                     throw (RuntimeException) fallbackException;
                 } else {
                    // Wrap checked exceptions from fallback
                    throw new PocketFlowException("Fallback execution failed", fallbackException);
                 }
            }
        }
    }

    /**
     * A synchronous node that processes a list of items individually.
     *
     * @param <IN_ITEM>  Type of items in the input list (from prep).
     * @param <OUT_ITEM> Type of items in the output list (from exec).
     * @param <R>        Type of the final result/action returned by post.
     */
    public static abstract class BatchNode<IN_ITEM, OUT_ITEM, R> extends Node<List<IN_ITEM>, List<OUT_ITEM>, R> {

        public BatchNode() { super(); }
        public BatchNode(int maxRetries, long waitMillis) { super(maxRetries, waitMillis); }

        /** Execute logic for a single item in the batch. */
        public abstract OUT_ITEM execItem(IN_ITEM item);

        /** Fallback logic for a single item if execItem fails all retries. */
        public OUT_ITEM execItemFallback(IN_ITEM item, Exception lastException) throws Exception {
            // Default behavior matches Node's fallback: re-throw the last execution exception.
            // Note: This calls the *Node*'s fallback, not the BatchNode's execItemFallback directly.
            // This might be confusing. Consider if BatchNode should have its own fallback field.
            // For now, we simulate by re-throwing. If you need item-specific fallback, override this.
            throw lastException;
        }

        // Override internalExec to process items one by one with retry logic
        @Override
        protected List<OUT_ITEM> internalExec(List<IN_ITEM> batchPrepResult) {
            if (batchPrepResult == null || batchPrepResult.isEmpty()) {
                return Collections.emptyList();
            }

            List<OUT_ITEM> results = new ArrayList<>(batchPrepResult.size());
            for (IN_ITEM item : batchPrepResult) {
                // Apply retry logic per item - reusing Node's logic structure conceptually
                 Exception lastItemException = null;
                 OUT_ITEM itemResult = null;
                 boolean itemSuccess = false;

                 for (currentRetry = 0; currentRetry < maxRetries; currentRetry++) {
                    try {
                        itemResult = execItem(item); // Attempt item execution
                        itemSuccess = true;
                        break; // Success, exit retry loop for this item
                    } catch (Exception e) {
                        lastItemException = e;
                        if (currentRetry < maxRetries - 1 && waitMillis > 0) {
                             try {
                                TimeUnit.MILLISECONDS.sleep(waitMillis);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new PocketFlowException("Thread interrupted during batch item retry wait for item: " + item, ie);
                            }
                        }
                    }
                 } // End retry loop for item

                 if (!itemSuccess) {
                     // All retries failed for this item, attempt item fallback
                     try {
                         itemResult = execItemFallback(item, lastItemException);
                     } catch (Exception fallbackException) {
                         if (lastItemException != null && fallbackException != lastItemException) {
                            fallbackException.addSuppressed(lastItemException);
                         }
                          if (fallbackException instanceof RuntimeException) {
                             throw (RuntimeException) fallbackException;
                         } else {
                             throw new PocketFlowException("Fallback execution failed for item: " + item, fallbackException);
                         }
                     }
                 }
                 results.add(itemResult);
            } // End loop over items
            return results;
        }
    }

    /**
     * Orchestrates the execution of a sequence of connected nodes.
     * Can itself be used as a node within a larger flow.
     *
     * @param <R_ACTION> The type of the action returned by the last node in the flow's execution.
     */
    public static class Flow<R_ACTION> extends BaseNode<Void, R_ACTION, R_ACTION> {
        protected BaseNode<?, ?, ?> startNode;

        public Flow() { this(null); }
        public Flow(BaseNode<?, ?, ?> startNode) { this.start(startNode); }

        /** Sets the starting node for this flow. */
        public <SN_P, SN_E, SN_R> BaseNode<SN_P, SN_E, SN_R> start(BaseNode<SN_P, SN_E, SN_R> startNode) {
            Objects.requireNonNull(startNode, "Start node cannot be null");
            this.startNode = startNode;
            return startNode; // Allow chaining from start node
        }

        // Flow's "exec" is the orchestration logic. It doesn't fit the BaseNode exec signature.
        @Override
        public final R_ACTION exec(Void prepResult) {
            throw new UnsupportedOperationException("Flow.exec() is internal. Use run() or runLifecycle().");
        }

        /** Orchestrates the flow execution for given context and parameters. */
        @SuppressWarnings({"unchecked", "rawtypes"}) // Necessary for dynamically calling runLifecycle on nodes with varying generics
        protected R_ACTION orchestrate(Map<String, Object> sharedContext, Map<String, Object> initialParams) {
            if (startNode == null) {
                logWarn("Flow started with no start node.");
                return null;
            }

            BaseNode currentNode = this.startNode;
            R_ACTION lastAction = null;
            Map<String, Object> currentParams = new HashMap<>(this.params); // Start with flow's base params
            if (initialParams != null) {
                currentParams.putAll(initialParams); // Merge/override with run-specific params
            }

            while (currentNode != null) {
                currentNode.setParams(currentParams); // Apply combined params
                // Cast R_ACTION is safe assuming flow nodes ultimately produce compatible action types.
                lastAction = (R_ACTION) currentNode.runLifecycle(sharedContext);
                currentNode = currentNode.getNextNode(lastAction);
            }
            return lastAction;
        }

        // Override runLifecycle to perform orchestration instead of simple exec
        @Override
        protected R_ACTION runLifecycle(Map<String, Object> sharedContext) {
            Void prepRes = prep(sharedContext); // Call flow's own prep (usually no-op)
            R_ACTION orchRes = orchestrate(sharedContext, null); // Orchestrate with flow's base params
            return post(sharedContext, prepRes, orchRes); // Call flow's own post
        }

        /** Default post for a Flow returns the result of the orchestration (the last action). */
        @Override
        public R_ACTION post(Map<String, Object> sharedContext, Void prepResult, R_ACTION execResult) {
            return execResult;
        }
    }

    /**
     * A flow that runs its entire sequence of nodes for each parameter set provided by `prep`.
     * The `prep` method MUST return a `List<Map<String, Object>>`.
     *
     * @param <R_ACTION> The type of the action returned by the BatchFlow's post method.
     */
    public static abstract class BatchFlow<R_ACTION> extends Flow<R_ACTION> {

        public BatchFlow() { super(); }
        public BatchFlow(BaseNode<?, ?, ?> startNode) { super(startNode); }

        /** Subclasses MUST override prep to return the list of parameter maps for each batch run. */
        // ** FIX: Removed @Override **
        public abstract List<Map<String, Object>> prep(Map<String, Object> sharedContext);

        // ** FIX: Removed the exec method override **
        // public final R_ACTION exec(Void prepResult) { ... }

         // Override runLifecycle to iterate through batch parameters and orchestrate
        @Override
        protected R_ACTION runLifecycle(Map<String, Object> sharedContext) {
            // *** Crucial difference: Call the overridden prep() which returns List<Map> ***
            List<Map<String, Object>> batchParamsList = this.prep(sharedContext); // Use 'this.prep' explicitly

            if (batchParamsList == null) {
                batchParamsList = Collections.emptyList();
            }

            // Note: Python version doesn't seem to collect/use the results of each _orchestrate run.
            // We run orchestrate for its side effects on sharedContext.
            for (Map<String, Object> batchParams : batchParamsList) {
                // Merge flow base params with current batch params for this specific run
                Map<String, Object> currentRunParams = new HashMap<>(this.params);
                if (batchParams != null) {
                    currentRunParams.putAll(batchParams);
                }
                orchestrate(sharedContext, currentRunParams); // Run orchestration for this item
            }

            // Post receives the original list of batch parameters from prep,
            // and null for execResult (as per Python's implementation pattern).
            // The R_ACTION type is determined by the BatchFlow's specific post implementation.
            return post(sharedContext, batchParamsList, null); // Pass the List<Map> as P, null as E
        }

         /**
          * Post-processing for the entire batch flow.
          *
          * @param sharedContext Shared data map.
          * @param prepResult The List of parameter Maps returned by prep.
          * @param execResult Always null for BatchFlow (as exec runs multiple times).
          * @return The final action/result for the entire BatchFlow.
          */
         // ** FIX: Removed @Override **
         public abstract R_ACTION post(Map<String, Object> sharedContext, List<Map<String, Object>> prepResult, Void execResult);

    }


    // ========================================================================
    // Async Classes
    // ========================================================================

    /**
     * An asynchronous node with retry capabilities using CompletableFuture.
     */
    public static abstract class AsyncNode<P, E, R> extends Node<P, E, R> {

        public AsyncNode() { this(1, 0); }
        public AsyncNode(int maxRetries, long waitMillis) { super(maxRetries, waitMillis); }

        // --- Async Lifecycle Methods ---
        public CompletableFuture<P> prepAsync(Map<String, Object> sharedContext) { return CompletableFuture.completedFuture(null); }
        public abstract CompletableFuture<E> execAsync(P prepResult);
        public CompletableFuture<R> postAsync(Map<String, Object> sharedContext, P prepResult, E execResult) { return CompletableFuture.completedFuture(null); }

        // --- Override Sync Methods to Throw ---
        @Override public final P prep(Map<String, Object> shared) { throw new PocketFlowException("Use prepAsync for AsyncNode"); }
        @Override public abstract E exec(P prepResult); // Make abstract or throw to force async usage
        @Override public final R post(Map<String, Object> s, P p, E e) { throw new PocketFlowException("Use postAsync for AsyncNode"); }
        @Override public final R run(Map<String, Object> shared) { throw new PocketFlowException("Use runAsync for AsyncNode"); }
        @Override protected final E internalExec(P prepResult) { throw new PocketFlowException("Use _execAsync for AsyncNode"); }
        @Override protected final R runLifecycle(Map<String, Object> s) { throw new PocketFlowException("Use _runAsync for AsyncNode"); }


        /** Async fallback logic. Default completes exceptionally with the last error. */
        public CompletableFuture<E> execFallbackAsync(P prepResult, Throwable lastException) {
            return CompletableFuture.failedFuture(lastException);
        }

        /** Internal async execution with retry logic. */
        protected CompletableFuture<E> internalExecAsync(P prepResult) {
            CompletableFuture<E> finalResult = new CompletableFuture<>();
            internalExecRecursive(prepResult, 0, finalResult);
            return finalResult;
        }

        private void internalExecRecursive(P prepResult, int attempt, CompletableFuture<E> finalResult) {
            execAsync(prepResult).whenComplete((result, throwable) -> {
                if (throwable == null) {
                    finalResult.complete(result); // Success
                } else {
                    // Execution failed
                    if (attempt < maxRetries - 1) {
                        // Schedule retry
                        if (waitMillis > 0) {
                            CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS, DELAYED_EXECUTOR)
                                .execute(() -> internalExecRecursive(prepResult, attempt + 1, finalResult));
                        } else {
                            // Retry immediately (or schedule on default executor to avoid deep stacks)
                             CompletableFuture.runAsync(() -> internalExecRecursive(prepResult, attempt + 1, finalResult));
                             // Note: Immediate recursion can lead to StackOverflowError for many fast retries.
                             // Using runAsync breaks the chain slightly. Consider using default executor.
                        }
                    } else {
                        // Last attempt failed, try fallback
                        execFallbackAsync(prepResult, throwable).whenComplete((fallbackResult, fallbackThrowable) -> {
                            if (fallbackThrowable == null) {
                                finalResult.complete(fallbackResult); // Fallback success
                            } else {
                                // Fallback failed, complete with fallback exception
                                if (throwable != null && fallbackThrowable != throwable) {
                                    fallbackThrowable.addSuppressed(throwable); // Add original exec error
                                }
                                finalResult.completeExceptionally(fallbackThrowable);
                            }
                        });
                    }
                }
            });
        }

        /** Runs the full async lifecycle for this node. */
        public CompletableFuture<R> runAsyncLifecycle(Map<String, Object> sharedContext) {
            return prepAsync(sharedContext)
                .thenCompose(prepResult -> internalExecAsync(prepResult)
                    .thenCompose(execResult -> postAsync(sharedContext, prepResult, execResult)));
        }

        /** Executes this async node standalone. Issues warning if successors exist. */
        public CompletableFuture<R> runAsync(Map<String, Object> sharedContext) {
            if (!successors.isEmpty()) {
                logWarn("AsyncNode " + getClass().getSimpleName() + " has successors, but runAsync() was called. Use AsyncFlow.");
            }
            return runAsyncLifecycle(sharedContext);
        }
    }


    /**
     * An asynchronous node that processes a list of items sequentially.
     * Each item processing waits for the previous one to complete.
     */
    public static abstract class AsyncBatchNode<IN_ITEM, OUT_ITEM, R> extends AsyncNode<List<IN_ITEM>, List<OUT_ITEM>, R> {

        public AsyncBatchNode() { super(); }
        public AsyncBatchNode(int maxRetries, long waitMillis) { super(maxRetries, waitMillis); }

        /** Asynchronously execute logic for a single item. */
        public abstract CompletableFuture<OUT_ITEM> execItemAsync(IN_ITEM item);

        /** Override sync exec to throw, ensuring async usage. */
        @Override public final List<OUT_ITEM> exec(List<IN_ITEM> prepResult) {
             throw new PocketFlowException("Use execAsync for AsyncBatchNode");
        }

        /** Async fallback for a single item failure. Default fails the future. */
         public CompletableFuture<OUT_ITEM> execItemFallbackAsync(IN_ITEM item, Throwable lastException) {
            return CompletableFuture.failedFuture(lastException);
        }

        // Internal execution processes items sequentially using thenCompose
        @Override
        protected CompletableFuture<List<OUT_ITEM>> internalExecAsync(List<IN_ITEM> batchPrepResult) {
            if (batchPrepResult == null || batchPrepResult.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            List<OUT_ITEM> results = new ArrayList<>(batchPrepResult.size());
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (IN_ITEM item : batchPrepResult) {
                chain = chain.thenCompose(v -> {
                    // Apply retry logic per item using a helper method
                    return executeItemWithRetryAsync(item).thenAccept(results::add);
                });
            }

            return chain.thenApply(v -> results); // Return the collected results after all items processed
        }

         // Helper to apply retry/fallback logic to a single item execution
         // ** FIX: Changed visibility to protected **
         protected CompletableFuture<OUT_ITEM> executeItemWithRetryAsync(IN_ITEM item) {
              CompletableFuture<OUT_ITEM> finalItemResult = new CompletableFuture<>();
              executeItemRecursive(item, 0, finalItemResult);
              return finalItemResult;
         }

         // ** FIX: Changed visibility to protected **
         protected void executeItemRecursive(IN_ITEM item, int attempt, CompletableFuture<OUT_ITEM> finalItemResult) {
             execItemAsync(item).whenComplete((result, throwable) -> {
                  if (throwable == null) {
                      finalItemResult.complete(result); // Success
                  } else {
                      if (attempt < maxRetries - 1) {
                           // Schedule retry
                            if (waitMillis > 0) {
                                CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS, DELAYED_EXECUTOR)
                                    .execute(() -> executeItemRecursive(item, attempt + 1, finalItemResult));
                            } else {
                                // Use executor to avoid deep stacks on immediate retry
                                 CompletableFuture.runAsync(() -> executeItemRecursive(item, attempt + 1, finalItemResult));
                            }
                      } else {
                           // Last attempt failed, try fallback
                            execItemFallbackAsync(item, throwable).whenComplete((fallbackResult, fallbackThrowable) -> {
                                if (fallbackThrowable == null) {
                                    finalItemResult.complete(fallbackResult);
                                } else {
                                     if (throwable != null && fallbackThrowable != throwable) {
                                         fallbackThrowable.addSuppressed(throwable);
                                     }
                                    finalItemResult.completeExceptionally(fallbackThrowable);
                                }
                            });
                      }
                  }
             });
         }
    }

     /**
     * An asynchronous node that processes a list of items in parallel.
     */
    public static abstract class AsyncParallelBatchNode<IN_ITEM, OUT_ITEM, R> extends AsyncBatchNode<IN_ITEM, OUT_ITEM, R> {

        public AsyncParallelBatchNode() { super(); }
        public AsyncParallelBatchNode(int maxRetries, long waitMillis) { super(maxRetries, waitMillis); }

        // Inherits execItemAsync and execItemFallbackAsync from AsyncBatchNode

        // Override internal execution to run items in parallel
        @Override
        protected CompletableFuture<List<OUT_ITEM>> internalExecAsync(List<IN_ITEM> batchPrepResult) {
            if (batchPrepResult == null || batchPrepResult.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            List<CompletableFuture<OUT_ITEM>> futures = batchPrepResult.stream()
                .map(this::executeItemWithRetryAsync) // Use the (now protected) retry helper
                .collect(Collectors.toList());

            // Wait for all futures to complete and collect results
            return gatherFutures(futures);
        }
    }

    /**
     * Orchestrates asynchronous execution of nodes.
     */
    public static class AsyncFlow<R_ACTION> extends Flow<R_ACTION> {

        public AsyncFlow() { super(); }
        public AsyncFlow(BaseNode<?, ?, ?> startNode) { super(startNode); }

        // --- Override Sync Methods to Throw ---
        @Override public final R_ACTION run(Map<String, Object> s) { throw new PocketFlowException("Use runAsync for AsyncFlow"); }
        @Override protected final R_ACTION runLifecycle(Map<String, Object> s) { throw new PocketFlowException("Use _runAsyncLifecycle for AsyncFlow"); }
        @Override protected final R_ACTION orchestrate(Map<String, Object> s, Map<String, Object> p) { throw new PocketFlowException("Use _orchestrateAsync for AsyncFlow"); }
        // Keep sync prep/post but provide async versions
        // @Override public Void prep(Map<String, Object> shared) { return super.prep(shared); }
        // @Override public R_ACTION post(Map<String, Object> s, Void p, R_ACTION e) { return super.post(s, p, e); }


        // --- Async Lifecycle ---
        public CompletableFuture<Void> prepAsync(Map<String, Object> sharedContext) { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<R_ACTION> postAsync(Map<String, Object> sharedContext, Void prepResult, R_ACTION execResult) { return CompletableFuture.completedFuture(execResult); }

        /** Runs the full async lifecycle for the flow. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected CompletableFuture<R_ACTION> runAsyncLifecycle(Map<String, Object> sharedContext) {
            return prepAsync(sharedContext)
                .thenCompose(prepRes -> orchestrateAsync(sharedContext, null)
                    .thenCompose(orchRes -> postAsync(sharedContext, prepRes, (R_ACTION) orchRes))); // Cast needed
        }

        /** Orchestrates the async flow execution. */
        @SuppressWarnings({"unchecked", "rawtypes"}) // Necessary for dynamic dispatch
        protected CompletableFuture<?> orchestrateAsync(Map<String, Object> sharedContext, Map<String, Object> initialParams) {
             if (startNode == null) {
                logWarn("AsyncFlow started with no start node.");
                return CompletableFuture.completedFuture(null);
            }

            Map<String, Object> currentParams = new HashMap<>(this.params); // Start with flow's base params
            if (initialParams != null) {
                currentParams.putAll(initialParams);
            }

            return orchestrateAsyncStep(startNode, sharedContext, currentParams, null);
        }

        // Recursive helper for async orchestration step
        @SuppressWarnings({"unchecked", "rawtypes"}) // Necessary for dynamic dispatch
        private CompletableFuture<?> orchestrateAsyncStep(
                BaseNode current,
                Map<String, Object> sharedContext,
                Map<String, Object> nodeParams,
                Object lastAction // Action from previous step, not used in return value of this step
        ) {
            if (current == null) {
                // End of flow, return the last action that *led* here.
                return CompletableFuture.completedFuture(lastAction);
            }

            current.setParams(nodeParams);

            CompletableFuture<?> actionFuture;
            if (current instanceof AsyncNode) {
                // If it's an AsyncNode, call its async lifecycle
                actionFuture = ((AsyncNode) current).runAsyncLifecycle(sharedContext);
            } else {
                // If it's a synchronous node, run it synchronously and wrap in a future
                try {
                    Object syncAction = current.runLifecycle(sharedContext);
                    actionFuture = CompletableFuture.completedFuture(syncAction);
                } catch (Throwable t) {
                    actionFuture = CompletableFuture.failedFuture(t); // Propagate sync error
                }
            }

            // Once the current node completes, determine the next node and recurse
            return actionFuture.thenCompose(action -> {
                BaseNode nextNode = current.getNextNode(action);
                // Pass the *action* from the completed node to the recursive call
                return orchestrateAsyncStep(nextNode, sharedContext, nodeParams, action);
            });
        }

         /** Executes this async flow standalone. Issues warning if successors exist. */
        public CompletableFuture<R_ACTION> runAsync(Map<String, Object> sharedContext) {
             if (!successors.isEmpty()) {
                 logWarn("AsyncFlow " + getClass().getSimpleName() + " has successors, but runAsync() was called. Use a parent AsyncFlow.");
             }
             return runAsyncLifecycle(sharedContext);
        }
    }

    /**
     * An asynchronous flow that runs its sequence for each parameter set from `prepAsync`, sequentially.
     */
    public static abstract class AsyncBatchFlow<R_ACTION> extends AsyncFlow<R_ACTION> {

        public AsyncBatchFlow() { super(); }
        public AsyncBatchFlow(BaseNode<?, ?, ?> startNode) { super(startNode); }

        /** Subclasses MUST override prepAsync to return the list of parameter maps. */
        // ** FIX: Removed @Override **
        public abstract CompletableFuture<List<Map<String, Object>>> prepAsync(Map<String, Object> sharedContext);

        // Override sync prep to throw
        // ** FIX: Removed @Override **
        public final List<Map<String, Object>> prep(Map<String, Object> shared) {
            throw new PocketFlowException("Use prepAsync for AsyncBatchFlow");
        }

         /** Override postAsync to accept the List<Map> from prep. */
         public abstract CompletableFuture<R_ACTION> postAsync(Map<String, Object> sharedContext, List<Map<String, Object>> prepResult, Void execResult);

         // Override the base Flow post to allow the correct signature in the abstract method above
          @Override
          public final CompletableFuture<R_ACTION> postAsync(Map<String, Object> sharedContext, Void prepResult, R_ACTION execResult) {
              // This version shouldn't be called directly by the lifecycle due to runAsyncLifecycle override
               throw new PocketFlowException("Internal error: AsyncBatchFlow postAsync called with wrong signature.");
          }


        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected CompletableFuture<R_ACTION> runAsyncLifecycle(Map<String, Object> sharedContext) {
            // *** Call the overridden prepAsync which returns CompletableFuture<List<Map>> ***
            return this.prepAsync(sharedContext).thenCompose(batchParamsList -> {

                List<Map<String, Object>> finalBatchParams = (batchParamsList != null) ? batchParamsList : Collections.emptyList();

                CompletableFuture<Void> sequenceChain = CompletableFuture.completedFuture(null);

                // Chain orchestrations sequentially
                for (Map<String, Object> batchParams : finalBatchParams) {
                    sequenceChain = sequenceChain.thenCompose(v -> {
                        Map<String, Object> currentRunParams = new HashMap<>(this.params);
                         if (batchParams != null) {
                            currentRunParams.putAll(batchParams);
                         }
                        // Run orchestration for this item, discard its result (usually null/last action)
                        return orchestrateAsync(sharedContext, currentRunParams).thenApply(action -> null); // -> CompletableFuture<Void>
                    });
                }

                // After all sequential orchestrations are done, call postAsync
                return sequenceChain.thenCompose(v ->
                    // Cast is safe because we defined postAsync with List<Map> above
                     this.postAsync(sharedContext, finalBatchParams, null)
                 );
            });
        }
    }

     /**
     * An asynchronous flow that runs its sequence for each parameter set from `prepAsync`, in parallel.
     */
    public static abstract class AsyncParallelBatchFlow<R_ACTION> extends AsyncBatchFlow<R_ACTION> {

        public AsyncParallelBatchFlow() { super(); }
        public AsyncParallelBatchFlow(BaseNode<?, ?, ?> startNode) { super(startNode); }

        // Inherits prepAsync and postAsync definitions from AsyncBatchFlow

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected CompletableFuture<R_ACTION> runAsyncLifecycle(Map<String, Object> sharedContext) {
            // *** Call the overridden prepAsync which returns CompletableFuture<List<Map>> ***
            return this.prepAsync(sharedContext).thenCompose(batchParamsList -> {

                List<Map<String, Object>> finalBatchParams = (batchParamsList != null) ? batchParamsList : Collections.emptyList();

                if (finalBatchParams.isEmpty()) {
                     // If no batches, just call post directly
                    return this.postAsync(sharedContext, finalBatchParams, null);
                }

                List<CompletableFuture<?>> parallelOrchestrations = new ArrayList<>();

                // Start all orchestrations in parallel
                for (Map<String, Object> batchParams : finalBatchParams) {
                    Map<String, Object> currentRunParams = new HashMap<>(this.params);
                    if (batchParams != null) {
                        currentRunParams.putAll(batchParams);
                    }
                    // Add the future representing one full orchestration run to the list
                    parallelOrchestrations.add(orchestrateAsync(sharedContext, currentRunParams));
                }

                // Wait for all parallel orchestrations to complete
                return CompletableFuture.allOf(parallelOrchestrations.toArray(new CompletableFuture[0]))
                    .thenCompose(v ->
                        // After all are done, call postAsync
                         this.postAsync(sharedContext, finalBatchParams, null)
                    );
            });
        }
    }

    // --- Helper for AsyncParallelBatchNode ---

    /**
     * Gathers results from a list of CompletableFutures, preserving order.
     * If any future fails, the resulting future fails.
     */
    private static <T> CompletableFuture<List<T>> gatherFutures(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDoneFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return allDoneFuture.thenApply(v ->
            futures.stream()
                   .map(CompletableFuture::join) // join() is safe here because allOf ensures completion
                   .collect(Collectors.toList())
        );
    }


    // ========================================================================
    // Usage Example
    // ========================================================================
    public static void main(String[] args) throws Exception {

        // --- Example Node Implementations ---

        // Sets an initial number
        class SetNumberNode extends Node<Void, Integer, String> {
            private final int number;
            public SetNumberNode(int number) { this.number = number; }

            @Override public Void prep(Map<String, Object> ctx) { return null; } // P is Void
            @Override public Integer exec(Void prepResult) { // E is Integer
                Integer multiplier = (Integer) params.getOrDefault("multiplier", 1);
                return number * multiplier;
            }
            @Override public String post(Map<String, Object> ctx, Void p, Integer e) { // R is String
                ctx.put("currentValue", e);
                return e > 20 ? "over_20" : "default"; // Action based on result
            }
        }

         // Adds to the current number (Async)
        class AddNumberAsyncNode extends AsyncNode<Integer, Integer, String> {
             private final int numberToAdd;
             public AddNumberAsyncNode(int numberToAdd) { this.numberToAdd = numberToAdd;}

             // P is Integer (gets current value)
             @Override public CompletableFuture<Integer> prepAsync(Map<String, Object> ctx) {
                 return CompletableFuture.completedFuture((Integer)ctx.get("currentValue"));
             }
             // E is Integer (the new sum)
             @Override public CompletableFuture<Integer> execAsync(Integer currentValue) {
                 int newValue = currentValue + numberToAdd;
                 System.out.println("Async Add: " + currentValue + " + " + numberToAdd + " = " + newValue);
                 // Simulate async work
                 return CompletableFuture.supplyAsync(() -> newValue, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
             }
             // Override sync exec to enforce async usage
             @Override public Integer exec(Integer prepResult) { throw new PocketFlowException("Use execAsync"); }

             // R is String (action)
             @Override public CompletableFuture<String> postAsync(Map<String, Object> ctx, Integer p, Integer e) {
                 ctx.put("currentValue", e); // Update context with the new value
                 return CompletableFuture.completedFuture("added");
             }
        }

         // Simple node to print final result
         class PrintResultNode extends Node<Integer, Void, Void> {
             @Override public Integer prep(Map<String, Object> ctx) { return (Integer)ctx.get("currentValue"); }
             @Override public Void exec(Integer prepResult) {
                 System.out.println("Final Result: " + prepResult);
                 return null;
             }
             // No post needed, returns Void (null action, flow ends)
         }


         // --- Example Flow Definition ---
         SetNumberNode start = new SetNumberNode(10);
         AddNumberAsyncNode addAsync = new AddNumberAsyncNode(5);
         PrintResultNode printFinal = new PrintResultNode();
         PrintResultNode printOver20 = new PrintResultNode(); // Separate instance for different path


         // Define connections
         start.next(addAsync, "default");       // If SetNumberNode returns "default" (<=20)
         start.next(printOver20, "over_20"); // If SetNumberNode returns "over_20"
         addAsync.next(printFinal, "added");    // After adding, print


         // --- Create and Run Async Flow ---
         AsyncFlow<String> asyncFlow = new AsyncFlow<>(start);
         Map<String, Object> sharedContext = new HashMap<>();

         System.out.println("Running Async Flow...");
         // Run the flow and wait for completion (for main example)
         String lastAction = (String) asyncFlow.runAsync(sharedContext).join(); // .join() waits and gets result or throws

         System.out.println("Async Flow completed. Last action: " + lastAction); // Should be "added"
         System.out.println("Context after run: " + sharedContext); // Should show {currentValue=15}

         System.out.println("\nRunning Async Flow with different initial params...");
         // Example of running with parameters that trigger the other branch
         Map<String, Object> sharedContext2 = new HashMap<>();
         asyncFlow.setParams(Map.of("multiplier", 3)); // Make SetNumberNode produce > 20
         lastAction = (String) asyncFlow.runAsync(sharedContext2).join();

         System.out.println("Async Flow completed. Last action: " + lastAction); // Should be "over_20"
         System.out.println("Context after run 2: " + sharedContext2); // Should show {currentValue=30}

    }
}