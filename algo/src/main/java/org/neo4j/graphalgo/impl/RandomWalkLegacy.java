package org.neo4j.graphalgo.impl;

import org.neo4j.graphalgo.api.ArrayGraphInterface;
import org.neo4j.graphalgo.api.Degrees;
import org.neo4j.graphalgo.api.IdMapping;
import org.neo4j.graphalgo.core.IdMap;
import org.neo4j.graphdb.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class RandomWalkLegacy extends Algorithm<RandomWalkLegacy> {

    private ArrayGraphInterface arrayGraphInterface;
    private Degrees degrees;
    private IdMap mapping;
    private HashSet<Integer> startNodeIds;
    private HashSet<Integer> endNodeIds;
    private int randomWalkLength;
    private int numberOfrandomWalks;
    private ArrayList<ArrayList<Integer>> metapaths;
    private ArrayList<Integer> metapathsWeights;
    private Random random;
    private final static int DEFAULT_WEIGHT = 5;

    public RandomWalkLegacy(IdMapping idMapping,
                            ArrayGraphInterface arrayGraphInterface,
                            Degrees degrees,
                            HashSet<Long> startNodeIds,
                            HashSet<Long> endNodeIds,
                            int numberOfRandomWalks,
                            int randomWalkLength){

        this.startNodeIds = new HashSet<>();
        this.endNodeIds = new HashSet<>();
        convertIds(idMapping, startNodeIds, this.startNodeIds);
        convertIds(idMapping, endNodeIds, this.endNodeIds);
        this.arrayGraphInterface = arrayGraphInterface;
        this.degrees = degrees;
        this.numberOfrandomWalks = numberOfRandomWalks;
        this.randomWalkLength = randomWalkLength;
        this.metapaths = new ArrayList<>();
        this.metapathsWeights = new ArrayList<>();
        this.random = new Random();
    }

    private void convertIds(IdMapping idMapping, HashSet<Long> incomingIds, HashSet<Integer> convertedIds){
        for(long l : incomingIds){
          convertedIds.add(idMapping.toMappedNodeId(l));
        }
    }

    public Result compute() {

        for (int nodeId : startNodeIds) {
            runRandomWalk(nodeId);
        }

        HashSet<String> finalMetaPaths = new HashSet<>();

        for(ArrayList<Integer> metaPath :metapaths){
            finalMetaPaths.add(metaPath.stream().map(Object::toString).collect(Collectors.joining(" | ")) + "\n");
        }

        for (String s:finalMetaPaths) {
            System.out.println(s);
        }

        return new Result();
    }

    private void runRandomWalk(int startNodeId){
        for(int i=0; i < numberOfrandomWalks; i++) {
            int nodeHopId = startNodeId;
            ArrayList<Integer> metapath = new ArrayList<>();
            metapath.add(getValue(nodeHopId));
            for(int j=1; j <= randomWalkLength; j++){
                int degree = degrees.degree(nodeHopId, Direction.OUTGOING);
                if (degree <= 0) {
                    break;
                }
                int randomEdgeIndex= random.nextInt(degree);
                nodeHopId = arrayGraphInterface.getOutgoingNodes(nodeHopId)[randomEdgeIndex];
                metapath.add(getValue(nodeHopId));
            }
        }
    }

    private int getValue(int nodeMappedId) {
        return nodeMappedId;
    }

    public Stream<RandomWalkLegacy.Result> resultStream() {
        return IntStream.range(0, 1).mapToObj(result -> new Result());
    }

    @Override
    public RandomWalkLegacy me() { return this; }

    @Override
    public RandomWalkLegacy release() {
        return null;
    }

    /**
     * Result class used for streaming
     */
    public static final class Result {

        public Result() {

        }

        @Override
        public String toString() {
            return "Result{}";
        }
    }

    public void showTop(int n){
        for (int i = 0; i < n; i++){
            System.out.println(i + ". " + metapaths.get(i).stream().map(Object::toString).collect(Collectors.joining(" | ")) + "  " + metapathsWeights.get(i));
        }
    }

    public void weight (int index, int weight) throws Exception {
        if(weight <= 0 || weight > 10)
            throw new Exception("Weight needs to be in range (0;10]");
        metapathsWeights.set(index, weight);
    }

    public float similarity (){
        float sum = 0;
        for (int weight: metapathsWeights){
            sum += weight;
        }
        return sum/metapathsWeights.size()/10;
    }



}