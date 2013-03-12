package org.opentrackingtools.updater;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.ParticleFilter.Updater;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.util.AbstractCloneableSerializable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.opentrackingtools.VehicleStateInitialParameters;
import org.opentrackingtools.distributions.CountedDataDistribution;
import org.opentrackingtools.distributions.OnOffEdgeTransDistribution;
import org.opentrackingtools.distributions.PathStateDistribution;
import org.opentrackingtools.distributions.PathStateMixtureDensityModel;
import org.opentrackingtools.estimators.MotionStateEstimatorPredictor;
import org.opentrackingtools.graph.InferenceGraph;
import org.opentrackingtools.graph.InferenceGraphEdge;
import org.opentrackingtools.graph.InferenceGraphSegment;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.SimpleBayesianParameter;
import org.opentrackingtools.model.VehicleState;
import org.opentrackingtools.paths.Path;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.paths.PathState;
import org.opentrackingtools.util.GeoUtils;
import org.opentrackingtools.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * This is an updater that produces new states from paths generated by sampling
 * edges along the predicted motion state.
 * 
 * @author bwillard
 * 
 * @param <O>
 */
public class VehicleTrackingBootstrapUpdater<O extends GpsObservation>
    extends AbstractCloneableSerializable implements
    Updater<O, VehicleState<O>> {

  private static final Logger _log = LoggerFactory
      .getLogger(VehicleTrackingBootstrapUpdater.class);

  protected static final long maxGraphBoundsResampleTries =
      (long) 1e6;

  private static final long serialVersionUID = 2884138088944317656L;

  protected InferenceGraph inferenceGraph;

  protected O initialObservation;

  protected VehicleStateInitialParameters parameters;

  protected Random random;

  public long seed;

  public VehicleTrackingBootstrapUpdater(O obs,
    InferenceGraph inferredGraph,
    VehicleStateInitialParameters parameters, Random rng) {
    this.initialObservation = obs;
    this.inferenceGraph = inferredGraph;
    if (rng == null) {
      this.random = new Random();
    } else {
      this.random = rng;
    }
    this.parameters = parameters;
  }

  @Override
  public double computeLogLikelihood(VehicleState<O> particle,
    O observation) {
    double logLikelihood = 0d;
    logLikelihood +=
        particle.getMotionStateParam().getConditionalDistribution()
            .getProbabilityFunction()
            .logEvaluate(observation.getProjectedPoint());
    return logLikelihood;
  }

  /**
   * Create vehicle states from the nearby edges.
   */
  @Override
  public DataDistribution<VehicleState<O>> createInitialParticles(
    int numParticles) {
    final DataDistribution<VehicleState<O>> retDist =
        new CountedDataDistribution<VehicleState<O>>(true);

    /*
     * Start by creating an off-road vehicle state with which we can obtain the surrounding
     * edges.
     */
    final VehicleState<O> nullState =
        VehicleState.constructInitialVehicleState(this.parameters, this.inferenceGraph, this.initialObservation, this.random,
            PathEdge.nullPathEdge);
    final MultivariateGaussian initialMotionStateDist =
        nullState.getMotionStateParam().getParameterPrior();
    final Collection<InferenceGraphSegment> edges =
        this.inferenceGraph.getNearbyEdges(initialMotionStateDist,
            initialMotionStateDist.getCovariance());

    for (int i = 0; i < numParticles; i++) {
      /*
       * From the surrounding edges, we create states on those edges.
       */
      final DataDistribution<VehicleState<O>> statesOnEdgeDistribution =
          new CountedDataDistribution<VehicleState<O>>(true);
      
      final double nullLogLikelihood =
          nullState.getEdgeTransitionParam()
              .getConditionalDistribution()
              .getProbabilityFunction().logEvaluate(InferenceGraphEdge.nullGraphEdge)
              + this.computeLogLikelihood(nullState,
                  this.initialObservation);

      statesOnEdgeDistribution
          .increment(nullState, nullLogLikelihood);

      for (final InferenceGraphSegment line : edges) {
        
        PathEdge startPathEdge = new PathEdge(line, false); 
        VehicleState<O> stateOnEdge = VehicleState.constructInitialVehicleState(
            parameters, inferenceGraph, initialObservation, random, startPathEdge);

        final double logLikelihood =
            stateOnEdge.getEdgeTransitionParam()
                .getConditionalDistribution()
                .getProbabilityFunction().logEvaluate(startPathEdge.getInferenceGraphEdge())
                + this.computeLogLikelihood(stateOnEdge,
                    this.initialObservation);

        statesOnEdgeDistribution
            .increment(stateOnEdge, logLikelihood);
      }

      retDist.increment(statesOnEdgeDistribution.sample(this.random));
    }

    return retDist;
  }

  @Override
  public VehicleState<O> update(VehicleState<O> previousState) {

    final VehicleState<O> updatedState =
        new VehicleState<O>(previousState);
    final MotionStateEstimatorPredictor motionStatePredictor =
        new MotionStateEstimatorPredictor(updatedState, this.random,
            (double) this.parameters.getInitialObsFreq());

    /*
     * Predict new location, i.e. project forward
     */
    final MultivariateGaussian predictedMotionState =
        motionStatePredictor
            .createPredictiveDistribution(updatedState
                .getMotionStateParam().getParameterPrior());
    /*
     * Add some transition error and set this as the
     * new motion state for our new vehicle state.
     */
    final Vector noisyPredictedState =
        motionStatePredictor.sampleStateTransDist(
            predictedMotionState.getMean(), this.random);
    predictedMotionState.setMean(noisyPredictedState);
    updatedState.getMotionStateParam().setParameterPrior(
        predictedMotionState);

    /*
     * Initialize the first path edge, distance moved tally
     * and edge transition distribution, from which we sample our
     * edge movements. 
     */
    final List<InferenceGraphEdge> edges = Lists.newArrayList();
    final boolean isBackward =
        predictedMotionState.getInputDimensionality() == 4
            || predictedMotionState.getMean().getElement(0) >= 0d
            ? false : true;
    InferenceGraphEdge prevEdge =
        updatedState.getPathStateParam().getValue().getEdge().getInferenceGraphEdge();
    double distance;
    if (!prevEdge.isNullEdge()) {
      distance = (isBackward ? 1d : -1d) * prevEdge.getLength();
    } else {
      distance = 0d;
    }
    final OnOffEdgeTransDistribution edgeTransDistribution =
        updatedState.getEdgeTransitionParam()
            .getConditionalDistribution();
    InferenceGraphEdge newEdge = null;
    /*
     * Sample edges until we go off-road (which should only
     * be in the beginning, see below), or until we sample
     * the same edge twice (or the one we started on).
     */
    do {
      /*
       * When we've already started an on-road path we don't 
       * go off-road.  At least not yet.
       * To accomplish this, we perform a little hack and
       * remove the off-road state from the possible transitions.
       */
      if (!edges.isEmpty() && !prevEdge.isNullEdge()) {
        edgeTransDistribution.getDomain().remove(
            InferenceGraphEdge.nullGraphEdge);
      }
      
      newEdge = edgeTransDistribution.sample(this.random);

      if (!newEdge.isNullEdge()) {
        distance += prevEdge.getLength();
        prevEdge = newEdge;
      } else {
        prevEdge = InferenceGraphEdge.nullGraphEdge;
      }
      edges.add(prevEdge);

    } while (!prevEdge.isNullEdge()
        && !prevEdge.equals(newEdge));

    final Path newPath;
    if (Iterables.getFirst(edges, null).isNullEdge()) {
      newPath = Path.nullPath;
    } else {
      List<PathEdge> pathEdges = Lists.newArrayList();
      for (InferenceGraphEdge edge : edges) {
        for (InferenceGraphSegment segment : edge.getSegments(distance)) {
          pathEdges.add(new PathEdge(segment, false));
        }
      }
      newPath = new Path(pathEdges, false);
    }

    final PathState newPathState =
        new PathState(newPath, predictedMotionState.getMean());

    updatedState.getPathStateParam().setValue(newPathState);
    updatedState.setParentState(previousState);

    return updatedState;
  }

  public void setRandom(Random rng) {
    this.random = rng;
  }

}