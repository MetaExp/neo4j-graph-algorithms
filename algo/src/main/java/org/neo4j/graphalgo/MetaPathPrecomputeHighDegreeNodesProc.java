package org.neo4j.graphalgo;

import org.neo4j.graphalgo.core.GraphLoader;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraph;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraphFactory;
import org.neo4j.graphalgo.impl.metaPathComputation.MetaPathPrecomputeHighDegreeNodes;
import org.neo4j.graphalgo.results.MetaPathPrecomputeHighDegreeNodesResult;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MetaPathPrecomputeHighDegreeNodesProc {

    @Context
    public GraphDatabaseAPI api;

    @Context
    public Log log;

    @Context
    public KernelTransaction transaction;

    @Procedure("algo.metaPathPrecomputeHighDegreeNodes")
    @Description("CALL algo.metaPathPrecomputeHighDegreeNodes(length:int, ratioHighDegreeNodes:int) YIELD length: \n" +
            "Compute all metaPaths up to a metapath-length given by 'length' that start with a startNode and end with a endNode and saves them to a File called 'Precomputed_MetaPaths_Instances.txt' \n")

    public Stream<MetaPathPrecomputeHighDegreeNodesResult> computeAllMetaPaths(
            @Name(value = "length", defaultValue = "5") String lengthString,
            @Name(value = "ratioHighDegreeNodes", defaultValue = "1000000") String ratioHighDegreeNodesString) throws IOException {

        int length = Integer.valueOf(lengthString);
        int ratioHighDegreeNodes = Integer.valueOf(ratioHighDegreeNodesString);

        final MetaPathPrecomputeHighDegreeNodesResult.Builder builder = MetaPathPrecomputeHighDegreeNodesResult.builder();

        final HeavyGraph graph;

        graph = (HeavyGraph) new GraphLoader(api)
                .asUndirected(true)
                .withLabelAsProperty(true)
                .load(HeavyGraphFactory.class);


        final MetaPathPrecomputeHighDegreeNodes algo = new MetaPathPrecomputeHighDegreeNodes(graph, graph, graph, length, ratioHighDegreeNodes);
        HashMap<Integer, HashMap<String, HashSet<Integer>>> metaPaths = new HashMap<>();
        metaPaths = algo.compute().getFinalMetaPaths();
        builder.setMetaPaths(metaPaths);
        graph.release();
        //return algo.resultStream();
        //System.out.println(Stream.of(builder.build()));
        return Stream.of(builder.build());
    }
}
