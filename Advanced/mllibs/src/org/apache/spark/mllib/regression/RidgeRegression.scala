package org.apache.spark.mllib.regression

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.optimization._
import org.apache.spark.mllib.util.MLUtils

import org.jblas.DoubleMatrix

/**
 * Regression model trained using RidgeRegression.
 *
 * @param weights Weights computed for every feature.
 * @param intercept Intercept computed for this model.
 */
class RidgeRegressionModel(
  override val weights: Array[Double],
  override val intercept: Double)
  extends GeneralizedLinearModel(weights, intercept)
  with RegressionModel with Serializable {

  // prediction: the same as LinearRegressionModel
  override def predictPoint(
    dataMatrix: DoubleMatrix,
    weightMatrix: DoubleMatrix,
    intercept: Double): Double = {
    dataMatrix.dot(weightMatrix) + intercept
  }
}

/**
 * Train a regression model with L2-regularization using Stochastic Gradient Descent.
 */
class RidgeRegressionWithSGD private (
  var stepSize: Double,
  var numIterations: Int,
  var regParam: Double,
  var miniBatchFraction: Double)
  extends GeneralizedLinearAlgorithm[RidgeRegressionModel]
  with Serializable {

  val gradient = new SquaredGradient()
  val updater = new SquaredL2Updater()

  @transient val optimizer = new GradientDescent(gradient, updater)
    .setStepSize(stepSize)
    .setNumIterations(numIterations)
    .setRegParam(regParam)
    .setMiniBatchFraction(miniBatchFraction)

  // We don't want to penalize the intercept in RidgeRegression, so set this to false.
  super.setIntercept(false)

  var yMean = 0.0
  var xColMean: DoubleMatrix = _
  var xColSd: DoubleMatrix = _

  /**
   * Construct a RidgeRegression object with default parameters
   */
  def this() = this(1.0, 100, 1.0, 1.0)

  override def setIntercept(addIntercept: Boolean): this.type = {
    // TODO: Support adding intercept.
    if (addIntercept) throw new UnsupportedOperationException("Adding intercept is not supported.")
    this
  }

  override def createModel(weights: Array[Double], intercept: Double) = {
    val weightsMat = new DoubleMatrix(weights.length, 1, weights: _*)
    val weightsScaled = weightsMat.div(xColSd)
    val interceptScaled = yMean - weightsMat.transpose().mmul(xColMean.div(xColSd)).get(0)

    new RidgeRegressionModel(weightsScaled.data, interceptScaled)
  }

  override def run(
    input: RDD[LabeledPoint],
    initialWeights: Array[Double]): RidgeRegressionModel =
    {
      val nfeatures: Int = input.first().features.length
      val nexamples: Long = input.count()

      // To avoid penalizing the intercept, we center and scale the data.
      val stats = MLUtils.computeStats(input, nfeatures, nexamples)
      yMean = stats._1
      xColMean = stats._2
      xColSd = stats._3

      val normalizedData = input.map { point =>
        val yNormalized = point.label - yMean
        val featuresMat = new DoubleMatrix(nfeatures, 1, point.features: _*)
        val featuresNormalized = featuresMat.sub(xColMean).divi(xColSd)
        LabeledPoint(yNormalized, featuresNormalized.toArray)
      }

      super.run(normalizedData, initialWeights)
    }
}

/**
 * Top-level methods for calling RidgeRegression.
 */
object RidgeRegressionWithSGD {

  /**
   * Train a RidgeRegression model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate the gradient. The weights used in
   * gradient descent are initialized using the initial weights provided.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size to be used for each iteration of gradient descent.
   * @param regParam Regularization parameter.
   * @param miniBatchFraction Fraction of data to be used per iteration.
   * @param initialWeights Initial set of weights to be used. Array should be equal in size to
   *        the number of features in the data.
   */
  def train(
    input: RDD[LabeledPoint],
    numIterations: Int,
    stepSize: Double,
    regParam: Double,
    miniBatchFraction: Double,
    initialWeights: Array[Double]): RidgeRegressionModel =
    {
      new RidgeRegressionWithSGD(stepSize, numIterations, regParam, miniBatchFraction).run(
        input, initialWeights)
    }

  /**
   * Train a RidgeRegression model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate the gradient.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size to be used for each iteration of gradient descent.
   * @param regParam Regularization parameter.
   * @param miniBatchFraction Fraction of data to be used per iteration.
   */
  def train(
    input: RDD[LabeledPoint],
    numIterations: Int,
    stepSize: Double,
    regParam: Double,
    miniBatchFraction: Double): RidgeRegressionModel =
    {
      new RidgeRegressionWithSGD(stepSize, numIterations, regParam, miniBatchFraction).run(input)
    }

  /**
   * Train a RidgeRegression model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. We use the entire data set to
   * update the gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param stepSize Step size to be used for each iteration of Gradient Descent.
   * @param regParam Regularization parameter.
   * @param numIterations Number of iterations of gradient descent to run.
   * @return a RidgeRegressionModel which has the weights and offset from training.
   */
  def train(
    input: RDD[LabeledPoint],
    numIterations: Int,
    stepSize: Double,
    regParam: Double): RidgeRegressionModel =
    {
      train(input, numIterations, stepSize, regParam, 1.0)
    }

  /**
   * Train a RidgeRegression model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using a step size of 1.0. We use the entire data set to
   * update the gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @return a RidgeRegressionModel which has the weights and offset from training.
   */
  def train(
    input: RDD[LabeledPoint],
    numIterations: Int): RidgeRegressionModel =
    {
      train(input, numIterations, 1.0, 1.0, 1.0)
    }

  def main(args: Array[String]) {
    if (args.length != 5) {
      println("Usage: RidgeRegression <master> <input_dir> <step_size> <regularization_parameter>" +
        " <niters>")
      System.exit(1)
    }
    val sc = new SparkContext(args(0), "RidgeRegression")
    val data = MLUtils.loadLabeledData(sc, args(1))
    val model = RidgeRegressionWithSGD.train(data, args(4).toInt, args(2).toDouble,
      args(3).toDouble)
    println("Weights: " + model.weights.mkString("[", ", ", "]"))
    println("Intercept: " + model.intercept)

    sc.stop()
  }
}
