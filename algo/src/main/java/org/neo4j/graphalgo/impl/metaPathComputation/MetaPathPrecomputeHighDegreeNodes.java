package org.neo4j.graphalgo.impl.metaPathComputation;

import org.neo4j.graphalgo.api.ArrayGraphInterface;
import org.neo4j.graphalgo.api.Degrees;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraph;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


//TODO test correctness!

public class MetaPathPrecomputeHighDegreeNodes extends MetaPathComputation {

    private Degrees degrees;
    private HeavyGraph graph;
    private ArrayGraphInterface arrayGraphInterface;
    private ArrayList<Integer> metaPathsWeights;
    private int metaPathLength;
    private int currentLabelId = 0;
    private HashMap<Integer, HashMap<String, HashSet<Integer>>> duplicateFreeMetaPaths;
    private PrintStream out;
    private PrintStream debugOut;
    private int printCount = 0;
    private double estimatedCount;
    private long startTime;
    private HashMap<Integer, Integer> labelDictionary;
    private float ratioHighDegreeNodes;
    private List<Integer> maxDegreeNodes;
    private Semaphore threadSemaphore = new Semaphore(Runtime.getRuntime().availableProcessors()); //TODO maybe threadpool and such things would be better


    public MetaPathPrecomputeHighDegreeNodes(HeavyGraph graph, ArrayGraphInterface arrayGraphInterface, Degrees degrees, int metaPathLength, float ratioHighDegreeNodes) throws IOException {
        this.graph = graph;
        this.arrayGraphInterface = arrayGraphInterface;
        this.metaPathsWeights = new ArrayList<>();
        this.metaPathLength = metaPathLength;
        this.out = new PrintStream(new FileOutputStream("Precomputed_MetaPaths_HighDegree.txt"));//ends up in root/tests //or in dockerhome
        this.debugOut = new PrintStream(new FileOutputStream("Precomputed_MetaPaths_HighDegree_Debug.txt"));
        this.estimatedCount = Math.pow(arrayGraphInterface.getAllLabels().size(), metaPathLength + 1);
        this.labelDictionary = new HashMap<>();
        this.degrees = degrees;
        this.ratioHighDegreeNodes = ratioHighDegreeNodes;
        this.duplicateFreeMetaPaths = new HashMap<>();
    }

    public Result compute() throws Exception {
        debugOut.println("started computation");
        startTime = System.nanoTime();
        maxDegreeNodes = getMaxDegreeNodes();
        HashMap<Integer, HashMap<String, HashSet<Integer>>> finalMetaPaths = computeAllMetaPaths();
        long endTime = System.nanoTime();

        debugOut.println("calculation took: " + String.valueOf(endTime - startTime));
        debugOut.println("actual amount of metaPaths: " + printCount);
        debugOut.println("total time past: " + (endTime - startTime));
        debugOut.println("finished computation");

        System.out.println(endTime - startTime);
        return new Result(finalMetaPaths);
    }

    private void outputIndexStructure(int highDegreeNode, HashMap<String, HashSet<Integer>> metaPaths) {
        synchronized (out) {
            out.print(highDegreeNode + ":");
            metaPaths.forEach((metaPath, endNodes) -> out.print(metaPath + "=" + endNodes.stream().map(Object::toString).collect(Collectors.joining(",")) + "-"));
            out.print("\n");
        }
    }

    public HashMap<Integer, HashMap<String, HashSet<Integer>>> computeAllMetaPaths() throws Exception{

        initializeLabelDict();
        computeMetaPathsFromAllRelevantNodes();

        return duplicateFreeMetaPaths;
    }

    private void initializeLabelDict() {
        currentLabelId = 0;

        for (int nodeLabel : arrayGraphInterface.getAllLabels()) {
            assignIdToNodeLabel(nodeLabel);
        }
    }

