package org.openplans.tools.tracking.impl;

import java.util.Collections;
import java.util.List;

import org.openplans.tools.tracking.impl.graph.InferredEdge;
import org.openplans.tools.tracking.impl.graph.paths.InferredPath;
import org.openplans.tools.tracking.impl.graph.paths.PathEdge;
import org.openplans.tools.tracking.impl.util.OtpGraph;
import org.opentripplanner.routing.edgetype.PlainStreetEdge;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;

public class TrackingTestUtils {

  public static LineString makeGeometry(StreetVertex v0, StreetVertex v1) {
    GeometryFactory gf = new GeometryFactory();
    Coordinate[] coordinates = new Coordinate[] { v0.getCoordinate(),
        v1.getCoordinate() };
    return gf.createLineString(coordinates);
  }

  public static InferredPath makeTmpPath(OtpGraph graph, 
    boolean isBackward, Coordinate ... coords) {
    Preconditions.checkArgument(coords.length > 1);
    
    final List<InferredEdge> edges = Lists.newArrayList();
    StreetVertex vtLast = null;
    for (int i = 0; i < coords.length; i++) {
      final Coordinate coord = coords[i];
      StreetVertex vt = new IntersectionVertex(graph.getBaseGraph(), 
          "tmpVertex"+i, coord, "none");
      if (vtLast != null) {
        final double distance = vt.getCoordinate().distance(vtLast.getCoordinate());
        final LineString geom = TrackingTestUtils.makeGeometry(vtLast, vt);
        Edge edge = new PlainStreetEdge(
            vtLast,
            vt,
            geom,
//            isBackward ? vt : vtLast, 
//            isBackward ? vtLast : vt, 
//            isBackward ? (LineString) geom.reverse() : geom,
            "tmpEdge" + i, 
            distance, 
            StreetTraversalPermission.ALL, false);
        InferredEdge ie = new InferredEdge(edge, 1000 + i, graph);
        edges.add(ie);
      }
      vtLast = vt;
      
    }
    
    if (isBackward) {
      Collections.reverse(edges);
    }
    
    final List<PathEdge> pathEdges = Lists.newArrayList();
    double distToStart = 0;
    for (InferredEdge edge : edges) {
      PathEdge pe = PathEdge.getEdge(edge, (isBackward ? -1d : 1d) * distToStart, isBackward);
      distToStart += edge.getLength();
      pathEdges.add(pe);
    }
    
    return InferredPath.getInferredPath(pathEdges, isBackward); 
  }

}
