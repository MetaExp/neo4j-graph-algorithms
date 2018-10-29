package org.neo4j.graphalgo.metaPathComputationProcs;

import org.neo4j.graphalgo.core.GraphLoader;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraph;
import org.neo4j.graphalgo.core.heavyweight.HeavyGraphFactory;
import org.neo4j.graphalgo.impl.metapath.getSchema.GetSchema;
import org.neo4j.graphalgo.impl.metapath.Pair;
import org.neo4j.graphalgo.impl.metapath.labels.LabelMapping;
import org.neo4j.graphalgo.results.metaPathComputationResults.GetSchemaResult;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Procedure;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Stream;

public class GetSchemaProc {
    @Context
    public GraphDatabaseAPI api;

    @Context
    public Log log;

    @Context
    public KernelTransaction transaction;

    @Procedure("algo.GetSchema")
    @Description("CALL algo.GetSchema() YIELD schema: \n" +
            "return schema as adjacency list \n")

    public Stream<GetSchemaResult> GetSchema() throws FileNotFoundException {

        final GetSchemaResult.Builder builder = GetSchemaResult.builder();

        ArrayList<HashSet<Pair>> schema = null;
        HashMap<Integer, Integer> reversedLabelDictionary = null;
        boolean notYetComputed = false;
        try {
            FileInputStream fileIn = new FileInputStream("metagraph.ser");
            ObjectInputStream in = new ObjectInputStream(fileIn);
            schema = (ArrayList<HashSet<Pair>>) in.readObject();
            in.close();
            fileIn.close();

            fileIn = new FileInputStream("reversedLabelDictionary.ser");
            in = new ObjectInputStream(fileIn);
            reversedLabelDictionary = (HashMap<Integer, Integer>) in.readObject();
            in.close();
            fileIn.close();
        } catch (IOException i) {
            notYetComputed = true;
        } catch (ClassNotFoundException c) {
            c.printStackTrace();
            notYetComputed = true;
        }

        if(notYetComputed){
            final HeavyGraph graph;

            graph = (HeavyGraph) new GraphLoader(api)
                    .asUndirected(true)
                    .withLabelAsProperty(true)
                    .load(HeavyGraphFactory.class);

            LabelMapping labelMapping = null; // TODO
            final GetSchema algo = new GetSchema(graph, labelMapping);
            GetSchema.Result result = algo.compute();
            graph.release();

            schema = result.getSchema();
            reversedLabelDictionary = result.getReverseLabelDictionary();
        }

        builder.setSchema(schema);
        builder.setReverseLabelDictionary(reversedLabelDictionary);

        return Stream.of(builder.build());
    }
}