    private int assignIdToNodeLabel(int nodeLabel) {
        labelDictionary.put(nodeLabel, currentLabelId);
        currentLabelId++;
        return currentLabelId - 1;
    }

    private void computeMetaPathsFromAllRelevantNodes() throws Exception {//TODO: rework for Instances
        ArrayList<ComputeMetaPathFromNodeThread> threads = new ArrayList<>(maxDegreeNodes.size());
        int i = 0;
        for (int nodeID : maxDegreeNodes) {
            threadSemaphore.acquire();
            ComputeMetaPathFromNodeThread thread = new ComputeMetaPathFromNodeThread(this, "thread--" + i, nodeID, metaPathLength);
            thread.start();
            threads.add(thread);
            i++;
        }

        for (ComputeMetaPathFromNodeThread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void computeMetaPathFromNodeLabel(ArrayList<Integer> pCurrentMetaPath, HashSet<Integer> pCurrentInstances, int pMetaPathLength) {
        Stack<ArrayList<Integer>> param1 = new Stack();
        Stack<HashSet<Integer>> param2 = new Stack();
        Stack<Integer> param3 = new Stack();
        param1.push(pCurrentMetaPath);
        param2.push(pCurrentInstances);
        param3.push(pMetaPathLength);

        ArrayList<Integer> currentMetaPath;
        HashSet<Integer> currentInstances;
        int metaPathLength;

        while (!param1.empty() && !param2.empty() && !param3.empty()) {
            currentMetaPath = param1.pop();
            currentInstances = param2.pop();
            metaPathLength = param3.pop();

            if (metaPathLength <= 0) {
                continue;
            }

            ArrayList<HashSet<Integer>> nextInstances = allocateNextInstances();
            fillNextInstances(currentInstances, nextInstances);
            currentInstances = null;//not sure if this helps or not
            for (int i = 0; i < nextInstances.size(); i++) {
                HashSet<Integer> nextInstancesForLabel = nextInstances.get(i);
                if (!nextInstancesForLabel.isEmpty()) {
                    ArrayList<Integer> newMetaPath = copyMetaPath(currentMetaPath);
                    int label = arrayGraphInterface.getLabel(nextInstancesForLabel.iterator().next()); //first element since all have the same label.
                    newMetaPath.add(label);
                    addMetaPath(newMetaPath, nextInstancesForLabel);

                    //nextInstances = null; // how exactly does this work?
                    param1.push(newMetaPath);
                    param2.push(nextInstancesForLabel);
                    param3.push(metaPathLength - 1);
                    //nextInstances.set(i, null);
                    //nextInstancesForLabel = null;
                }
            }
        }
    }


    private ArrayList<HashSet<Integer>> allocateNextInstances() {
        ArrayList<HashSet<Integer>> nextInstances = new ArrayList<>(arrayGraphInterface.getAllLabels().size());
        for (int i = 0; i < arrayGraphInterface.getAllLabels().size(); i++) {
            nextInstances.add(new HashSet<>());
        }

        return nextInstances;
    }

    private void fillNextInstances(HashSet<Integer> currentInstances, ArrayList<HashSet<Integer>> nextInstances) {
        for (int instance : currentInstances) {
            for (int nodeId : arrayGraphInterface.getAdjacentNodes(instance)) { //TODO: check if getAdjacentNodes works
                int label = arrayGraphInterface.getLabel(nodeId); //get the id of the label of the node
                int labelID = labelDictionary.get(label);
                nextInstances.get(labelID).add(nodeId); // add the node to the corresponding instances array
            }
        }
    }

    private ArrayList<Integer> copyMetaPath(ArrayList<Integer> currentMetaPath) {
        ArrayList<Integer> newMetaPath = new ArrayList<>();
        for (int label : currentMetaPath) {
            newMetaPath.add(label);
        }
        return newMetaPath;
    }

    private String addMetaPath(ArrayList<Integer> newMetaPath, HashSet<Integer> nextInstancesForLabel) {
        String joinedMetaPath = newMetaPath.stream().map(Object::toString).collect(Collectors.joining("|"));
        int nodeID = ((ComputeMetaPathFromNodeThread) Thread.currentThread()).getNodeID();
        duplicateFreeMetaPaths.get(nodeID).putIfAbsent(joinedMetaPath, nextInstancesForLabel);
        duplicateFreeMetaPaths.get(nodeID).get(joinedMetaPath).addAll(nextInstancesForLabel);

        return joinedMetaPath;
    }

    public void computeMetaPathFromNodeLabel(int nodeID, int metaPathLength) {
        duplicateFreeMetaPaths.put(nodeID, new HashMap<>());
        ArrayList<Integer> initialMetaPath = new ArrayList<>();
        //initialMetaPath.add(arrayGraphInterface.getLabel(nodeID)); //Not needed as the high degree node (start node) ID is given in the file and its label ID can be easily derived
        //TODO less hacky
        HashSet<Integer> instanceHS = new HashSet<>();
        instanceHS.add(nodeID);
        computeMetaPathFromNodeLabel(initialMetaPath, instanceHS, metaPathLength - 1);
        outputIndexStructure(nodeID, duplicateFreeMetaPaths.get(nodeID));
        duplicateFreeMetaPaths.remove(nodeID);
        threadSemaphore.release();
    }
/*
    private HashSet<Integer> initInstancesRow(int startNodeLabel) {
        int startNodeLabelId = labelDictionary.get(startNodeLabel);
        HashSet<Integer> row = initialInstances.get(startNodeLabelId);
        return row;
    }*/

    //TODO------------------------------------------------------------------------------------------------------------------
    public Stream<org.neo4j.graphalgo.impl.metaPathComputation.ComputeAllMetaPaths.Result> resultStream() {
        return IntStream.range(0, 1).mapToObj(result -> new org.neo4j.graphalgo.impl.metaPathComputation.ComputeAllMetaPaths.Result(new HashSet<>()));
    }

    @Override
    public MetaPathPrecomputeHighDegreeNodes me() {
        return this;
    }

    @Override
    public MetaPathPrecomputeHighDegreeNodes release() {
        return null;
    }

    /**
     * Result class used for streaming
     */
    public static final class Result {

        HashMap<Integer, HashMap<String, HashSet<Integer>>> finalMetaPaths;

        public Result(HashMap<Integer, HashMap<String, HashSet<Integer>>> finalMetaPaths) {
            this.finalMetaPaths = finalMetaPaths;
        }

        @Override
        public String toString() {
            return "Result{}";
        }

        public HashMap<Integer, HashMap<String, HashSet<Integer>>> getFinalMetaPaths() {
            return finalMetaPaths;
        }
    }

    private List<Integer> getMaxDegreeNodes() {
        ArrayList<Integer> nodeList = new ArrayList();
        List<Integer> maxDegreeNodes;
        graph.forEachNode(nodeList::add);
        nodeList.sort(new DegreeComparator(graph)); //TODO Use Array instead of list?

        maxDegreeNodes = nodeList.subList((int) (nodeList.size() - Math.ceil((double) nodeList.size() * ratioHighDegreeNodes)), nodeList.size());
        for (int nodeID : maxDegreeNodes) { //TODO always consecutive? (without gap)
            debugOut.println("nodeID: " + nodeID + "; degree: " + graph.degree(nodeID, Direction.BOTH) + "; label: " + graph.getLabel(nodeID));

        }
        return maxDegreeNodes;
    }
}


class DegreeComparator implements Comparator<Integer> {
    HeavyGraph graph;

    DegreeComparator(HeavyGraph graph) {
        this.graph = graph;
    }

    @Override
    public int compare(Integer a, Integer b) {
        return Integer.compare(graph.degree(a, Direction.BOTH), graph.degree(b, Direction.BOTH));
    }
}