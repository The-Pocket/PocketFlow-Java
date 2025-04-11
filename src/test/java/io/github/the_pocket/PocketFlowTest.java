package io.github.the_pocket; 

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Import static inner classes from PocketFlow for use in tests
import io.github.the_pocket.PocketFlow.*;

class PocketFlowTest {

    // --- Test Node Implementations (used only within tests) ---

    static class SetNumberNode extends Node<Void, Integer, String> {
        private final int number;
        public SetNumberNode(int number) { this.number = number; }
        @Override public Integer exec(Void prepResult) { return number * (Integer) params.getOrDefault("multiplier", 1); }
        @Override public String post(Map<String, Object> ctx, Void p, Integer e) { ctx.put("currentValue", e); return e > 20 ? "over_20" : "default"; }
    }

    static class AddNumberNode extends Node<Integer, Integer, String> {
         private final int numberToAdd;
         public AddNumberNode(int numberToAdd) { this.numberToAdd = numberToAdd;}
         @Override public Integer prep(Map<String, Object> ctx) { return (Integer)ctx.get("currentValue"); }
         @Override public Integer exec(Integer currentValue) { return currentValue + numberToAdd; }
         @Override public String post(Map<String, Object> ctx, Integer p, Integer e) { ctx.put("currentValue", e); return "added"; }
    }

    static class ResultCaptureNode extends Node<Integer, Void, Void> {
         // Captures the value in exec for checking
         @Override public Integer prep(Map<String, Object> ctx) { return (Integer)ctx.getOrDefault("currentValue", -999); } // Default if missing
         @Override public Void exec(Integer prepResult) {
             params.put("capturedValue", prepResult); // Store in params for assertion
             System.out.println("ResultCaptureNode captured: " + prepResult);
             return null;
         }
    }

    static class SimpleLogNode extends Node<Void, String, Void> {
         @Override public String exec(Void prepResult) {
             String message = "SimpleLogNode executed with multiplier: " + params.get("multiplier");
             System.out.println(message);
             return message;
         }
         @Override public Void post(Map<String, Object> ctx, Void p, String e) {
             ctx.put("last_message_from_batch_" + params.get("multiplier"), e);
             return null;
         }
    }

    // --- Test Methods ---

    @Test
    void testSimpleLinearFlow() {
        SetNumberNode start = new SetNumberNode(10);
        AddNumberNode addSync = new AddNumberNode(5);
        ResultCaptureNode captureFinal = new ResultCaptureNode();

        start.next(addSync, "default").next(captureFinal, "added");

        Flow<String> syncFlow = new Flow<>(start);
        Map<String, Object> sharedContext = new HashMap<>();
        String lastAction = syncFlow.run(sharedContext);

        assertNull(lastAction, "Final node should return null action");
        assertEquals(15, sharedContext.get("currentValue"), "Final value should be 15");
        assertEquals(15, captureFinal.params.get("capturedValue"), "ResultCaptureNode should capture 15");
    }

    @Test
    void testBranchingFlow() {
        SetNumberNode start = new SetNumberNode(10);
        AddNumberNode addSync = new AddNumberNode(5); // Won't run if branch taken
        ResultCaptureNode captureFinalDefault = new ResultCaptureNode();
        ResultCaptureNode captureFinalOver20 = new ResultCaptureNode();

        start.next(addSync, "default").next(captureFinalDefault, "added");
        start.next(captureFinalOver20, "over_20"); // Branch if > 20

        Flow<String> syncFlow = new Flow<>(start);
        Map<String, Object> sharedContext = new HashMap<>();

        // Test the "over_20" branch
        syncFlow.setParams(Map.of("multiplier", 3)); // 10 * 3 = 30
        String lastAction = syncFlow.run(sharedContext);

        assertNull(lastAction, "Final node should return null action");
        assertEquals(30, sharedContext.get("currentValue"), "Value should be 30");
        assertEquals(30, captureFinalOver20.params.get("capturedValue"), "captureFinalOver20 should capture 30");
        assertNull(captureFinalDefault.params.get("capturedValue"), "captureFinalDefault should not have run");
    }

    @Test
    void testBatchFlowExecution() {
        class MyTestBatchFlow extends BatchFlow<Void> {
            public MyTestBatchFlow(BaseNode<?, ?, ?> startNode) { super(startNode); }
            @Override
            public List<Map<String, Object>> prepBatch(Map<String, Object> sharedContext) {
                return List.of( Map.of("multiplier", 2), Map.of("multiplier", 4) );
            }
            @Override
            public Void postBatch(Map<String, Object> sharedContext, List<Map<String, Object>> batchPrepResult) {
                sharedContext.put("postBatchCalled", true);
                return null;
            }
        }

        MyTestBatchFlow batchFlow = new MyTestBatchFlow(new SimpleLogNode());
        Map<String, Object> batchContext = new HashMap<>();
        Void result = batchFlow.run(batchContext);

        assertNull(result, "BatchFlow postBatch should return null");
        assertTrue((Boolean)batchContext.get("postBatchCalled"), "postBatch should have been called");
        assertEquals("SimpleLogNode executed with multiplier: 2", batchContext.get("last_message_from_batch_2"));
        assertEquals("SimpleLogNode executed with multiplier: 4", batchContext.get("last_message_from_batch_4"));
    }
}