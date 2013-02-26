package org.opentrackingtools.model;

import gov.sandia.cognition.math.matrix.Vector;

import org.apache.commons.lang.builder.CompareToBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.opentrackingtools.distributions.OnOffEdgeTransDistribution;
import org.opentrackingtools.estimators.AbstractRoadTrackingFilter;
import org.opentrackingtools.graph.InferenceGraph;
import org.opentrackingtools.paths.PathStateBelief;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

/**
 * This class represents the state of a vehicle, which is made up of the
 * vehicles location, whether it is on an edge, which path it took from its
 * previous location on an edge, and the distributions that determine these.
 * 
 * @author bwillard
 * 
 */
public class VehicleState implements Comparable<VehicleState> {

  private static final long serialVersionUID = 3229140254421801273L;

  /*
   * These members represent the state/parameter samples/sufficient statistics.
   */
  private final AbstractRoadTrackingFilter movementFilter;

  /**
   * This could be the 4D ground-coordinates dist. for free motion, or the 2D
   * road-coordinates, either way the tracking filter will check. Also, this
   * could be the prior or prior predictive distribution.
   */
  protected final PathStateBelief belief;

  /*-
   * Edge transition priors 
   * 1. edge off 
   * 2. edge on 
   * 3. edges transitions to others (one for all)
   * edges
   */
  protected final OnOffEdgeTransDistribution edgeTransitionDist;
  private final GpsObservation observation;
  private VehicleState parentState = null;

  private final InferenceGraph graph;

  private int hash = 0;

  public VehicleState(InferenceGraph inferredGraph, GpsObservation observation,
    AbstractRoadTrackingFilter updatedFilter, PathStateBelief belief, OnOffEdgeTransDistribution edgeTransitionDist,
    VehicleState parentState) {

    Preconditions.checkNotNull(inferredGraph);
    Preconditions.checkNotNull(observation);
    Preconditions.checkNotNull(updatedFilter);
    Preconditions.checkNotNull(belief);

    this.observation = observation;
    this.movementFilter = updatedFilter;
    this.belief = belief.clone();
    this.graph = inferredGraph;

    /*
     * Check that the state's location corresponds
     * to the last edge.
     */
    Preconditions.checkState(!belief.isOnRoad()
        || belief.getEdge().equals(Iterables.getLast(belief.getPath().getPathEdges())));

    this.edgeTransitionDist = edgeTransitionDist;

    this.parentState = parentState;
    /*
     * Reset the parent's parent state so that we don't keep these objects
     * forever.
     */

    final double timeDiff;
    if (observation.getPreviousObservation() != null) {
      timeDiff =
          (observation.getTimestamp().getTime() - observation.getPreviousObservation().getTimestamp().getTime()) / 1000d;
      this.movementFilter.setCurrentTimeDiff(timeDiff);
    }
  }

  public VehicleState(VehicleState other) {
    this.graph = other.graph;
    this.movementFilter = other.movementFilter.clone();
    this.belief = other.belief.clone();
    this.edgeTransitionDist = other.edgeTransitionDist.clone();
    this.observation = other.observation;
    this.parentState = other.parentState;
  }

  @Override
  public VehicleState clone() {
    return new VehicleState(this);
  }

  @Override
  public int compareTo(VehicleState arg0) {
    return oneStateCompareTo(this, arg0);
  }

  @Override
  public boolean equals(Object obj) {
    /*
     * We do this to avoid evaluating every parent down the chain.
     */
    if (!oneStateEquals(this, obj))
      return false;

    final VehicleState other = (VehicleState) obj;
    if (parentState == null) {
      if (other.parentState != null) {
        return false;
      }
    } else if (!oneStateEquals(parentState, other.parentState)) {
      return false;
    }

    return true;
  }

  public PathStateBelief getBelief() {
    return belief;
  }

  public OnOffEdgeTransDistribution getEdgeTransitionDist() {
    return edgeTransitionDist;
  }

  public InferenceGraph getGraph() {
    return graph;
  }

  /**
   * Returns ground-coordinate mean location
   * 
   * @return
   */
  public Vector getMeanLocation() {
    final Vector v = belief.getGroundState();
    return AbstractRoadTrackingFilter.getOg().times(v);
  }

  public AbstractRoadTrackingFilter getMovementFilter() {
    return movementFilter;
  }

  public GpsObservation getObservation() {
    return observation;
  }

  public VehicleState getParentState() {
    return parentState;
  }

  @Override
  public int hashCode() {
    /*
     * We do this to avoid evaluating every parent down the chain.
     */
    if (hash != 0) {
      return hash;
    } else {
      final int prime = 31;
      int result = 1;
      result = prime * result + oneStateHashCode(this);
      if (this.parentState != null)
        result = prime * result + oneStateHashCode(this.parentState);
      hash = result;
      return result;
    }
  }

  public void setParentState(VehicleState parentState) {
    this.parentState = parentState;
  }

  @Override
  public String toString() {
    ToStringBuilder builder = new ToStringBuilder(this);
    builder.append("belief", belief);
    builder.append("observation", observation);
    return builder.toString();
  }

  public static Vector getNonVelocityVector(Vector vector) {
    final Vector res;
    if (vector.getDimensionality() == 4)
      res = AbstractRoadTrackingFilter.getOg().times(vector);
    else
      res = AbstractRoadTrackingFilter.getOr().times(vector);
    return res;
  }

  public static long getSerialversionuid() {
    return serialVersionUID;
  }

  private static int oneStateCompareTo(VehicleState t, VehicleState o) {
    if (t == o)
      return 0;

    if (t == null) {
      if (o != null)
        return -1;
      else
        return 0;
    } else if (o == null) {
      return 1;
    }

    final CompareToBuilder comparator = new CompareToBuilder();
    comparator.append(t.belief, o.belief);
    comparator.append(t.getObservation(), o.getObservation());
    comparator.append(t.edgeTransitionDist, o.edgeTransitionDist);

    return comparator.toComparison();
  }

  protected static boolean oneStateEquals(Object thisObj, Object obj) {
    if (thisObj == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (thisObj.getClass() != obj.getClass()) {
      return false;
    }
    final VehicleState thisState = (VehicleState) thisObj;
    final VehicleState other = (VehicleState) obj;
    if (thisState.belief == null) {
      if (other.belief != null) {
        return false;
      }
    } else if (!thisState.belief.equals(other.belief)) {
      return false;
    }
    if (thisState.edgeTransitionDist == null) {
      if (other.edgeTransitionDist != null) {
        return false;
      }
    } else if (!thisState.edgeTransitionDist.equals(other.edgeTransitionDist)) {
      return false;
    }
    if (thisState.movementFilter == null) {
      if (other.movementFilter != null) {
        return false;
      }
    } else if (!thisState.movementFilter.equals(other.movementFilter)) {
      return false;
    }
    if (thisState.observation == null) {
      if (other.observation != null) {
        return false;
      }
    } else if (!thisState.observation.equals(other.observation)) {
      return false;
    }
    return true;
  }

  protected static int oneStateHashCode(VehicleState state) {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((state.belief == null) ? 0 : state.belief.hashCode());
    result = prime * result + ((state.edgeTransitionDist == null) ? 0 : state.edgeTransitionDist.hashCode());
    result = prime * result + ((state.movementFilter == null) ? 0 : state.movementFilter.hashCode());
    result = prime * result + ((state.observation == null) ? 0 : state.observation.hashCode());
    return result;
  }

}
