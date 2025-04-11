package io.github.the_pocket;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.the_pocket.PocketFlow.*;

class PocketFlowTest {

    // --- Test Node Implementations ---

    static class SetNumberNode extends Node<Void, Integer> {
        private final int number;
        public SetNumberNode(int number) { this.number = number; }
        @Override public Integer exec(Void prepResult) { return number * (Integer) params.getOrDefault("multiplier", 1); }
        @Override public String post(Map<String, Object> ctx, Void p, Integer e) {
             ctx.put("currentValue", e);
             return e > 20 ? "over_20" : "default";
        }
    }

    static class AddNumberNode extends Node<Integer, Integer> {
         private final int numberToAdd;
         public AddNumberNode(int numberToAdd) { this.numberToAdd = numberToAdd;}
         @Override public Integer prep(Map<String, Object> ctx) { return (Integer)ctx.get("currentValue"); }
         @Override public Integer exec(Integer currentValue) { return currentValue + numberToAdd; }
         @Override public String post(Map<String, Object> ctx, Integer p, Integer e) {
             ctx.put("currentValue", e);
             return "added";
         }
    }

    static class ResultCaptureNode extends Node<Integer, Void> {
         @Override public Integer prep(Map<String, Object> ctx) { return (Integer)ctx.getOrDefault("currentValue", -999); }
         @Override public Void exec(Integer prepResult) {
             params.put("capturedValue", prepResult);
             return null;
         }
    }

    static class SimpleLogNode extends Node<Void, String> {
         @Override public String exec(Void prepResult) {
             String message = "SimpleLogNode executed with multiplier: " + params.get("multiplier");
             return message;
         }
         @Override public String post(Map<String, Object> ctx, Void p, String e) {
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

        Flow syncFlow = new Flow(start);
        Map<String, Object> sharedContext = new HashMap<>();
        String lastAction = syncFlow.run(sharedContext);

        assertNull(lastAction);
        assertEquals(15, sharedContext.get("currentValue"));
        assertEquals(15, captureFinal.params.get("capturedValue"));
    }

    @Test
    void testBranchingFlow() {
        SetNumberNode start = new SetNumberNode(10);
        AddNumberNode addSync = new AddNumberNode(5);
        ResultCaptureNode captureFinalDefault = new ResultCaptureNode();
        ResultCaptureNode captureFinalOver20 = new ResultCaptureNode();

        start.next(addSync, "default").next(captureFinalDefault, "added");
        start.next(captureFinalOver20, "over_20");

        Flow syncFlow = new Flow(start);
        Map<String, Object> sharedContext = new HashMap<>();

        syncFlow.setParams(Map.of("multiplier", 3));
        String lastAction = syncFlow.run(sharedContext);

        assertNull(lastAction);
        assertEquals(30, sharedContext.get("currentValue"));
        assertEquals(30, captureFinalOver20.params.get("capturedValue"));
        assertNull(captureFinalDefault.params.get("capturedValue"));
    }

    @Test
    void testBatchFlowExecution() {
        class MyTestBatchFlow extends BatchFlow {
            public MyTestBatchFlow(BaseNode<?, ?> startNode) { super(startNode); }
            @Override
            public List<Map<String, Object>> prepBatch(Map<String, Object> sharedContext) {
                return List.of( Map.of("multiplier", 2), Map.of("multiplier", 4) );
            }
            @Override
            public String postBatch(Map<String, Object> sharedContext, List<Map<String, Object>> batchPrepResult) {
                sharedContext.put("postBatchCalled", true);
                return "batch_complete";
            }
        }

        MyTestBatchFlow batchFlow = new MyTestBatchFlow(new SimpleLogNode());
        Map<String, Object> batchContext = new HashMap<>();
        String result = batchFlow.run(batchContext);

        assertEquals("batch_complete", result);
        assertTrue((Boolean)batchContext.get("postBatchCalled"));
        assertEquals("SimpleLogNode executed with multiplier: 2", batchContext.get("last_message_from_batch_2"));
        assertEquals("SimpleLogNode executed with multiplier: 4", batchContext.get("last_message_from_batch_4"));
    }
}