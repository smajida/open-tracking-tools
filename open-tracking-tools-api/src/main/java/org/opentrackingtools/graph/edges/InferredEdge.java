package org.opentrackingtools.graph.edges;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.linearref.LengthIndexedLine;
import com.vividsolutions.jts.linearref.LengthLocationMap;
import com.vividsolutions.jts.linearref.LocationIndexedLine;

public interface InferredEdge extends Comparable<InferredEdge> {

  public abstract Coordinate getCenterPointCoord();

  public abstract Vector getEndPoint();

  public abstract Geometry getGeometry();

  public abstract Double getLength();

  public abstract Vector getStartPoint();

  public abstract boolean isNullEdge();

  public abstract boolean hasReverse();

  public abstract String getEdgeId();
  
  public abstract Object getBackingEdge();

  public abstract void update(MultivariateGaussian stateBelief);

}