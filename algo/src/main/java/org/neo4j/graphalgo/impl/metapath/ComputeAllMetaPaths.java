package org.neo4j.graphalgo.impl.metapath;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.IntLongCursor;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.procedures.ObjectLongProcedure;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraph;
import org.neo4j.graphalgo.impl.metapath.labels.LabelMapping;
import org.neo4j.graphalgo.impl.metapath.labels.Tokens;

import java.util.concurrent.*;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class ComputeAllMetaPaths extends MetaPathComputation {

    private HeavyGraph graph;
    private LabelMapping labelMapping;
    private int metaPathLength;
    private int currentLabelId = 0;
    private PrintStream out;
    private PrintStream debugOut;
    private int printCount = 0;
    private long startTime;
    private short startEdgeLabel;

    public ComputeAllMetaPaths(HeavyGraph graph, LabelMapping labelMapping, int metaPathLength, PrintStream out) throws IOException {
        this.graph = graph;
        this.labelMapping = labelMapping;
        startEdgeLabel = labelMapping.getAllEdgeLabels()[0];
        this.metaPathLength = metaPathLength;
        this.out = out;//ends up in root/tests //or in dockerhome
        this.debugOut = new PrintStream(new FileOutputStream("Precomputed_MetaPaths_Debug.txt"));
    }

    public Result compute() {
        debugOut.println("started computation");
        debugOut.println("length: " + metaPathLength);
        startTime = System.nanoTime();

        ObjectLongMap<MetaPath> pathResults = computeMetaPaths();
        List<String> strings = new ArrayList<>(pathResults.size());
        pathResults.forEach((ObjectLongProcedure<MetaPath>)(path,count) -> {
            out.println(path.toString(labelMapping) + ":" + count);
            strings.add(String.format("%s\t%d",path.toString(),count));
        });

        long endTime = System.nanoTime();

        System.out.println("calculation took: " + String.valueOf(endTime - startTime));
        debugOut.println("actual amount of metaPaths: " + printCount);
        debugOut.println("total time past: " + (endTime - startTime));
        debugOut.println("finished computation");
        return new Result(strings);
    }

    private int combineLabels(short firstLabel, short secondLabel) {
        return (int)firstLabel << 16 | (secondLabel & 0xFFFF);
    }

    /*

    private List<List<String>> computeMetaPathsFromAllNodeLabels() throws InterruptedException {
        int processorCount = Runtime.getRuntime().availableProcessors();
        debugOut.println("ProcessorCount: " + processorCount);

        ExecutorService executor = Executors.newFixedThreadPool(processorCount);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (short nodeLabel : labelMapping.getAllNodeLabels()) {
            Future<T> future = executor.submit(new ComputeMetaPathFromNodeLabelTask(nodeLabel, metaPathLength));
            futures.add(future);
        }

        executor.shutdown();
        executor.awaitTermination(100, TimeUnit.SECONDS);

        return futures.stream().map(this::get).collect(Collectors.toList());
    }

    private <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    */
    static class CurrentState  {
        MetaPath path;
        int remainingLength;
        IntLongMap nodes = new IntLongHashMap();

        public CurrentState(short label, int length) {
            this.path = new MetaPath(label);
            this.remainingLength = length - 1;
        }

        public CurrentState() { }

        public CurrentState addNode(int id, long count) {
            this.nodes.putOrAdd(id,count,count);
            return this;
        }

        public CurrentState next(long count, short type, short label, int neighbourId) {
            CurrentState result = new CurrentState();
            result.path = path.extend(type, label);
            result.remainingLength = remainingLength - 1;
            return result.addNode(neighbourId, count);
        }

        public long totalCount() {
            long sum = 0;
            for (LongCursor cursor : this.nodes.values()) {
                sum += cursor.value;
            }
            return sum;
        }
    }

    private ObjectLongMap<MetaPath> computeMetaPaths() {
        ObjectContainer<CurrentState> states = loadNodes();
        ObjectLongMap<MetaPath> pathResults = new ObjectLongHashMap<>();
        recurse(states, pathResults);
        return pathResults;
    }

    private void recurse(ObjectContainer<CurrentState> states, ObjectLongMap<MetaPath> pathResults) {
        for (ObjectCursor<CurrentState> cursor : states) {
            CurrentState current = cursor.value;
            pathResults.put(current.path, current.totalCount());
            ObjectContainer<CurrentState> newStates = expand(current);
            if (newStates != null) {
               recurse(newStates, pathResults);
            }
        }
    }

    private ObjectContainer<CurrentState> loadNodes() {
        IntObjectMap<CurrentState> nodesByLabel = new IntObjectHashMap<>();
        for (short label : labelMapping.getAllNodeLabels()) {
            nodesByLabel.put(combineLabels((short)0,label), new CurrentState(label, metaPathLength));
        }
        labelMapping.forEachNode((nodeLabels) -> {
            for (short nodeLabel : nodeLabels.value) {
                nodesByLabel.get(combineLabels((short)0,nodeLabel)).addNode(nodeLabels.key, 1);
            }
        });
        return nodesByLabel.values();
    }


    private ObjectContainer<CurrentState> expand(CurrentState state) {
        if (state.remainingLength == 0) return null;
        IntObjectMap<CurrentState> newStates = new IntObjectHashMap<>();
        for (IntLongCursor node : state.nodes) {
            int nodeId = node.key;
            long count = node.value;
            for (int neighbourId : graph.getAdjacentNodes(nodeId)) {
                short label = labelMapping.getLabel(neighbourId);
                short type = labelMapping.getEdgeLabel(nodeId, neighbourId);
                int pair = combineLabels(type, label);
                if (newStates.containsKey(pair)) {
                    newStates.get(pair).addNode(neighbourId, count);
                } else  {
                    CurrentState newState = state.next(count, type, label, neighbourId);
                    newStates.put(pair, newState);
                }
            }
        }
        return newStates.values();
    }


    /*private void drainFutures() {
        List<Future<String>> futures = new ArrayList<>();
        Iterator<Future<String>> it = futures.iterator();
        while (it.hasNext()) {
            Future<String> next =  it.next();
            if (next.isDone()) {
                Future<String> data = futures.get();
                it.remove();
            }
        }

        List<String> strings = futures.stream().map(this::get).collect(Collectors.toList());
        for (Future<String> future : futures) {
            future.get();
        }
    }*/
    public static class MetaPath {
        short[] path;
        byte length;

        public MetaPath(short label) {
            this.path = new short[5];
            this.path[0] = label;
            this.length = 1;
        }

        public MetaPath() { }

        @Override
        public int hashCode() {
            if (length == 0) return 0;
            int hash = path[0];
            for (int i = 1; i < length; i++) {
                hash = hash*31 + path[i];
            }
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof MetaPath)) return false;
            MetaPath other = (MetaPath) o;
            if (length != other.length) return false;
            for (int i = 0; i < length; i++) {
                if (path[i] != other.path[i]) return false;
            }
            return true;
        }
        public String toString(LabelMapping mapping) {
            Tokens labels = mapping.getLabels();
            Tokens types = mapping.getTypes();
            StringBuilder sb = new StringBuilder(length * 10);
            for (int i = 0; i < length; i++) {
                int id = path[i];
                sb.append(i % 2 == 0 ? labels.name(id) : types.name(id));
                if (i < length -1) sb.append("|");
            }
            return sb.toString();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(length * 5);
            for (int i = 0; i < length; i++) {
                sb.append((int) path[i]);
                if (i < length -1) sb.append(" | ");
            }
            return sb.toString();
        }

        public void add(short label) {
            if (length == path.length) {
                path = Arrays.copyOf(path,length+5);
            }
            path[length++] = label;
        }

        public MetaPath copy() {
            MetaPath copy = new MetaPath();
            copy.length = length;
            copy.path = Arrays.copyOf(path, path.length + 2);
            return copy;
        }

        public MetaPath extend(short edgeLabel, short nodeLabel) {
            MetaPath result = copy();
            result.add(edgeLabel);
            result.add(nodeLabel);
            return result;
        }
    }

    private class ComputeMetaPathFromNodeLabelTask implements Callable<ObjectIntMap<MetaPath>> {
        short nodeLabel;

        short metaPathLength;
        ObjectIntMap<MetaPath> metaPaths;

        ComputeMetaPathFromNodeLabelTask(short nodeLabel, short metaPathLength) {
            this.nodeLabel = nodeLabel;
            this.metaPathLength = metaPathLength;
            this.metaPaths = new ObjectIntHashMap<>();
        }

        public ObjectIntMap<MetaPath> call() {
      //      computeMetaPathFromNodeLabel(nodeLabel, metaPathLength);
            return metaPaths;
        }

        /*
        public void computeMetaPathFromNodeLabel(short startNodeLabel, short metaPathLength) {
            MetaPath initialMetaPath = new MetaPath(startNodeLabel);
            HashMap<Integer, Integer> initialInstancesRow = initInstancesRow(startNodeLabel);
            computeMetaPathFromNodeLabel(initialMetaPath, initialInstancesRow, metaPathLength - 1);

        }

        private void computeMetaPathFromNodeLabel(MetaPath currentMetaPath, HashMap<Integer, Integer> currentInstances, int metaPathLength) {
            if (metaPathLength <= 0) {
                return;
            }

            ArrayList<HashMap<Integer, Integer>> nextInstances = calculateNextInstances(currentInstances);

            for (int edgeLabel : labelMapping.getAllEdgeLabels()) {
                for (short nodeLabel : labelMapping.getAllNodeLabels()) {
                    int instancesArrayIndex = labelDictionary.get(new Pair(edgeLabel, nodeLabel));
                    HashMap<Integer, Integer> nextInstancesForLabel = nextInstances.get(instancesArrayIndex);
                    if (!nextInstancesForLabel.isEmpty()) {
                        nextInstances.set(instancesArrayIndex, null);

                        MetaPath newMetaPath = currentMetaPath.extend(edgeLabel, nodeLabel);

                        if (metaPaths.containsKey(newMetaPath)) {
                            metaPaths.put(newMetaPath, metaPaths.get(newMetaPath) + 1);
                        } else {
                            metaPaths.put(newMetaPath, 1);
                        }

                        long instanceCountSum = 0;
                        for (int count : nextInstancesForLabel.values()) {//refactor to stream?
                            instanceCountSum += count;
                        }

                        ComputeAllMetaPaths.this.addMetaPathGlobal(newMetaPath, instanceCountSum);
                        computeMetaPathFromNodeLabel(newMetaPath, nextInstancesForLabel, metaPathLength - 1);
                    }
                }
            }
        }
        */
    }


    //TODO------------------------------------------------------------------------------------------------------------------


    @Override
    public ComputeAllMetaPaths me() {
        return this;
    }

    @Override
    public ComputeAllMetaPaths release() {
        return null;
    }

    /**
     * Result class used for streaming
     */
    public static final class Result {

        List<String> finalMetaPaths;

        public Result(List<String> finalMetaPaths) {
            this.finalMetaPaths = finalMetaPaths;
        }

        @Override
        public String toString() {
            return "Result{}";
        }

        public List<String> getFinalMetaPaths() {
            return finalMetaPaths;
        }
    }
}
